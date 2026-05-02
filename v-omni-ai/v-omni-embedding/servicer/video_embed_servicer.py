"""
VideoEmbedServicer：实现 VideoEmbedService.GetVideoEmbedding RPC。

完整 Pipeline：
  下载视频 → 抽帧 → 提音频
  → CLIP(768) + OCR(384) + ASR(384) + Title(384)
  → concat(1920) → FusionMLP → 512 维 L2 归一化向量
"""
import logging
import tempfile
import time
from pathlib import Path

import grpc

import video_embed_pb2
import video_embed_pb2_grpc

from encoders.text_encoder import get_clip_vector, get_title_vector
from encoders.ocr_encoder import get_ocr_vector
from encoders.asr_encoder import get_asr_vector
from pipeline.video_utils import download_video, extract_frames, extract_audio
from pipeline.fusion import fuse_to_512

log = logging.getLogger(__name__)


class VideoEmbedServicer(video_embed_pb2_grpc.VideoEmbedServiceServicer):

    def GetVideoEmbedding(self, request, context):
        """
        输入: video_url, title
        输出: embedding[512], status, message
        """
        t0 = time.time()
        video_url = request.video_url.strip()
        title = request.title.strip()
        log.info(f"收到请求 | url={video_url} | title={title!r}")

        try:
            with tempfile.TemporaryDirectory(prefix="vemb_") as tmp:
                tmp = Path(tmp)
                video_path = tmp / "video.mp4"
                audio_path = tmp / "audio.wav"
                frames_dir = tmp / "frames"
                frames_dir.mkdir()

                # Step 1: 下载
                download_video(video_url, video_path)

                # Step 2: 抽帧 + 提音频
                frames = extract_frames(video_path, frames_dir)
                has_audio = extract_audio(video_path, audio_path)

                # Step 3-6: 四路特征
                log.info("Step 3/6: CLIP 视觉特征")
                clip_vec = get_clip_vector(frames)

                log.info("Step 4/6: OCR 文字提取")
                ocr_vec = get_ocr_vector(frames)

                log.info("Step 5/6: Whisper ASR")
                asr_vec = get_asr_vector(audio_path, has_audio)

                title_vec = get_title_vector(title)

                # Step 7: MLP 融合
                log.info("Step 6/6: MLP 融合")
                embedding = fuse_to_512(title_vec, clip_vec, ocr_vec, asr_vec)

            elapsed = time.time() - t0
            log.info(f"✅ 完成，耗时 {elapsed:.2f}s，输出维度: {embedding.shape}")

            return video_embed_pb2.VideoEmbedResponse(
                embedding=embedding.tolist(),
                status="ok",
                message=(
                    f"耗时 {elapsed:.2f}s | 帧数 {len(frames)} | "
                    f"音频 {'有' if has_audio else '无'}"
                ),
            )

        except Exception as e:
            log.exception("Pipeline 异常")
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return video_embed_pb2.VideoEmbedResponse(
                embedding=[],
                status="error",
                message=str(e),
            )
