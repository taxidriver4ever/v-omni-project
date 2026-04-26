import grpc
from concurrent import futures
import torch
import vomni_pb2
import vomni_pb2_grpc
from model import VOmniRecommender
import open_clip
from PIL import Image
import io
import logging
import os
from dotenv import load_dotenv

# 加载 .env 文件中的变量到系统环境变量中
load_dotenv()

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class RecommenderServicer(vomni_pb2_grpc.RecommenderServicer):
    def __init__(self):
        if not torch.cuda.is_available():
            logger.error("CUDA is not available! GPU acceleration failed.")

        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        logger.info(f"Using device: {self.device}")

        # 1. 加载 V-Omni 推荐模型
        self.model = VOmniRecommender(embed_dim=512).to(self.device)
        self.model.eval()

        # 2. 加载 CLIP 模型
        logger.info("Loading CLIP model...")
        self.clip_model, _, self.clip_preprocess = open_clip.create_model_and_transforms(
            'ViT-B-32',
            pretrained='laion2b_s34b_b79k',
            precision="fp16"  # <--- 4060 开启这个，显存占用减半，推理速度翻倍
        )
        self.clip_model = self.clip_model.to(self.device)
        self.clip_tokenizer = open_clip.get_tokenizer('ViT-B-32')
        logger.info("All models loaded successfully.")

    def GetImageEmbeddings(self, request, context):
        try:
            if not request.image_data:
                return vomni_pb2.MultiVectorResponse()

            images = []
            for img_bytes in request.image_data:
                # 内存读取图片，避免磁盘IO
                img = Image.open(io.BytesIO(img_bytes)).convert('RGB')
                images.append(self.clip_preprocess(img))

            image_input = torch.stack(images).to(self.device).half()

            with torch.no_grad():
                image_features = self.clip_model.encode_image(image_input)
                # 归一化
                image_features /= image_features.norm(dim=-1, keepdim=True)

                # --- 均值池化逻辑 ---
                if request.do_pooling:
                    # 将所有图片向量取平均 [N, 512] -> [1, 512]
                    pooled_feature = torch.mean(image_features, dim=0, keepdim=True)
                    # 池化后再次归一化，确保模长为 1
                    image_features = pooled_feature / pooled_feature.norm(dim=-1, keepdim=True)

            res = vomni_pb2.MultiVectorResponse()
            for feat in image_features:
                res.vectors.add(values=feat.cpu().tolist())
            return res

        except Exception as e:
            logger.error(f"Image Error: {e}")
            context.set_details(str(e))
            return vomni_pb2.MultiVectorResponse()

    def GetTextEmbedding(self, request, context):
        try:
            text_input = self.clip_tokenizer([request.text]).to(self.device)
            with torch.no_grad():
                text_features = self.clip_model.encode_text(text_input)
                text_features /= text_features.norm(dim=-1, keepdim=True)
            return vomni_pb2.VectorResponse(values=text_features.squeeze(0).cpu().tolist())
        except Exception as e:
            logger.error(f"Text Error: {e}")
            return vomni_pb2.VectorResponse()

    def GetUserEmbedding(self, request, context):
        try:
            # 数据解包
            q_list = [[v.values for v in request.q_5_videos]]
            sk_list = [[v.values for v in request.short_k]]
            lk_list = [[v.values for v in request.long_k]]

            t_q = torch.tensor(q_list, dtype=torch.float32).to(self.device)
            t_sk = torch.tensor(sk_list, dtype=torch.float32).to(self.device)
            t_lk = torch.tensor(lk_list, dtype=torch.float32).to(self.device)
            t_weights = torch.tensor([request.short_weights], dtype=torch.float32).to(self.device)

            with torch.no_grad():
                output_tensor = self.model(t_q, t_sk, t_lk, t_weights)

            return vomni_pb2.RecommendResponse(
                status="success",
                user_embedding=output_tensor.squeeze(0).cpu().tolist()
            )
        except Exception as e:
            logger.error(f"User Embedding Error: {e}")
            return vomni_pb2.RecommendResponse(status="error")

# 线程池大小可根据 CPU 核心数调整
MAX_MESSAGE_LENGTH = 64 * 1024 * 1024
def serve():

    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=10),
        options=[
            ('grpc.max_send_message_length', MAX_MESSAGE_LENGTH),
            ('grpc.max_receive_message_length', MAX_MESSAGE_LENGTH),
        ]
    )
    vomni_pb2_grpc.add_RecommenderServicer_to_server(RecommenderServicer(), server)
    server.add_insecure_port('[::]:50051')
    logger.info("V-Omni System Hub is online on port 50051")
    server.start()
    server.wait_for_termination()

if __name__ == '__main__':
    serve()