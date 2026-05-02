import torch
from torch.utils.data import Dataset


class SeqDataset(Dataset):

    def __init__(self, samples):

        self.samples = samples

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):

        history, target, label = self.samples[idx]

        return (
            torch.tensor(history, dtype=torch.float32),  # [T, 512]
            torch.tensor(target, dtype=torch.float32),   # [512]
            torch.tensor(label, dtype=torch.float32)
        )