import torch
import torch.nn as nn


class TransformerRanker(nn.Module):

    def __init__(self, emb_dim=512, nhead=8):

        super().__init__()

        encoder_layer = nn.TransformerEncoderLayer(
            d_model=emb_dim,
            nhead=nhead,
            batch_first=True
        )

        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=2)

        self.mlp = nn.Sequential(
            nn.Linear(emb_dim * 2, 256),
            nn.ReLU(),
            nn.Linear(256, 1)
        )

        self.sigmoid = nn.Sigmoid()

    def forward(self, history, mask, target):

        # history: [B,T,512]
        # mask: [B,T]
        # target: [B,512]

        h = self.encoder(history)

        h = h.mean(dim=1)

        x = torch.cat([h, target], dim=-1)

        out = self.mlp(x)

        return self.sigmoid(out)