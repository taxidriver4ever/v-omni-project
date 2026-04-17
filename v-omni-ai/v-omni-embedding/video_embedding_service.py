import os
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
import onnxruntime as ort
import cv2
from minio import Minio

app = FastAPI()

# ==================== 配置 ====================
MODEL_PATH = os.getenv("ONNX_MODEL_PATH",
                       r"D:\private_project\v-omni-project\v-omni-ai\v-omni-embedding\model\resnet50-v1-12.onnx")
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "admin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "password123")
MINIO_SECURE = os.getenv("MINIO_SECURE", "false").lower() == "true"

# 初始化 ONNX 模型
session = ort.InferenceSession(MODEL_PATH)
input_name = session.get_inputs()[0].name
output_name = session.get_outputs()[0].name

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


def preprocess_image(image_bytes):
    """将图片字节流转为模型输入 (1,3,224,224)，数据类型强制为 float32"""
    nparr = np.frombuffer(image_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    img = cv2.resize(img, (224, 224))
    img = img.astype(np.float32) / 255.0
    # 关键：均值与标准差也必须为 float32
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    img = (img - mean) / std
    img = np.transpose(img, (2, 0, 1))  # (H,W,C) -> (C,H,W)
    img = np.expand_dims(img, axis=0)  # (1,C,H,W)
    return img


@app.post("/embedding/mean_pool")
async def compute_mean_pooling(task: FrameTask):
    """接收 frame_objects 列表，返回均值池化后的向量"""
    vectors = []
    for obj_name in task.frame_objects:
        try:
            response = minio_client.get_object("tmp-extraction-image", obj_name)
            image_bytes = response.read()
            response.close()
            response.release_conn()

            input_tensor = preprocess_image(image_bytes)
            output = session.run([output_name], {input_name: input_tensor})[0]  # shape (1,2048)
            # 确保向量为 float32（虽然 output 通常已是 float32，但显式转换更安全）
            vectors.append(output.flatten().astype(np.float32))
        except Exception as e:
            print(f"处理图片 {obj_name} 失败: {e}")
            continue

    if not vectors:
        raise HTTPException(status_code=400, detail="No valid frames")

    # 均值池化，结果转为 float32 列表
    mean_vector = np.mean(vectors, axis=0).astype(np.float32).tolist()

    return {
        "video_id": task.video_id,
        "vector": mean_vector,
        "vector_length": len(mean_vector)
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=18001)