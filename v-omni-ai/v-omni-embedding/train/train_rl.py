import torch
import torch.optim as optim
import random
import numpy as np
from models.reranker import RerankQNetwork
from data.rl_dataset import RerankEnvironment


def train():
    EMB_DIM = 16
    TOP_K = 5
    STATE_DIM = EMB_DIM * 2 + 1

    q_net = RerankQNetwork(STATE_DIM)
    optimizer = optim.Adam(q_net.parameters(), lr=1e-3)
    loss_fn = torch.nn.SmoothL1Loss()

    for episode in range(1000):
        # 模拟生成数据集 (Dataset Generation)
        candidates = [{'id': i, 'score': random.random(), 'emb': np.random.randn(EMB_DIM)} for i in range(20)]
        env = RerankEnvironment(candidates, TOP_K, EMB_DIM)
        states = env.reset()

        while True:
            # Epsilon-greedy 探索
            q_values = q_net(states)
            action_idx = torch.argmax(q_values).item() if random.random() > 0.1 else random.randint(0, len(states) - 1)

            curr_state = states[action_idx]
            reward, next_states, done = env.step(action_idx)

            # 计算 Target Q
            with torch.no_grad():
                target = reward + (0.95 * torch.max(q_net(next_states)) if not done else 0)

            # 更新网络
            loss = loss_fn(q_net(curr_state), target.unsqueeze(0))
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(q_net.parameters(), 1.0)  # 加上你之前的防爆炸
            optimizer.step()

            if done: break
            states = next_states

        if episode % 100 == 0:
            print(f"Episode {episode} Training...")

    # 保存模型给 Java 端或推理端使用
    torch.save(q_net.state_dict(), "v_omni_rerank_v1.pth")
    print("Model Saved!")


if __name__ == "__main__":
    train()