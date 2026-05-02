import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np

# --- 业务级全局常量定义 ---
USER_MODEL_DIM = 512  # 模型主干特征的维度 (语义空间的标准宽度)
USER_MODEL_BIZ_DIM = 4  # 附加业务特征的维度 (如: 活跃度、消费等级等离散指标)
USER_MODEL_MAX_SHORT = 24  # 短期序列的最大窗口长度


class LongShortUserModel(nn.Module):
    """
    长短期用户兴趣融合模型
    职责: 接收用户当前态(Q)与历史长短序列(K,V)，通过注意力机制提取上下文，
          并与业务特征融合，输出一个兼顾“当前意图”与“历史偏好”的综合表征。
    """

    def __init__(
            self,
            dim: int = USER_MODEL_DIM,
            biz_dim: int = USER_MODEL_BIZ_DIM,
            max_len: int = USER_MODEL_MAX_SHORT,
    ):
        super().__init__()  # 注册为 PyTorch 的标准 Module
        self.dim = dim  # 基础语义维度 (512)
        self.v_dim = dim + biz_dim  # Value 向量的实际宽度 (516)

        # 1. 核心映射层 (转接头)
        # 负责接收 516 维的混合特征，将其重新投影回 512 维，实现业务特征与语义特征的深度交叉
        self.output_layer = nn.Linear(self.v_dim, dim)

        # 2. 层归一化 (稳压器)
        # 保证经过线性变换和残差相加后，特征向量的模长能稳定在 sqrt(512) ≈ 22.6 附近，防止梯度爆炸
        self.norm = nn.LayerNorm(dim)

        # 3. 专家级冷启动初始化 (Soft Identity Initialization)
        with torch.no_grad():  # 初始化阶段无需计算梯度
            # a. 将权重左侧 dim×dim 部分设为单位矩阵
            nn.init.eye_(self.output_layer.weight[:, :self.dim])
            # b. 核心改动：乘以 0.5 权重。
            # 理由：防止在初始状态下 Q(当前意图) 的信号过载，给模型留出融合历史信息和业务特征的“学习余量”
            self.output_layer.weight.data[:, :self.dim] *= 0.5
            # c. 偏置清零，右侧 biz_dim 部分保留系统默认的随机微小值，由模型自驱动学习
            nn.init.zeros_(self.output_layer.bias)

        # 4. 注意力机制超参数
        # 短期位置偏置：设置为可学习参数，范围 0~5.0。相比原来的 0~2.0 增加了对序列位置的敏感度
        self.position_bias = nn.Parameter(torch.linspace(0.0, 20.0, max_len))
        # 缩放因子 (Scale)：使用标准的 sqrt(d_k)，防止点积结果过大导致 Softmax 梯度消失
        self.temp_scale = np.sqrt(dim)
        self.long_term_scale = np.sqrt(dim)

    def _get_attention(
            self,
            q: torch.Tensor,  # [Batch, dim]
            k: torch.Tensor,  # [Batch, SeqLen, dim]
            v: torch.Tensor,  # [Batch, SeqLen, v_dim]
            bias: torch.Tensor | None,  # [SeqLen]
            scale: float,  # float
            mask: torch.Tensor | None = None,  # [Batch, SeqLen] (True表示有效，False表示Padding)
    ) -> tuple[torch.Tensor, torch.Tensor]:

        # 将 Q 扩展维度以进行批量的矩阵乘法运算：[Batch, dim] -> [Batch, 1, dim]
        q_ext = q[:, None, :]

        # 计算 Q 和 K 的点积相似度，并立即进行 Scale 缩放
        # k.transpose(-1, -2) 将 K 转置为 [Batch, dim, SeqLen]
        # 结果 scores 维度为 [Batch, 1, SeqLen]
        scores = torch.matmul(q_ext, k.transpose(-1, -2)) / scale

        # 叠加位置偏置 (利用广播机制，自动加到每个 Batch 的 SeqLen 维度上)
        if bias is not None:
            scores = scores + bias

        # 处理掩码 (Padding Mask)
        if mask is not None:
            # ~mask[:, None, :] 取反并扩展维度对齐 scores -> [Batch, 1, SeqLen]
            pad_mask = ~mask[:, None, :]
            # 将填充位置的得分设为负无穷，Softmax 后权重将严格为 0
            scores = scores.masked_fill(pad_mask, float("-inf"))

        # 通过 Softmax 转化为概率分布 (权重矩阵)
        weights = F.softmax(scores, dim=-1)
        # 兜底安全策略：如果全序列被 Mask，Softmax 会产生 NaN，这里将其强转为 0.0
        weights = torch.nan_to_num(weights, nan=0.0)

        # 加权聚合 Value 矩阵：[Batch, 1, SeqLen] x [Batch, SeqLen, v_dim] -> [Batch, 1, v_dim]
        context = torch.matmul(weights, v)

        # squeeze(1) 移除多余的维度 -> [Batch, v_dim]
        return context.squeeze(1), weights

    def forward(
            self,
            q_current: torch.Tensor,  # [Batch, dim]
            sk: torch.Tensor,  # [Batch, max_short, dim]
            sv: torch.Tensor,  # [Batch, max_short, v_dim]
            lk: torch.Tensor,  # [Batch, max_long, dim]
            lv: torch.Tensor,  # [Batch, max_long, v_dim]
            short_mask: torch.Tensor | None = None,  # [Batch, max_short]
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:

        # ==========================================
        # 阶段 1: 提取多粒度上下文
        # ==========================================
        # 短期上下文 (包含业务特征, v_dim 维度)，带有强烈的时序位置偏置
        s_ctx, s_w = self._get_attention(
            q_current, sk, sv, bias=self.position_bias, scale=self.temp_scale, mask=short_mask
        )
        # 长期上下文 (同样为 v_dim 维度)，纯语义相似度匹配，无位置干预
        l_ctx, l_w = self._get_attention(
            q_current, lk, lv, bias=None, scale=self.long_term_scale, mask=None
        )

        # ==========================================
        # 阶段 2: 兴趣融合与特征加工
        # ==========================================
        # 静态权重融合：85% 侧重短期即时需求，15% 兼顾长期底色
        fused_context = 0.85 * s_ctx + 0.15 * l_ctx

        # 将 516 维的混合特征，通过带特殊初始化的 Linear 层降维，交叉提炼成 512 维
        transformed = self.output_layer(fused_context)

        # ==========================================
        # 阶段 3: 软残差与标准化
        # ==========================================
        # Soft Residual Connection (软残差连接)
        # 核心逻辑：降低 q_current 的比重(0.5)，迫使网络高度重视 transformed 提取的历史与业务特征
        combined = transformed + 0.5 * q_current

        # 过一层 LayerNorm，约束方差，抹平样本间的极端值波动
        fused_out = self.norm(combined)

        # # ==========================================
        # # 阶段 4: 推理期监控面板
        # # ==========================================
        # if not self.training:
        #     self._debug_print(q_current, fused_out)

        return fused_out, s_w, l_w

    # def _debug_print(self, q_current: torch.Tensor, fused_out: torch.Tensor):
    #     """内部诊断工具：在非训练状态下，监控模型特征变换的健康度"""
    #     # 放宽打印限制，防止 Tensor 被折叠
    #     torch.set_printoptions(threshold=10_000, linewidth=200, precision=4, sci_mode=False)
    #
    #     # 转移到 CPU 并转为 Numpy 以便后续可能的复杂数值分析
    #     q_np = q_current[0].detach().cpu().numpy()
    #     out_np = fused_out[0].detach().cpu().numpy()
    #
    #     # 计算输入输出的余弦相似度，衡量特征方向的偏移量
    #     cos_sim = F.cosine_similarity(q_current, fused_out).mean().item()
    #
    #     print("\n" + "📊" * 10 + " 用户模型数值诊断 " + "📊" * 10)
    #     print(f"Input  Norm: {np.linalg.norm(q_np):.4f}")
    #     print(f"Output Norm: {np.linalg.norm(out_np):.4f} (期望值应接近 22.6)")
    #     print(f"CosSim (Q vs Out): {cos_sim:.4f} (期望值在 0.85~0.95 之间)")
    #
    #     # 相似度过高意味着模型没有学进去新的历史/业务特征，发生了“信号短路”
    #     if cos_sim > 0.99:
    #         print("⚠️ 警告：信号方向与输入过近，模型可能依然比较固执！")
    #
    #     print("-" * 60 + "\n")
    #     # 恢复 PyTorch 默认打印配置，避免污染外部环境
    #     torch.set_printoptions(profile='default')