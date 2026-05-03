import torch
import torch.nn as nn
import numpy as np


# ==========================================
# 1. 模型结构定义 (和推理类放在一起方便加载)
# ==========================================
class RerankQNetwork(nn.Module):
    def __init__(self, state_dim):
        super(RerankQNetwork, self).__init__()
        self.net = nn.Sequential(
            nn.Linear(state_dim, 128),
            nn.ReLU(),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 1)  # 输出 Q 值：预测当前选择对整列表的长期贡献
        )

    def forward(self, x):
        return self.net(x)


# ==========================================
# 2. 推理重排类
# ==========================================
class Reranker:
    def __init__(self, model_path=None, emb_dim=16, top_k=5):
        """
        model_path: 训练好的 .pth 文件路径。如果为 None 则初始化一个空模型。
        """
        self.emb_dim = emb_dim
        self.top_k = top_k
        self.state_dim = emb_dim * 2 + 1  # 候选项emb + 上下文emb + 原始分

        self.model = RerankQNetwork(self.state_dim)

        if model_path:
            # 加载模型权重
            self.model.load_state_dict(torch.load(model_path, map_location=torch.device('cpu')))
            self.model.eval()
            print(f"✅ V-Omni RL 模型已从 {model_path} 加载")

    def rerank(self, scored_items):
        """
        RL 重排核心逻辑
        scored_items: 列表，格式为 [{'id': '..', 'score': 0.9, 'emb': np.array}, ...]
        """
        selected = []
        remaining = scored_items.copy()

        # 贪心序列生成：每次选出 Q 值最高的一项，直到选满 top_k
        while len(selected) < self.top_k and remaining:
            # 1. 构建当前列表的上下文特征 (Context Embedding)
            if not selected:
                s_emb = np.zeros(self.emb_dim)
            else:
                s_emb = np.mean([s['emb'] for s in selected], axis=0)

            # 2. 批量构造候选特征
            features = []
            for candidate in remaining:
                # 特征工程：候选物品 emb + 当前列表平均 emb + 基础分
                feat = np.concatenate([candidate['emb'], s_emb, [candidate['score']]])
                features.append(feat)

            features_tensor = torch.tensor(np.array(features), dtype=torch.float32)

            # 3. 推理 Q 值
            with torch.no_grad():
                q_values = self.model(features_tensor).squeeze(-1)

            # 4. 选择 Q 值最大的索引
            # 处理剩余 1 个物品时 squeeze 可能导致标量的情况
            if q_values.dim() == 0:
                best_idx = 0
            else:
                best_idx = torch.argmax(q_values).item()

            # 5. 从剩余池中移入已选池
            best_item = remaining.pop(best_idx)
            selected.append(best_item)

        return selected


# ==========================================
# 3. 快速测试 (Demo)
# ==========================================
if __name__ == "__main__":
    # 模拟 10 个召回项
    EMB_DIM = 16
    mock_items = []
    for i in range(10):
        mock_items.append({
            'id': f'video_{i}',
            'score': np.random.uniform(0.5, 1.0),
            'emb': np.random.randn(EMB_DIM).astype(np.float32)
        })

    # 初始化重排器 (这里不传路径，仅演示逻辑)
    reranker = V_Omni_RLReranker(emb_dim=EMB_DIM, top_k=5)

    # 执行重排
    results = reranker.rerank(mock_items)

    print("--- V-Omni RL Rerank Result ---")
    for idx, item in enumerate(results):
        print(f"Rank {idx + 1}: {item['id']} | Base Score: {item['score']:.4f}")