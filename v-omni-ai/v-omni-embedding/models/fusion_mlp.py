"""
FusionMLP：将 title / CLIP / OCR / ASR 四路向量融合为 512 维输出。

输入: concat(title_vec[384], clip_vec[768], ocr_vec[384], asr_vec[384]) = 1920 维
输出: 512 维 L2 归一化向量
"""
import torch
import torch.nn as nn
import torch.nn.functional as F


class FusionMLP(nn.Module):
    def __init__(self, in_dim: int = 1920, out_dim: int = 512):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(in_dim, 1024),
            nn.LayerNorm(1024),
            nn.GELU(),
            nn.Dropout(0.1),
            nn.Linear(1024, out_dim),
            nn.LayerNorm(out_dim),
        )
        self._init_weights()

    def _init_weights(self):
        for m in self.modules():
            if isinstance(m, nn.Linear):
                nn.init.xavier_uniform_(m.weight)
                nn.init.zeros_(m.bias)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return F.normalize(self.net(x), p=2, dim=-1)
