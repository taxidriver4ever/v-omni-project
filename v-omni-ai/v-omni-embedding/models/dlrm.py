import torch
import torch.nn as nn


class DLRM(nn.Module):
    def __init__(self, dense_in_dim, sparse_vocab_sizes, emb_dim=128):
        super(DLRM, self).__init__()
        self.num_sparse_fields = len(sparse_vocab_sizes)

        # 1. Sparse Embedding Layers
        self.embeddings = nn.ModuleList([
            nn.Embedding(vocab_size, emb_dim) for vocab_size in sparse_vocab_sizes
        ])

        # 2. Bottom MLP (Dense features)
        self.bottom_mlp = nn.Sequential(
            nn.Linear(dense_in_dim, 256),
            nn.ReLU(),
            nn.Linear(256, emb_dim),
            nn.ReLU()
        )

        # 3. Top MLP (Interaction + Bottom output)
        num_interact_fields = self.num_sparse_fields + 1
        interaction_dim = (num_interact_fields * (num_interact_fields - 1)) // 2
        top_mlp_in_dim = emb_dim + interaction_dim

        self.top_mlp = nn.Sequential(
            nn.Linear(top_mlp_in_dim, 128),
            nn.ReLU(),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 1)
        )
        self.sigmoid = nn.Sigmoid()

    def forward(self, dense_x, sparse_x):
        # 处理连续特征
        x_bottom = self.bottom_mlp(dense_x)

        # 处理离散特征并查表
        sparse_embs = [self.embeddings[i](sparse_x[:, i]) for i in range(self.num_sparse_fields)]

        # 显式点积交叉 (Feature Interaction)
        combined = torch.stack([x_bottom] + sparse_embs, dim=1)
        interactions = torch.bmm(combined, combined.transpose(1, 2))

        # 提取上三角索引
        n_fields = combined.size(1)
        rows, cols = torch.triu_indices(n_fields, n_fields, offset=1)
        x_int = interactions[:, rows, cols]

        # 拼接原始抽象特征并进入 Top MLP
        x_final = torch.cat([x_bottom, x_int], dim=-1)
        return self.sigmoid(self.top_mlp(x_final))