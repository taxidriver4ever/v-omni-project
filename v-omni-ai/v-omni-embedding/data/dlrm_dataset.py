import torch
from torch.utils.data import Dataset
import numpy as np


class InteractionDataset(Dataset):

    def __init__(self, file_path):
        data = np.load(file_path, allow_pickle=True)

        self.user_emb = data[:, 0]
        self.item_emb = data[:, 1]
        self.label = data[:, 2]

    def __len__(self):
        return len(self.label)

    def __getitem__(self, idx):

        return (
            torch.tensor(self.user_emb[idx], dtype=torch.float32),
            torch.tensor(self.item_emb[idx], dtype=torch.float32),
            torch.tensor(self.label[idx], dtype=torch.float32)
        )