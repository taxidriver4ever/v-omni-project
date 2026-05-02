import torch
import torch.nn as nn


class DLRM(nn.Module):
    def __init__(self, emb_dim=512):
        super(DLRM, self).__init__()

        # 用户塔（其实这里是MLP压缩）
        self.user_mlp = nn.Sequential(
            nn.Linear(emb_dim, 256),
            nn.ReLU(),
            nn.Linear(256, 128)
        )

        # 视频塔
        self.item_mlp = nn.Sequential(
            nn.Linear(emb_dim, 256),
            nn.ReLU(),
            nn.Linear(256, 128)
        )

        # 交叉层（核心）
        self.fc = nn.Sequential(
            nn.Linear(128 * 2, 128),
            nn.ReLU(),
            nn.Linear(128, 1)
        )

        self.sigmoid = nn.Sigmoid()

    def forward(self, user_emb, item_emb):

        u = self.user_mlp(user_emb)
        v = self.item_mlp(item_emb)

        x = torch.cat([u, v], dim=-1)

        out = self.fc(x)

        return self.sigmoid(out)