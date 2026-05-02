"""
融合管线：将四路向量 concat 后经 FusionMLP 输出 512 维 L2 归一化向量。
"""
import numpy as np
import torch

from config import DEVICE
from models.registry import registry


def fuse_to_512(
    title_vec: np.ndarray,  # (384,)
    clip_vec: np.ndarray,   # (768,)
    ocr_vec: np.ndarray,    # (384,)
    asr_vec: np.ndarray,    # (384,)
) -> np.ndarray:
    """
    concat(title, clip, ocr, asr) = (1920,)
    → FusionMLP → L2 归一化 → (512,) float32
    """
    concat = np.concatenate([title_vec, clip_vec, ocr_vec, asr_vec])  # (1920,)
    tensor = torch.from_numpy(concat).unsqueeze(0).float().to(DEVICE)  # (1, 1920)
    mlp = registry.mlp()
    with torch.no_grad():
        out = mlp(tensor)  # (1, 512)
    return out.squeeze(0).cpu().numpy().astype(np.float32)
