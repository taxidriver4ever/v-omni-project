"""
OCR 编码器：对视频帧稀疏采样后用 EasyOCR 提取文字，
再经 SentenceTransformer 编码为 384 维向量。

修复了 EasyOCR 内部 utils.py 对彩色图像解包 img.shape 导致的 ValueError：
  强制将图像转为灰度图再传入，保证 shape 长度固定为 2。
"""
import logging
from pathlib import Path
from typing import List

import cv2
import numpy as np

from config import OCR_MAX_FRAMES
from models.registry import registry

log = logging.getLogger(__name__)


def get_ocr_vector(frames: List[Path]) -> np.ndarray:
    """
    输入: 视频帧路径列表
    输出: (384,) float32 向量
    """
    reader = registry.ocr()
    text_model = registry.text()

    # 均匀稀疏采样
    sampled = frames
    if len(frames) > OCR_MAX_FRAMES:
        step = len(frames) / OCR_MAX_FRAMES
        sampled = [frames[int(i * step)] for i in range(OCR_MAX_FRAMES)]

    seen, texts = set(), []
    for p in sampled:
        try:
            img = cv2.imread(str(p))
            if img is None:
                log.warning(f"无法读取图片: {p}")
                continue
            # 转灰度彻底规避 EasyOCR 彩色图像解包 ValueError
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            results = reader.readtext(gray, detail=1)
            for res in results:
                if len(res) >= 2:
                    text = str(res[-2]).strip()
                    conf = float(res[-1])
                    if conf >= 0.5 and text and text not in seen:
                        seen.add(text)
                        texts.append(text)
        except Exception as e:
            log.warning(f"OCR 解析异常 {p.name}: {e}")

    merged = " ".join(texts) if texts else "[NO OCR TEXT]"
    log.info(f"OCR 完成 | 去重文字数: {len(texts)} | 概览: {merged[:50]}...")

    vec = text_model.encode(merged, normalize_embeddings=True, show_progress_bar=False)
    return vec.astype(np.float32)
