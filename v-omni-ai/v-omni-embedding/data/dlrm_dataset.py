import torch
from torch.utils.data import Dataset
import numpy as np


class DLRMDataset(Dataset):
    def __init__(self, num_samples, vocab_dims, dense_dim=2, video_dim=512):
        """
        Args:
            num_samples: 样本总数
            vocab_dims: 离散特征词表大小字典 {'user': 1000, 'item': 5000, ...}
            dense_dim: 连续特征维度 (Age + Popularity = 2)
            video_dim: 视频向量维度 (512)
        """
        self.num_samples = num_samples
        self.vocab_dims = vocab_dims

        # 1. 模拟连续型特征 (Age, Popularity)
        self.dense_data = np.random.rand(num_samples, dense_dim).astype(np.float32)

        # 2. 模拟离散型特征 (存储为字典，方便后续按 key 读取)
        self.sparse_data = {}
        for feature_name, vocab_size in vocab_dims.items():
            self.sparse_data[feature_name] = np.random.randint(0, vocab_size, size=num_samples)

        # 3. 模拟视频向量 (Video Embedding)
        self.video_data = np.random.randn(num_samples, video_dim).astype(np.float32)

        # 4. 模拟二分类标签 (0/1)
        self.labels = np.random.randint(0, 2, size=(num_samples, 1)).astype(np.float32)

    def __len__(self):
        return self.num_samples

    def __getitem__(self, idx):
        # 构造当前样本的离散特征字典
        sparse_sample = {
            feature_name: torch.tensor(self.sparse_data[feature_name][idx], dtype=torch.long)
            for feature_name in self.vocab_dims.keys()
        }

        # 返回顺序：离散特征字典, 连续特征, 视频向量, 标签
        return (
            sparse_sample,
            torch.tensor(self.dense_data[idx], dtype=torch.float32),
            torch.tensor(self.video_data[idx], dtype=torch.float32),
            torch.tensor(self.labels[idx], dtype=torch.float32)
        )


# --- 简单验证代码 ---
if __name__ == "__main__":
    vocabs = {'user': 100, 'item': 500, 'cluster': 10, 'sex': 2, 'country': 5, 'province': 10, 'city': 20}
    dataset = VOmniDataset(64, vocabs)

    # 测试取出一个样本
    s_batch, d_batch, v_batch, l_batch = dataset[0]

    print(f"离散特征 Keys: {list(s_batch.keys())}")
    print(f"连续特征形状: {d_batch.shape}")  # [2]
    print(f"视频向量形状: {v_batch.shape}")  # [512]
    print(f"标签形状: {l_batch.shape}")  # [1]