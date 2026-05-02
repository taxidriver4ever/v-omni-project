import torch
from torch.utils.data import DataLoader

from models.transformer_ranker import TransformerRanker
from data.transformer_dataset import SeqDataset


# =========================
# 假数据（你后面换ES）
# =========================
import numpy as np


def fake_video():
    return np.random.randn(512)


def build_fake_samples():

    samples = []

    seq = [fake_video() for _ in range(20)]

    window = 5

    for i in range(len(seq) - window - 1):

        history = seq[i:i+window]
        target = seq[i+window]
        label = 1

        samples.append((history, target, label))

        # negative sample
        neg = np.random.randn(512)
        samples.append((history, neg, 0))

    return samples


# =========================
# train
# =========================
def train():

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    # 1. 数据
    samples = build_fake_samples()

    dataset = SeqDataset(samples)

    dataloader = DataLoader(
        dataset,
        batch_size=32,
        shuffle=True
    )

    # 2. 模型
    model = TransformerRanker().to(device)

    optimizer = torch.optim.Adam(model.parameters(), lr=1e-4)

    loss_fn = torch.nn.BCELoss()

    # 3. 训练循环
    for epoch in range(5):

        total_loss = 0

        for history, target, label in dataloader:

            history = history.to(device)   # [B, T, 512]
            target = target.to(device)     # [B, 512]
            label = label.to(device).float()

            pred = model(history, target).squeeze(-1)

            loss = loss_fn(pred, label)

            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            total_loss += loss.item()

        print(f"epoch {epoch} loss: {total_loss:.4f}")


if __name__ == "__main__":
    train()