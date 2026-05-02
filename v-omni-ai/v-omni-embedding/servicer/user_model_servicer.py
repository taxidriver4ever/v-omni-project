"""
UserModelServicer：实现 UserModelService 的两个 RPC。

  GetLongTermInterest     — KMeansProcessor 聚类历史行为 → 兴趣质心
  GetUserInterestVector   — LongShortUserModel 融合长短期兴趣 → 用户向量

修复：
  1. val 拼接从 vecs[i] + bizs[i]（列表相加 = 延长列表，维度超出）
     改为 np.concatenate([vecs[i], bizs[i]])（正确的 numpy concat）
  2. 构造 short_mask 传入模型，padding 零向量的 attention 权重归零
"""
import logging
import time

import numpy as np
import torch
import grpc

import video_embed_pb2
import video_embed_pb2_grpc

from config import DEVICE, USER_MODEL_BIZ_DIM, USER_MODEL_DIM, USER_MODEL_MAX_SHORT
from models.registry import registry

log = logging.getLogger(__name__)


class UserModelServicer(video_embed_pb2_grpc.UserModelServiceServicer):

    # ── GetLongTermInterest ───────────────────────────────────────────────────
    def GetLongTermInterest(self, request, context):
        """
        输入: history（N 条 HistoryItem，每条含 512 维 embedding + 4 维 biz_labels）
        输出: K 个 InterestCentroid（兴趣质心 + 业务质心）
        """
        t0 = time.time()
        log.info(f"GetLongTermInterest: {len(request.history)} 条历史行为")

        try:
            if not request.history:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details("history 不能为空")
                return video_embed_pb2.LongTermInterestResponse(
                    status="error", message="history 不能为空"
                )

            # 反序列化
            history_vecs = np.array(
                [list(item.embedding) for item in request.history], dtype=np.float32
            )  # (N, 512)
            biz_labels = np.array(
                [list(item.biz_labels) for item in request.history], dtype=np.float32
            )  # (N, 4)

            # KMeans 聚类
            kmeans = registry.kmeans()
            centroids, biz_centroids = kmeans.process_user_history(history_vecs, biz_labels)

            # 序列化为 proto
            proto_centroids = [
                video_embed_pb2.InterestCentroid(
                    embedding=centroids[i].tolist(),
                    biz_labels=biz_centroids[i].tolist(),
                )
                for i in range(len(centroids))
            ]

            elapsed = time.time() - t0
            log.info(f"✅ GetLongTermInterest 完成，{len(proto_centroids)} 个质心，耗时 {elapsed:.2f}s")
            return video_embed_pb2.LongTermInterestResponse(
                centroids=proto_centroids,
                status="ok",
                message=f"耗时 {elapsed:.2f}s | 质心数 {len(proto_centroids)}",
            )

        except Exception as e:
            log.exception("GetLongTermInterest 异常")
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return video_embed_pb2.LongTermInterestResponse(
                status="error", message=str(e)
            )

    # ── GetUserInterestVector ─────────────────────────────────────────────────
    def GetUserInterestVector(self, request, context):
        t0 = time.time()
        log.info(
            f"GetUserInterestVector: short={len(request.short_term)} "
            f"long={len(request.long_term)}"
        )

        try:
            # ── 反序列化输入 (保持不变) ──────────────────────────────────────────────────
            q = np.array(list(request.query_embedding), dtype=np.float32)

            def _parse_items(items, max_len):
                vecs = [np.array(list(item.embedding), dtype=np.float32) for item in items]
                bizs = [np.array(list(item.biz_labels), dtype=np.float32) for item in items]
                vecs, bizs = vecs[:max_len], bizs[:max_len]
                L = len(vecs)
                key = np.zeros((max_len, USER_MODEL_DIM), dtype=np.float32)
                val = np.zeros((max_len, USER_MODEL_DIM + USER_MODEL_BIZ_DIM), dtype=np.float32)
                mask = np.zeros(max_len, dtype=bool)
                for i in range(L):
                    key[i] = vecs[i]
                    val[i] = np.concatenate([vecs[i], bizs[i]])
                    mask[i] = True
                return key, val, mask, L

            sk, sv, short_mask, short_len = _parse_items(request.short_term, USER_MODEL_MAX_SHORT)

            long_vecs = [np.array(list(c.embedding), dtype=np.float32) for c in request.long_term]
            long_bizs = [np.array(list(c.biz_labels), dtype=np.float32) for c in request.long_term]
            K = len(long_vecs)
            lk = np.stack(long_vecs, axis=0) if K > 0 else np.zeros((1, USER_MODEL_DIM), dtype=np.float32)
            lv = np.stack([np.concatenate([long_vecs[i], long_bizs[i]]) for i in range(K)],
                          axis=0) if K > 0 else np.zeros((1, USER_MODEL_DIM + USER_MODEL_BIZ_DIM), dtype=np.float32)

            # ── 转 Tensor (保持不变) ──────────────────────────────────────────────────
            def _to_tensor(arr):
                return torch.from_numpy(arr).unsqueeze(0).to(DEVICE)

            q_t, sk_t, sv_t, lk_t, lv_t = _to_tensor(q), _to_tensor(sk), _to_tensor(sv), _to_tensor(lk), _to_tensor(lv)
            mask_t = torch.from_numpy(short_mask).unsqueeze(0).to(DEVICE)

            # ── 模型推理 ──────────────────────────────────────────────────────
            model = registry.long_short()
            with torch.no_grad():
                # 这里接收模型返回的 fused (即本次实时计算的兴趣向量)
                fused, s_w, l_w = model(q_t, sk_t, sv_t, lk_t, lv_t, short_mask=mask_t)

            # ── 序列化输出 (✅ 已去掉 current_interest 逻辑) ─────────────────────────
            # 以前是: out_vec = model.current_interest if ... else fused
            # 现在直接使用本次计算的 fused，彻底断绝“记忆”
            out_vec = fused
            user_vec = out_vec.squeeze(0).cpu().numpy().tolist()  # [512]

            # 权重处理 (保持不变)
            s_w_full = s_w.squeeze().cpu().numpy()
            short_w = s_w_full[:short_len].tolist() if short_len > 1 else [float(s_w_full)]
            long_w = l_w.squeeze().cpu().numpy().tolist() if K > 1 else [float(l_w.squeeze().cpu().numpy())]

            elapsed = time.time() - t0
            log.info(f"✅ GetUserInterestVector 完成，耗时 {elapsed:.2f}s")

            return video_embed_pb2.UserInterestResponse(
                user_vector=user_vec,
                short_weights=short_w,
                long_weights=long_w,
                status="ok",
                message=f"Success | short={short_len} | long={K}",
            )

        except Exception as e:
            log.exception("GetUserInterestVector 异常")
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return video_embed_pb2.UserInterestResponse(status="error", message=str(e))