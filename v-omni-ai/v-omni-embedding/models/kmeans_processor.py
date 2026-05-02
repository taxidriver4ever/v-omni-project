"""
KMeansProcessor：对用户历史行为向量做 K-Means 聚类，
生成长期兴趣质心（Long-term K）及对应业务质心（Long-term V）。

输入:
  history_vectors : (N, 512) — 用户历史行为的视频 embedding
  biz_labels      : (N, 4)  — 每条行为对应的业务标签
输出:
  centroids     : (K, 512) — 兴趣质心（Long-term K）
  biz_centroids : (K, 4)  — 业务质心（Long-term V）
"""
import numpy as np
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score

from config import KMEANS_N_CLUSTERS, KMEANS_BIZ_DIM


class KMeansProcessor:
    def __init__(
        self,
        n_clusters: int = KMEANS_N_CLUSTERS,
        biz_dim: int = KMEANS_BIZ_DIM,
    ):
        self.n_clusters = n_clusters
        self.biz_dim = biz_dim
        # k-means++ 初始化保证收敛速度与稳定性
        self.model = KMeans(
            n_clusters=n_clusters,
            init="k-means++",
            n_init=10,
            random_state=42,
        )

    def process_user_history(
        self,
        history_vectors: np.ndarray,  # (N, 512)
        biz_labels: np.ndarray,        # (N, biz_dim)
    ) -> tuple[np.ndarray, np.ndarray]:
        """
        对历史行为聚类并汇总业务标签均值。

        行为数不足以聚类时（N < n_clusters），原样返回，
        调用方需自行处理变长序列。
        """
        if len(history_vectors) < self.n_clusters:
            return history_vectors, biz_labels

        # 聚类，得到每条行为所属簇索引 (N,)
        labels = self.model.fit_predict(history_vectors)

        # 兴趣质心 (K, 512)
        centroids: np.ndarray = self.model.cluster_centers_

        # 业务质心：每簇内 biz_labels 的均值 (K, biz_dim)
        biz_centroids = np.zeros((self.n_clusters, self.biz_dim), dtype=np.float32)
        for i in range(self.n_clusters):
            idx = np.where(labels == i)[0]
            if len(idx) > 0:
                biz_centroids[i] = np.mean(biz_labels[idx], axis=0)

        return centroids.astype(np.float32), biz_centroids

    def evaluate_k(self, data: np.ndarray) -> float:
        """
        用轮廓系数评估当前 K=n_clusters 是否合理。
        需在 fit_predict 之后调用（model.labels_ 已存在）。
        """
        return float(silhouette_score(data, self.model.labels_))
