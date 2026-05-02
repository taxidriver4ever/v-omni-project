import torch
from torch.utils.data import DataLoader
from models.dlrm import DLRM
from data.dlrm_dataset import InteractionDataset


def train():

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    # 数据
    dataset = InteractionDataset("data/train.npy")

    dataloader = DataLoader(dataset, batch_size=256, shuffle=True)

    # 模型
    model = DLRM(emb_dim=512).to(device)

    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
    loss_fn = torch.nn.BCELoss()

    for epoch in range(10):

        total_loss = 0

        for user_emb, item_emb, label in dataloader:

            user_emb = user_emb.to(device)
            item_emb = item_emb.to(device)
            label = label.to(device).float().unsqueeze(-1)

            pred = model(user_emb, item_emb)

            loss = loss_fn(pred, label)

            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            total_loss += loss.item()

        print(f"epoch {epoch}, loss={total_loss:.4f}")

    torch.save(model.state_dict(), "dlrm.pth")


if __name__ == "__main__":
    train()