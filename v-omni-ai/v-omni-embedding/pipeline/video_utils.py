"""
视频工具函数：下载、抽帧、提音频。
所有 ffmpeg / ffprobe / curl 调用均封装于此，其他模块不直接调用系统命令。
"""
import logging
import subprocess
from pathlib import Path
from typing import List

from config import FRAME_INTERVAL_SEC, MAX_FRAMES, MIN_FRAMES

log = logging.getLogger(__name__)


# ── 内部 shell 工具 ────────────────────────────────────────────────────────────
def _run(cmd: List[str], check: bool = True) -> subprocess.CompletedProcess:
    """同步运行 shell 命令；check=True 时失败抛出 RuntimeError。"""
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode != 0:
        raise RuntimeError(
            f"命令失败: {' '.join(cmd)}\n{result.stderr.decode()}"
        )
    return result


# ── 公开接口 ───────────────────────────────────────────────────────────────────
def download_video(url: str, dest: Path) -> None:
    """用 curl 流式下载视频到 dest 路径。"""
    log.info(f"⬇ 下载视频: {url}")
    _run(["curl", "-L", "-o", str(dest), "--max-time", "600", url])
    log.info(f"下载完成: {dest.stat().st_size / 1e6:.1f} MB")


def get_duration(video_path: Path) -> float:
    """ffprobe 获取视频时长（秒）。"""
    r = _run([
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(video_path),
    ])
    return float(r.stdout.decode().strip())


def extract_frames(video_path: Path, frames_dir: Path) -> List[Path]:
    """
    动态抽帧策略：
      - 每 FRAME_INTERVAL_SEC 秒一帧
      - 超过 MAX_FRAMES 时等比稀疏；不足 MIN_FRAMES 时加密
      - 输出 336×336 居中裁剪（CLIP ViT-L/14@336px 推荐尺寸）
    返回排序后的帧路径列表。
    """
    duration = get_duration(video_path)
    interval = float(FRAME_INTERVAL_SEC)
    expected = int(duration / interval)

    if expected > MAX_FRAMES:
        interval = duration / MAX_FRAMES
    elif expected < MIN_FRAMES:
        interval = max(duration / MIN_FRAMES, 0.5)

    log.info(f"视频时长 {duration:.1f}s，抽帧间隔 {interval:.2f}s")
    _run([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-i", str(video_path),
        "-vf", (
            f"fps=1/{interval:.4f},"
            "scale=336:336:force_original_aspect_ratio=decrease,"
            "pad=336:336:(ow-iw)/2:(oh-ih)/2"
        ),
        "-q:v", "2",
        str(frames_dir / "frame_%04d.jpg"),
    ])
    frames = sorted(frames_dir.glob("frame_*.jpg"))
    log.info(f"抽帧完成: {len(frames)} 帧")
    return frames


def extract_audio(video_path: Path, audio_path: Path) -> bool:
    """
    ffmpeg 提取 16kHz 单声道 WAV。
    无音频流时返回 False（不抛出异常）。
    """
    r = _run([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-i", str(video_path),
        "-vn", "-acodec", "pcm_s16le",
        "-ar", "16000", "-ac", "1",
        str(audio_path),
    ], check=False)
    if r.returncode != 0:
        log.warning("视频无音频流，跳过 ASR")
        return False
    log.info(f"音频提取完成: {audio_path.stat().st_size / 1e3:.0f} KB")
    return True
