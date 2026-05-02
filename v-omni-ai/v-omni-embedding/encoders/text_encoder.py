"""
文本编码器：
  get_title_vector  — title 字符串 → 384 维向量
  get_clip_vector   — 帧列表 → PersonSuppressedCLIP → 768 维向量
"""
import logging
from pathlib import Path
from typing import List
import torch
import numpy as np

from encoders.clip_encoder import PersonSuppressedCLIP
from models.registry import registry

log = logging.getLogger(__name__)


def get_title_vector(title: str) -> np.ndarray:
    """
    输入: 视频标题字符串（空字符串自动替换为占位符）
    输出: (384,) float32 向量
    """
    model = registry.text()
    text = title.strip() if title.strip() else "[NO TITLE]"
    vec = model.encode(text, normalize_embeddings=True, show_progress_bar=False)
    return vec.astype(np.float32)


def get_clip_vector(frames: List[Path]) -> np.ndarray:
    """
    使用 PersonSuppressedCLIP 对帧编码：
      YOLO 检测人物 → attention bias 压制人物 patch → Mean Pooling → (768,)
    """
    clip_model, clip_preprocess = registry.clip()
    encoder = PersonSuppressedCLIP(
        clip_model=clip_model,
        clip_preprocess=clip_preprocess,
        person_suppress_weight=-2.0,
        person_conf_threshold=0.4,
        inject_last_n_layers=3,
    )
    return encoder.encode_frames(frames)


def get_clip_text_vector(query: str) -> np.ndarray:
    """
    输入: 用户搜索词字符串
    输出: (768,) float32 向量
    作用: 将文本转为 CLIP 空间的向量，用于与视频视觉特征对齐
    """
    clip_model, _ = registry.clip()

    # 1. 清理输入
    text = query.strip() if query.strip() else "video"

    # 2. Tokenization & Encoding
    # 注意：CLIP 文本分支通常有 77 个 token 的长度限制
    import clip  # 假设你使用的是 openai/clip 或类似的包装库
    try:
        # 将文本转为模型可识别的 token 张量
        text_tokens = clip.tokenize([text]).to(next(clip_model.parameters()).device)

        with torch.no_grad():
            # 调用 CLIP 的 encode_text 方法
            text_features = clip_model.encode_text(text_tokens)

            # 3. L2 归一化 (非常重要，否则余弦相似度计算会出问题)
            text_features /= text_features.norm(dim=-1, keepdim=True)

        return text_features.cpu().numpy().flatten().astype(np.float32)

    except Exception as e:
        log.error(f"CLIP 文本编码失败: {e}")
        # 返回全零向量作为 fallback，防止下游 FusionMLP 崩溃
        return np.zeros(768, dtype=np.float32)
