import logging
import time
import numpy as np

# 导入生成的文件
import video_embed_pb2
import video_embed_pb2_grpc

from encoders.text_encoder import get_clip_text_vector, get_title_vector
from pipeline.fusion import fuse_to_512

log = logging.getLogger(__name__)


class TextEmbedServicer(video_embed_pb2_grpc.TextEmbedServiceServicer):
    """
    实现 TextEmbedService 接口，供 Java 调用进行向量搜索
    """

    def GetTextEmbedding(self, request, context):
        t0 = time.time()
        query = request.query_text.strip()

        if not query:
            return video_embed_pb2.TextEmbedResponse(
                embedding=[], status="error", message="Empty query"
            )

        try:
            # 1. 提取两路文本特征
            clip_text_vec = get_clip_text_vector(query)  # 768维
            semantic_vec = get_title_vector(query)  # 384维

            # 2. 补齐 OCR/ASR 零向量 (保持 1920 维输入给 FusionMLP)
            ocr_placeholder = np.zeros(384, dtype=np.float32)
            asr_placeholder = np.zeros(384, dtype=np.float32)

            # 3. 融合投影到 512 维
            embedding = fuse_to_512(semantic_vec, clip_text_vec, ocr_placeholder, asr_placeholder)

            elapsed = time.time() - t0
            log.info(f"✅ Text Query: '{query[:15]}...' -> 512d | Time: {elapsed:.3f}s")

            return video_embed_pb2.TextEmbedResponse(
                embedding=embedding.tolist(),
                status="ok",
                message=f"Processed in {elapsed:.3f}s"
            )

        except Exception as e:
            log.exception("Text embedding processing failed")
            return video_embed_pb2.TextEmbedResponse(
                embedding=[], status="error", message=str(e)
            )