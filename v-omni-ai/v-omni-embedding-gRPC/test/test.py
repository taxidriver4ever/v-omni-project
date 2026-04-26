import grpc
import torch
import numpy as np
# 导入自动生成的代码
import vomni_pb2
import vomni_pb2_grpc


def run_test():
    # 1. 建立与 Server 的连接 (确保此时 grpc_server.py 正在运行)
    channel = grpc.insecure_channel('localhost:50051')
    stub = vomni_pb2_grpc.RecommenderStub(channel)

    # 2. 构造模拟数据
    embed_dim = 512

    # 构造 q_5_videos (5个 512维向量)
    q5_raw = np.random.randn(5, embed_dim).astype(np.float32)
    q5_vectors = [vomni_pb2.Vector(values=v.tolist()) for v in q5_raw]

    # 构造 short_k (64个 512维向量)
    sk_raw = np.random.randn(64, embed_dim).astype(np.float32)
    sk_vectors = [vomni_pb2.Vector(values=v.tolist()) for v in sk_raw]

    # 构造 long_k (64个 512维向量)
    lk_raw = np.random.randn(64, embed_dim).astype(np.float32)
    lk_vectors = [vomni_pb2.Vector(values=v.tolist()) for v in lk_raw]

    # 构造 short_weights (64个 权重值)
    weights_raw = np.random.uniform(1.0, 2.0, 64).astype(np.float32).tolist()

    # 3. 组装最终请求体
    request = vomni_pb2.RecommendRequest(
        q_5_videos=q5_vectors,
        short_k=sk_vectors,
        long_k=lk_vectors,
        short_weights=weights_raw
    )

    # 4. 发起调用并计时
    import time
    start = time.time()
    try:
        response = stub.GetUserEmbedding(request)
        end = time.time()

        if response.status == "success":
            print(f"✅ 测试成功！")
            print(f"响应耗时: {(end - start) * 1000:.2f}ms")
            print(f"返回向量长度: {len(response.user_embedding)}")
            print(f"向量前5维预览: {response.user_embedding[:5]}")
        else:
            print(f"❌ Server 返回错误状态: {response.status}")

    except grpc.RpcError as e:
        print(f"❌ gRPC 调用失败: {e.code()}, {e.details()}")


if __name__ == '__main__':
    run_test()

# import open_clip
# print(open_clip.list_pretrained()[:10])