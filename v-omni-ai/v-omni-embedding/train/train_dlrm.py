import torch
import torch.nn as nn
from torch.utils.data import DataLoader
# 确保导入的是你最新的模型类名
from models.dlrm import VOmni_DLRM
from data.dlrm_dataset import DLRMDataset


def main():
    # --- 1. 参数配置 ---
    VOCAB_DIMS = {
        'user': 1000, 'item': 5000, 'cluster': 8,
        'sex': 3, 'country': 10, 'province': 35, 'city': 300
    }
    DENSE_DIM = 2  # Age + Popularity
    VIDEO_DIM = 512
    EMB_DIM = 16
    BATCH_SIZE = 128
    EPOCHS = 5
    LEARNING_RATE = 1e-3

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using device: {device}")

    # --- 2. 实例化数据集与加载器 ---
    # 注意：这里参数名要和 VOmniDataset 的 __init__ 定义一致 (num_samples)
    train_dataset = DLRMDataset(
        num_samples=10000,
        vocab_dims=VOCAB_DIMS,
        dense_dim=DENSE_DIM,
        video_dim=VIDEO_DIM
    )

    # DataLoader 会自动把 dict 里的 tensor 拼成 batch dict
    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True)

    # --- 3. 初始化模型 ---
    model = VOmni_DLRM(
        vocab_dims=VOCAB_DIMS,
        dense_dim=DENSE_DIM,
        video_dim=VIDEO_DIM,
        embed_dim=EMB_DIM
    ).to(device)

    # --- 4. 定义优化器与损失函数 ---
    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE)
    criterion = nn.BCELoss()

    # --- 5. 训练循环 ---
    print(f"开始训练 VOmni_DLRM_v2，总批次数: {len(train_loader)}")

    for epoch in range(EPOCHS):
        model.train()
        epoch_loss = 0.0

        for batch_idx, (sparse_batch, dense_batch, video_batch, label_batch) in enumerate(train_loader):
            # A. 数据搬运到 GPU/CPU
            # 离散特征是字典，需要遍历搬运
            sparse_batch = {k: v.to(device) for k, v in sparse_batch.items()}
            dense_batch = dense_batch.to(device)
            video_batch = video_batch.to(device)
            label_batch = label_batch.to(device)

            # B. 前向传播
            outputs = model(sparse_batch, dense_batch, video_batch)
            loss = criterion(outputs, label_batch)

            # C. 反向传播
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            epoch_loss += loss.item()

            if (batch_idx + 1) % 20 == 0:
                print(
                    f"Epoch [{epoch + 1}/{EPOCHS}], Step [{batch_idx + 1}/{len(train_loader)}], Loss: {loss.item():.4f}")

        avg_loss = epoch_loss / len(train_loader)
        print(f"==> Epoch {epoch + 1} 完成, 平均 Loss: {avg_loss:.4f}")

    # --- 6. 保存模型 ---
    torch.save(model.state_dict(), "vomni_dlrm_v2.pth")
    print("模型已保存至 vomni_dlrm_v2.pth")


if __name__ == "__main__":
    main()