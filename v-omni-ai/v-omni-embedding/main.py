"""
main.py — gRPC 服务启动入口

启动两个服务：
  VideoEmbedService  — 视频 → 512 维 embedding
  UserModelService   — 用户长短期兴趣建模

使用方法:
  python main.py

环境变量:
  GRPC_PORT          监听端口（默认 50051）
  GRPC_MAX_WORKERS   线程池大小（默认 4）
  MLP_WEIGHTS_PATH   FusionMLP 权重路径（默认 weights/mlp_fusion.pt）

生成 gRPC 代码（首次或 proto 变更后执行）:
  python -m grpc_tools.protoc -I proto \
      --python_out=. --grpc_python_out=. proto/video_embed.proto
"""
import logging
from concurrent import futures

import grpc

# 确保这里的导入名称和你生成的 pb2_grpc 文件名一致
import video_embed_pb2_grpc

from config import GRPC_PORT, GRPC_MAX_WORKERS, GRPC_MAX_MSG_MB
from models.registry import registry

# 导入三个服务的实现类
from servicer.video_embed_servicer import VideoEmbedServicer
from servicer.user_model_servicer import UserModelServicer
from servicer.text_embed_servicer import TextEmbedServicer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
log = logging.getLogger(__name__)


def serve(port: int = GRPC_PORT, max_workers: int = GRPC_MAX_WORKERS) -> None:
    """
    启动 gRPC 整合服务：涵盖视频向量化、搜索词向量化、用户建模
    """

    # 1. 预热模型：加载 CLIP(Vision/Text)、SBERT(Title)、MLP 权重
    # 提前加载可以避免 Java 端第一次请求时卡顿几十秒
    log.info("正在预热模型...")
    registry.warmup()

    # 2. 配置消息体大小限制
    max_msg = GRPC_MAX_MSG_MB * 1024 * 1024
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=max_workers),
        options=[
            ("grpc.max_receive_message_length", max_msg),
            ("grpc.max_send_message_length", max_msg),
        ],
    )

    # 3. 注册服务 (Service Registration)

    # [服务 A] 视频 Embedding (入库用)
    video_embed_pb2_grpc.add_VideoEmbedServiceServicer_to_server(
        VideoEmbedServicer(), server
    )

    # [服务 B] 用户兴趣建模 (推荐排序用)
    video_embed_pb2_grpc.add_UserModelServiceServicer_to_server(
        UserModelServicer(), server
    )

    # [服务 C] 文本 Embedding (搜索引擎用)
    # 这个就是你刚刚在 proto 里新增的接口
    video_embed_pb2_grpc.add_TextEmbedServiceServicer_to_server(
        TextEmbedServicer(), server
    )

    # 4. 启动端口监听
    server.add_insecure_port(f"[::]:{port}")
    server.start()

    log.info("=" * 50)
    log.info(f"🚀 V-Omni AI 整合服务已启动 | 端口: {port}")
    log.info(f"▸ VideoEmbedService: 视频 -> 512d (入库)")
    log.info(f"▸ TextEmbedService:  搜索词 -> 512d (搜索)")
    log.info(f"▸ UserModelService:  兴趣融合 (推荐)")
    log.info("=" * 50)

    server.wait_for_termination()


if __name__ == "__main__":
    try:
        serve()
    except KeyboardInterrupt:
        log.info("正在关闭服务...")