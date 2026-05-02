"""
全局配置 —— 所有模块从这里 import，避免散落的 magic number。
"""
import os
import torch

# ── 设备 ──────────────────────────────────────────────────────────────────────
DEVICE: str = "cuda" if torch.cuda.is_available() else "cpu"

# ── 视频抽帧 ──────────────────────────────────────────────────────────────────
FRAME_INTERVAL_SEC: int = 2     # 每 N 秒抽一帧
MAX_FRAMES: int = 48            # 最大帧数，防止超长视频 OOM
MIN_FRAMES: int = 4

# ── 模型推理 ──────────────────────────────────────────────────────────────────
CLIP_BATCH: int = 16            # CLIP 推理批大小
OCR_MAX_FRAMES: int = 12        # OCR 最多处理帧数（稀疏采样）
WHISPER_MODEL: str = "base" # tiny / base / small / medium / large-v3
TEXT_MODEL: str = "paraphrase-multilingual-MiniLM-L12-v2"  # 384 维

# ── 权重路径 ──────────────────────────────────────────────────────────────────
MLP_WEIGHTS: str = os.getenv("MLP_WEIGHTS_PATH", "weights/mlp_fusion.pt")

# ── gRPC 服务器 ───────────────────────────────────────────────────────────────
GRPC_PORT: int = int(os.getenv("GRPC_PORT", "50051"))
GRPC_MAX_WORKERS: int = int(os.getenv("GRPC_MAX_WORKERS", "4"))
GRPC_MAX_MSG_MB: int = 64       # 收发消息最大字节（MB）

# ── KMeans 用户兴趣聚类 ───────────────────────────────────────────────────────
KMEANS_N_CLUSTERS: int = 8      # 长期兴趣簇数（Long-term K）
KMEANS_BIZ_DIM: int = 4         # 业务标签维度

# ── LongShort 用户模型 ────────────────────────────────────────────────────────
USER_MODEL_DIM: int = 512       # 特征维度，与视频 embedding 对齐
USER_MODEL_BIZ_DIM: int = 4
USER_MODEL_MAX_SHORT: int = 24  # 短期序列最大长度
