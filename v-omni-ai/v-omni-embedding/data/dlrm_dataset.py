import torch
from torch.utils.data import Dataset
import numpy as np

class DLRMDataset(Dataset):
    def __init__(self, num_samples, dense_dim, sparse_vocab_sizes):
        self.num_samples = num_samples
        # 模拟连续型特征
        self.dense_data = np.random.rand(num_samples, dense_dim).astype(np.float32)
        # 模拟离散型特征索引
        self.sparse_data = np.zeros((num_samples, len(sparse_vocab_sizes)), dtype=np.int64)
        for i, vocab_size in enumerate(sparse_vocab_sizes):
            self.sparse_data[:, i] = np.random.randint(0, vocab_size, size=num_samples)
        # 模拟二分类标签 (0/1)
        self.labels = np.random.randint(0, 2, size=(num_samples, 1)).astype(np.float32)

    def __len__(self):
        return self.num_samples

    def __getitem__(self, idx):
        return (
            torch.tensor(self.dense_data[idx]),
            torch.tensor(self.sparse_data[idx]),
            torch.tensor(self.labels[idx])
        )