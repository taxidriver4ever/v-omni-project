import torch
from torch.utils.data import DataLoader
from models.dlrm import DLRM
from data.dlrm_dataset import DLRMDataset

def main():
    # 参数配置
    DENSE_DIM = 16
    SPARSE_VOCABS = [1000, 500, 200] # 示例：UserID, ItemID, CategoryID
    EMB_DIM = 64
    BATCH_SIZE = 128
    EPOCHS = 5
    LEARNING_RATE = 1e-3

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using device: {device}")

    # 1. 实例化数据集与加载器
    train_dataset = DLRMDataset(10000, DENSE_DIM, SPARSE_VOCABS)
    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True)

    # 2. 初始化模型
    model = DLRM(DENSE_DIM, SPARSE_VOCABS, EMB_DIM).to(device)

    # 3. 定义优化器与损失函数
    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE)
    criterion = torch.nn.BCELoss()

    # 4. 训练循环
    for epoch in range(EPOCHS):
        model.train()
        epoch_loss = 0
        for dense_batch, sparse_batch, label_batch in train_loader:
            dense_batch = dense_batch.to(device)
            sparse_batch = sparse_batch.to(device)
            label_batch = label_batch.to(device)

            # 前向传播
            outputs = model(dense_batch, sparse_batch)
            loss = criterion(outputs, label_batch)

            # 反向传播
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            epoch_loss += loss.item()

        print(f"Epoch {epoch+1}/{EPOCHS}, Loss: {epoch_loss/len(train_loader):.4f}")

    # 保存模型
    torch.save(model.state_dict(), "dlrm_v1.pth")
    print("Model saved to dlrm_v1.pth")

if __name__ == "__main__":
    main()