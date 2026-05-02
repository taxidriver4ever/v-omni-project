"""
ModelRegistry：所有模型的懒加载单例容器。

服务启动时调用 warmup() 预热所有模型，此后各模块直接取引用，
无需重复初始化。线程安全由 GIL 保证（所有模型只写一次）。
"""
import logging
from pathlib import Path

import torch

from config import (
    DEVICE,
    WHISPER_MODEL,
    TEXT_MODEL,
    MLP_WEIGHTS,
    KMEANS_N_CLUSTERS,
    KMEANS_BIZ_DIM,
    USER_MODEL_DIM,
    USER_MODEL_BIZ_DIM,
    USER_MODEL_MAX_SHORT,
)
from models.fusion_mlp import FusionMLP
from models.kmeans_processor import KMeansProcessor
from models.long_short_model import LongShortUserModel

log = logging.getLogger(__name__)


class ModelRegistry:
    """懒加载 + 单例。所有模型只初始化一次，进程内共享。"""

    def __init__(self):
        self._clip_model = None
        self._clip_preprocess = None
        self._text_model = None
        self._whisper_model = None
        self._ocr_reader = None
        self._mlp: FusionMLP | None = None
        self._kmeans: KMeansProcessor | None = None
        self._long_short: LongShortUserModel | None = None

    # ── CLIP ──────────────────────────────────────────────────────────────────
    def clip(self):
        if self._clip_model is None:
            import clip
            log.info(f"加载 CLIP ViT-L/14@336px on {DEVICE}")
            self._clip_model, self._clip_preprocess = clip.load(
                "ViT-L/14@336px", device=DEVICE, jit=False
            )
            self._clip_model.eval()
            log.info(f"CLIP 就绪，视觉输出维度: {self._clip_model.visual.output_dim}")
        return self._clip_model, self._clip_preprocess

    # ── Sentence-Transformer ──────────────────────────────────────────────────
    def text(self):
        if self._text_model is None:
            from sentence_transformers import SentenceTransformer
            log.info(f"加载 SentenceTransformer: {TEXT_MODEL}")
            self._text_model = SentenceTransformer(TEXT_MODEL, device=DEVICE)
        return self._text_model

    # ── faster-whisper ────────────────────────────────────────────────────────
    def whisper(self):
        if self._whisper_model is None:
            from faster_whisper import WhisperModel
            compute = "float16" if DEVICE == "cuda" else "int8"
            log.info(f"加载 faster-whisper [{WHISPER_MODEL}] compute={compute}")
            self._whisper_model = WhisperModel(
                WHISPER_MODEL, device=DEVICE, compute_type=compute
            )
        return self._whisper_model

    # ── EasyOCR ───────────────────────────────────────────────────────────────
    def ocr(self):
        if self._ocr_reader is None:
            import easyocr
            log.info("加载 EasyOCR (ch_sim + en)")
            self._ocr_reader = easyocr.Reader(
                ["ch_sim", "en"], gpu=(DEVICE == "cuda"), verbose=False
            )
        return self._ocr_reader

    # ── FusionMLP ─────────────────────────────────────────────────────────────
    def mlp(self) -> FusionMLP:
        if self._mlp is None:
            self._mlp = FusionMLP().to(DEVICE).eval()
            p = Path(MLP_WEIGHTS)
            if p.exists():
                state = torch.load(str(p), map_location=DEVICE)
                self._mlp.load_state_dict(state)
                log.info(f"MLP 权重已加载: {p}")
            else:
                log.warning(f"MLP 权重不存在 [{p}]，使用 Xavier 占位初始化并保存")
                p.parent.mkdir(parents=True, exist_ok=True)
                torch.save(self._mlp.state_dict(), str(p))
        return self._mlp

    # ── KMeansProcessor ───────────────────────────────────────────────────────
    def kmeans(self) -> KMeansProcessor:
        """
        KMeansProcessor 是纯 CPU sklearn 对象，无需 GPU 加载。
        每个请求共享同一个实例（fit_predict 是幂等的）。
        """
        if self._kmeans is None:
            log.info(f"初始化 KMeansProcessor (K={KMEANS_N_CLUSTERS})")
            self._kmeans = KMeansProcessor(
                n_clusters=KMEANS_N_CLUSTERS,
                biz_dim=KMEANS_BIZ_DIM,
            )
        return self._kmeans

    # ── LongShortUserModel ────────────────────────────────────────────────────
    def long_short(self) -> LongShortUserModel:
        if self._long_short is None:
            log.info(
                f"初始化 LongShortUserModel "
                f"(dim={USER_MODEL_DIM}, max_len={USER_MODEL_MAX_SHORT})"
            )
            self._long_short = LongShortUserModel(
                dim=USER_MODEL_DIM,
                biz_dim=USER_MODEL_BIZ_DIM,
                max_len=USER_MODEL_MAX_SHORT,
            ).to(DEVICE).eval()
        return self._long_short

    # ── 预热 ──────────────────────────────────────────────────────────────────
    def warmup(self) -> None:
        """服务启动时调用，避免首次请求触发冷启动延迟。"""
        log.info(f"预热所有模型（device={DEVICE}）...")
        self.clip()
        self.text()
        self.whisper()
        self.ocr()
        self.mlp()
        self.kmeans()
        self.long_short()
        log.info("✅ 所有模型已就绪")


# 进程级全局单例
registry = ModelRegistry()
