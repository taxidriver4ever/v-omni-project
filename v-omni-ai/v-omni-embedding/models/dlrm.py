import torch
import torch.nn as nn


class VOmni_DLRM(nn.Module):
    def __init__(self, vocab_dims, dense_dim=2, video_dim=512, embed_dim=16):
        """
        VOmni_DLRM_v2: 增强版 DLRM，集成视频语义投影与多维特征交叉

        Args:
            vocab_dims (dict): 包含各离散特征词表大小的字典 (user, item, cluster, sex, country, province, city)
            dense_dim (int): 连续特征维度 (默认 2: Age + Popularity)
            video_dim (int): 原始视频向量维度 (如 512)
            embed_dim (int): 统一映射后的 Embedding 维度
        """
        super(VOmni_DLRM, self).__init__()

        # 1. 离散特征嵌入层 (7个)
        self.embeddings = nn.ModuleDict({
            'user': nn.Embedding(vocab_dims['user'], embed_dim),
            'item': nn.Embedding(vocab_dims['item'], embed_dim),
            'cluster': nn.Embedding(vocab_dims['cluster'], embed_dim),
            'sex': nn.Embedding(vocab_dims['sex'], embed_dim),
            'country': nn.Embedding(vocab_dims['country'], embed_dim),
            'province': nn.Embedding(vocab_dims['province'], embed_dim),
            'city': nn.Embedding(vocab_dims['city'], embed_dim)
        })

        # 2. Bottom MLP: 处理连续特征并映射到 embedding 空间
        self.bottom_mlp = nn.Sequential(
            nn.Linear(dense_dim, 32),
            nn.ReLU(),
            nn.Linear(32, embed_dim),
            nn.ReLU()
        )

        # 3. Video Projection: 视频高维向量投影
        self.video_proj = nn.Linear(video_dim, embed_dim)

        # 4. 交互层配置
        # 向量总数 = 7(离散) + 1(连续Bottom输出) + 1(视频投影输出) = 9
        self.num_vectors = 7 + 1 + 1
        self.num_interactions = (self.num_vectors * (self.num_vectors - 1)) // 2  # 36个对两交叉项

        # 5. Top MLP: 最终决策层
        # 输入维度 = 交叉项(36) + Bottom MLP输出(embed_dim)
        self.top_mlp = nn.Sequential(
            nn.Linear(self.num_interactions + embed_dim, 64),
            nn.ReLU(),
            nn.Linear(64, 32),
            nn.ReLU(),
            nn.Linear(32, 1),
            nn.Sigmoid()
        )

    def forward(self, sparse_inputs, dense_inputs, video_vector):
        """
        Args:
            sparse_inputs (dict): 键为特征名, 值为 [batch_size] 的 LongTensor
            dense_inputs (Tensor): [batch_size, dense_dim]
            video_vector (Tensor): [batch_size, video_dim]
        """
        # --- 1. 获取所有特征向量 ---
        # 离散特征向量
        v_sparse = [self.embeddings[name](sparse_inputs[name]) for name in self.embeddings.keys()]

        # 连续特征投影
        v_dense = self.bottom_mlp(dense_inputs)

        # 视频特征投影
        v_video = self.video_proj(video_vector)

        # --- 2. 构造交互矩阵 ---
        # 堆叠所有向量: [batch_size, 9, embed_dim]
        combined = torch.stack(v_sparse + [v_dense, v_video], dim=1)

        # 批量矩阵乘法做内积: [batch_size, 9, 9]
        dot_products = torch.bmm(combined, combined.transpose(1, 2))

        # 提取上三角索引 (排除自相关项)
        rows, cols = torch.triu_indices(self.num_vectors, self.num_vectors, offset=1)
        interactions = dot_products[:, rows, cols]  # [batch_size, 36]

        # --- 3. 拼接并进入 Top MLP ---
        # 按照 DLRM 论文逻辑，将交叉项与 Bottom MLP 的输出拼接
        x_final = torch.cat([interactions, v_dense], dim=1)

        return self.top_mlp(x_final)


# --- 快速验证 ---
if __name__ == "__main__":
    # 配置
    vocabs = {'user': 100, 'item': 100, 'cluster': 5, 'sex': 2, 'country': 10, 'province': 20, 'city': 50}
    model = VOmni_DLRM_v2(vocabs)

    # 模拟数据
    batch_size = 4
    s_in = {k: torch.randint(0, v, (batch_size,)) for k, v in vocabs.items()}
    d_in = torch.randn(batch_size, 2)
    v_in = torch.randn(batch_size, 512)

    # 前向传播
    pred = model(s_in, d_in, v_in)
    print(f"输出形状: {pred.shape}")  # 预期 [4, 1]
    print(f"预测结果: \n{pred}")