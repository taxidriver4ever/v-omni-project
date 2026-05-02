"""
ASR 编码器：faster-whisper 转录音频 → SentenceTransformer → 384 维向量。
无音频时返回 "[NO AUDIO]" 占位向量。
"""
import logging
from pathlib import Path

import numpy as np

from models.registry import registry

log = logging.getLogger(__name__)


def get_asr_vector(audio_path: Path, has_audio: bool) -> np.ndarray:
    """
    输入:
      audio_path : 16kHz 单声道 WAV 路径
      has_audio  : 视频是否含音频流（由 extract_audio 返回）
    输出: (384,) float32 向量
    """
    text_model = registry.text()

    if not has_audio or not audio_path.exists():
        vec = text_model.encode("[NO AUDIO]", normalize_embeddings=True, show_progress_bar=False)
        return vec.astype(np.float32)

    whisper = registry.whisper()
    segments, info = whisper.transcribe(
        str(audio_path),
        beam_size=5,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
    )
    transcript = " ".join(seg.text.strip() for seg in segments)
    log.info(
        f"ASR 完成: lang={info.language} ({info.language_probability:.0%}), "
        f"{len(transcript)} 字符"
    )

    text = transcript.strip() if transcript.strip() else "[SILENT]"
    vec = text_model.encode(text, normalize_embeddings=True, show_progress_bar=False)
    return vec.astype(np.float32)
