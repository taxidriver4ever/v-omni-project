# video_embed_service

多模态视频 Embedding 服务 + 用户长短期兴趣建模服务，基于 gRPC 对外暴露。

## 目录结构

```
video_embed_service/
├── config.py                       # 全局配置（设备、超参、路径）
├── main.py                         # 服务启动入口
├── proto/
│   └── video_embed.proto           # Proto 定义（两个服务）
├── models/
│   ├── fusion_mlp.py               # FusionMLP：1920→512 融合网络
│   ├── kmeans_processor.py         # KMeansProcessor：历史行为聚类
│   ├── long_short_model.py         # LongShortUserModel：双路注意力
│   └── registry.py                 # 所有模型的懒加载单例
├── encoders/
│   ├── clip_encoder.py             # PersonSuppressedCLIP（YOLO+CLIP）
│   ├── ocr_encoder.py              # EasyOCR → SentenceTransformer
│   ├── asr_encoder.py              # faster-whisper → SentenceTransformer
│   └── text_encoder.py             # Title / CLIP 向量入口
├── pipeline/
│   ├── video_utils.py              # 下载、抽帧、提音频
│   └── fusion.py                   # concat → FusionMLP → 512 维
└── servicer/
    ├── video_embed_servicer.py     # VideoEmbedService 实现
    └── user_model_servicer.py      # UserModelService 实现（新增）
```

## 暴露的 RPC

### VideoEmbedService
| RPC | 输入 | 输出 |
|-----|------|------|
| `GetVideoEmbedding` | `video_url`, `title` | `embedding[512]` |

### UserModelService（新增）
| RPC | 输入 | 输出 |
|-----|------|------|
| `GetLongTermInterest` | N 条历史行为（embedding+biz） | K 个兴趣质心 |
| `GetUserInterestVector` | query + 短期序列 + 长期质心 | 融合用户向量(512) + 注意力权重 |

## 快速开始

### 1. 安装依赖
```bash
pip install grpcio grpcio-tools torch torchvision
pip install git+https://github.com/openai/CLIP.git
pip install sentence-transformers faster-whisper easyocr
pip install ultralytics scikit-learn aiohttp aiofiles Pillow numpy opencv-python
```

### 2. 生成 gRPC 代码
```bash
python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. proto/video_embed.proto
```

### 3. 启动服务
```bash
python main.py

# 或通过环境变量配置
GRPC_PORT=50051 GRPC_MAX_WORKERS=8 MLP_WEIGHTS_PATH=weights/mlp_fusion.pt python main.py
```

## 调用示例（Python）

### GetVideoEmbedding
```python
import grpc
import video_embed_pb2, video_embed_pb2_grpc

channel = grpc.insecure_channel("localhost:50051")
stub = video_embed_pb2_grpc.VideoEmbedServiceStub(channel)
resp = stub.GetVideoEmbedding(
    video_embed_pb2.VideoEmbedRequest(
        video_url="https://example.com/video.mp4",
        title="示例视频"
    )
)
print(resp.status, len(resp.embedding))  # ok 512
```

### GetLongTermInterest + GetUserInterestVector
```python
import grpc, numpy as np
import video_embed_pb2 as pb2
import video_embed_pb2_grpc as pb2_grpc

channel = grpc.insecure_channel("localhost:50051")
user_stub = pb2_grpc.UserModelServiceStub(channel)

# 准备历史行为（假设已有 20 条 512 维视频 embedding）
history = [
    pb2.HistoryItem(
        embedding=np.random.randn(512).tolist(),
        biz_labels=[0.8, 0.1, 0.05, 0.05]
    )
    for _ in range(20)
]

# Step 1: 获取长期兴趣质心
lt_resp = user_stub.GetLongTermInterest(
    pb2.LongTermInterestRequest(history=history)
)
print(f"长期质心数: {len(lt_resp.centroids)}")  # 8

# Step 2: 获取融合用户兴趣向量
query_emb = np.random.randn(512).tolist()
short_items = [
    pb2.ShortTermItem(
        embedding=np.random.randn(512).tolist(),
        biz_labels=[0.7, 0.2, 0.05, 0.05]
    )
    for _ in range(10)
]
ui_resp = user_stub.GetUserInterestVector(
    pb2.UserInterestRequest(
        query_embedding=query_emb,
        short_term=short_items,
        long_term=lt_resp.centroids,
    )
)
print(f"用户向量维度: {len(ui_resp.user_vector)}")  # 512
```

## 架构说明

```
视频请求
  ↓
download_video → extract_frames / extract_audio
  ↓                    ↓              ↓
CLIP(768)           OCR(384)       ASR(384)    Title(384)
  └──────────────────────────────────────────────┘
                    concat(1920)
                        ↓
                    FusionMLP
                        ↓
                   embedding(512)  ←── VideoEmbedService

用户请求
  ↓
历史 embedding(N×512) + biz_labels(N×4)
  ↓
KMeansProcessor (K=8)
  ↓
长期质心 lk(8×512) + lv(8×516)   短期序列 sk(24×512) + sv(24×516)
  └──────────────────────────────────────────────┘
              LongShortUserModel (0.7短 + 0.3长)
                        ↓
                  用户兴趣向量(512)  ←── UserModelService
```
