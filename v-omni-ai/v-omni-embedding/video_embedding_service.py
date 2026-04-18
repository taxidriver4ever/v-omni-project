import os
import io
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
from PIL import Image
import torch
import torchvision.transforms as transforms
from torchvision.models import resnet50, ResNet50_Weights
from minio import Minio

app = FastAPI()

# ==================== 配置 ====================
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "admin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "password123")
MINIO_SECURE = os.getenv("MINIO_SECURE", "false").lower() == "true"

# 初始化 PyTorch 模型（ResNet50，输出 2048 维特征）
weights = ResNet50_Weights.IMAGENET1K_V2
model = resnet50(weights=weights)
model.eval()
# 移除最后的分类层（fc），保留全局平均池化后的特征 (2048,)
feature_extractor = torch.nn.Sequential(*list(model.children())[:-1])

# 图像预处理管道（与 PyTorch 官方一致）
preprocess = transforms.Compose([
    transforms.Resize(256),
    transforms.CenterCrop(224),
    transforms.ToTensor(),
    transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
])

# 初始化 MinIO 客户端
minio_client = Minio(
    MINIO_ENDPOINT,
    access_key=MINIO_ACCESS_KEY,
    secret_key=MINIO_SECRET_KEY,
    secure=MINIO_SECURE
)

class FrameTask(BaseModel):
    video_id: str
    frame_objects: List[str]

def extract_feature_from_bytes(image_bytes):
    """从图片字节流提取 2048 维特征向量"""
    img = Image.open(io.BytesIO(image_bytes)).convert('RGB')
    input_tensor = preprocess(img).unsqueeze(0)  # shape: [1, 3, 224, 224]
    with torch.no_grad():
        features = feature_extractor(input_tensor).squeeze()  # shape: [2048]
    return features.numpy().astype(np.float32)

@app.post("/embedding/mean_pool")
async def compute_mean_pooling(task: FrameTask):
    """接收 frame_objects 列表，返回均值池化后的特征向量"""
    vectors = []
    for obj_name in task.frame_objects:
        try:
            response = minio_client.get_object("tmp-extraction-image", obj_name)
            image_bytes = response.read()
            response.close()
            response.release_conn()
            vec = extract_feature_from_bytes(image_bytes)
            vectors.append(vec)
        except Exception as e:
            print(f"处理图片 {obj_name} 失败: {e}")
            continue

    if not vectors:
        raise HTTPException(status_code=400, detail="No valid frames")

    full_mean_vector = np.mean(vectors, axis=0)

    # 2. 直接截取前 1024 维
    # [0:1024] 表示取下标 0 到 1023 的元素
    truncated_vector = full_mean_vector[:1024].astype(np.float32).tolist()

    return {
        "video_id": task.video_id,
        "vector": truncated_vector,
        "vector_length": len(truncated_vector)  # 现在这里会返回 1024
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=18001)