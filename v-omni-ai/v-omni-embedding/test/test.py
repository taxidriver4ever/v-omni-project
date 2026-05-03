import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
import random

# 定义 11 个精细档位
ACTION_SPACE = np.linspace(0, 1, 11)


# ==========================================
# 阶段 1：模拟包含“互动率奖惩”的训练数据
# ==========================================
def generate_mock_offline_data(num_samples=25000):
    print(f"🚀 [阶段 1] 正在模拟日志：调整奖惩平衡，激发模型积极性...")
    buffer = []
    for _ in range(num_samples):
        saturation = random.uniform(0, 1)
        similarity = random.uniform(0.5, 1)
        vtr_diff = random.uniform(-0.5, 0.5)
        state = np.array([saturation, similarity, vtr_diff], dtype=np.float32)

        action_idx = random.randint(0, len(ACTION_SPACE) - 1)
        alpha = ACTION_SPACE[action_idx]

        # 计算疲劳得分
        fatigue_score = (saturation + similarity) * 0.5 - vtr_diff

        # 1. 基础奖励：调大权重，让模型更看重完播表现
        base_reward = vtr_diff * 10.0

        # 2. 互动因子 (真正的核心调整)
        interact_factor = 0

        if fatigue_score < 0.4:
            # 【用户上头区】：鼓励高 Alpha
            # 如果此时推用户喜欢的 (Alpha大)，额外加分
            if alpha > 0.7:
                interact_factor = 8.0 * alpha
            else:
                interact_factor = -2.0  # 如果用户上头你却推多样化，稍微给点小惩罚（浪费机会）

        elif fatigue_score > 0.7:
            # 【用户疲劳区】：严惩高 Alpha
            if alpha > 0.5:
                # 触发互动率暴跌，给一个固定的猛烈打击
                interact_factor = -15.0
            else:
                # 给了多样性，奖励“挽救行为”
                interact_factor = 5.0

        else:
            # 【平稳期】
            interact_factor = 2.0 if 0.3 <= alpha <= 0.6 else -1.0

        reward = base_reward + interact_factor

        # 3. 限制范围，保护梯度稳定
        reward = np.clip(reward, -20.0, 20.0)

        # 终止信号逻辑
        done = 1.0 if fatigue_score > 0.85 and alpha > 0.7 else 0.0

        next_state = (state + np.random.normal(0, 0.02, 3)).astype(np.float32)
        buffer.append((state, action_idx, reward, next_state, done))

    return buffer


# ==========================================
# 阶段 2：Q 网络 (依然是 3 维输入)
# ==========================================
class VOmniFineQNetwork(nn.Module):
    def __init__(self, action_dim):
        super(VOmniFineQNetwork, self).__init__()
        self.fc = nn.Sequential(
            nn.Linear(3, 128),
            nn.ReLU(),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, action_dim)
        )

    def forward(self, x):
        return self.fc(x)


def train_offline_rl(buffer, epochs=500):  # 建议先从 500 轮开始观察
    print("🧠 [阶段 2] 开始训练 (训练端已注入互动惩罚)...")
    action_dim = len(ACTION_SPACE)
    model = VOmniFineQNetwork(action_dim)

    # 调低学习率，并改用对异常值更鲁棒的 Huber Loss (SmoothL1)
    optimizer = optim.Adam(model.parameters(), lr=0.0005)
    loss_fn = nn.SmoothL1Loss()

    states = torch.tensor(np.array([x[0] for x in buffer]))
    actions = torch.tensor([x[1] for x in buffer], dtype=torch.int64).unsqueeze(1)
    rewards = torch.tensor([x[2] for x in buffer], dtype=torch.float32)
    next_states = torch.tensor(np.array([x[3] for x in buffer]))
    dones = torch.tensor([x[4] for x in buffer], dtype=torch.float32)

    for epoch in range(epochs):
        # 当前 Q 值
        q_values = model(states).gather(1, actions).squeeze()

        # 目标 Q 值
        with torch.no_grad():
            max_next_q = model(next_states).max(1)[0]
            target_q = rewards + 0.95 * max_next_q * (1 - dones)

        loss = loss_fn(q_values, target_q)
        optimizer.zero_grad()
        loss.backward()

        # 增加梯度裁剪，彻底解决 Loss 爆炸问题
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)

        optimizer.step()

        if (epoch + 1) % 100 == 0:
            print(f"   Epoch {epoch + 1}/{epochs} | Loss: {loss.item():.6f}")

    return model


# ==========================================
# 阶段 3：测试 (输入保持不变)
# ==========================================
def test_fine_inference(model):
    print("\n🎯 [阶段 3] 测试：模型是否学会了“为了互动率而克制”...")
    test_cases = [
        {"name": "极度上头", "state": [0.1, 0.4, 0.6]},  # 应该推荐高 Alpha
        {"name": "中度疲劳", "state": [0.6, 0.7, 0.1]},  # 应该收敛到中 Alpha
        {"name": "极度厌倦", "state": [0.9, 0.9, -0.3]}  # 应该推荐极低 Alpha (Alpha=0.0或0.1)
    ]

    model.eval()
    with torch.no_grad():
        for case in test_cases:
            state_tensor = torch.tensor(case["state"], dtype=torch.float32).unsqueeze(0)
            q_values = model(state_tensor).numpy()[0]
            best_action_idx = np.argmax(q_values)
            recommended_alpha = ACTION_SPACE[best_action_idx]

            print(f"👤 {case['name']} -> 决策 Alpha: {recommended_alpha:.1f}")
            # 如果 Alpha 在极度厌倦下变小了，说明惩罚生效了


if __name__ == "__main__":
    data = generate_mock_offline_data(25000)
    model = train_offline_rl(data, epochs=20000)  # 1000轮通常就能看到非常稳定的结果
    test_fine_inference(model)

# import torch
# import torch.nn as nn
# import torch.optim as optim
#
#
# class SimpleDLRM(nn.Module):
#     def __init__(self, dense_in_features, sparse_vocab_sizes, embedding_dim):
#         super(SimpleDLRM, self).__init__()
#
#         self.embedding_dim = embedding_dim
#         self.num_sparse = len(sparse_vocab_sizes)
#         # 总共有多少个向量参与内积：1个连续向量 + N个离散向量
#         self.num_vectors = 1 + self.num_sparse
#
#         # ==========================================
#         # 1. 连续特征处理：Bottom MLP
#         # ==========================================
#         self.bottom_mlp = nn.Sequential(
#             nn.Linear(dense_in_features, 64),
#             nn.ReLU(),
#             nn.Linear(64, 32),
#             nn.ReLU(),
#             nn.Linear(32, embedding_dim)  # 最后一层必须输出 embedding_dim
#         )
#
#         # ==========================================
#         # 2. 离散特征处理：Embedding Tables
#         # ==========================================
#         self.embeddings = nn.ModuleList([
#             nn.Embedding(vocab_size, embedding_dim)
#             for vocab_size in sparse_vocab_sizes
#         ])
#
#         # ==========================================
#         # 3. 顶部融合处理：Top MLP
#         # ==========================================
#         # 计算交互层输出的维度：N个向量两两内积产生 N*(N-1)/2 个数值
#         interaction_dim = (self.num_vectors * (self.num_vectors - 1)) // 2
#         # Top MLP 输入 = 原始连续向量的维度 + 交互数值的数量
#         top_in_features = embedding_dim + interaction_dim
#
#         self.top_mlp = nn.Sequential(
#             nn.Linear(top_in_features, 64),
#             nn.ReLU(),
#             nn.Linear(64, 32),
#             nn.ReLU(),
#             nn.Linear(32, 1),
#             nn.Sigmoid()  # 输出 0~1 的点击概率
#         )
#
#     def forward(self, dense_x, sparse_x, debug=False):
#         # 1. 连续数据提炼
#         v_dense = self.bottom_mlp(dense_x)  # Shape: [Batch, embed_dim]
#
#         # 2. 离散数据查表
#         v_sparse_list = []
#         for i in range(self.num_sparse):
#             # 获取第 i 个离散特征的 ID，并查表
#             v_i = self.embeddings[i](sparse_x[:, i])
#             v_sparse_list.append(v_i)
#
#         # 3. 交互层 (Interaction) - 最核心的数学逻辑
#         # 把连续向量和所有离散向量堆叠在一起
#         # v_dense 需要增加一个维度变成 [Batch, 1, embed_dim]
#         v_dense_expanded = v_dense.unsqueeze(1)
#         v_sparse_stacked = torch.stack(v_sparse_list, dim=1)  # Shape: [Batch, num_sparse, embed_dim]
#
#         # 所有的向量坐在同一张桌子上 (Total vectors: 1 + num_sparse)
#         all_vectors = torch.cat([v_dense_expanded, v_sparse_stacked], dim=1)
#
#         # 矩阵乘法计算两两内积: Q * K^T
#         interactions = torch.bmm(all_vectors, all_vectors.transpose(1, 2))  # Shape: [Batch, num_vectors, num_vectors]
#
#         # 提取上三角矩阵的值（去掉对角线自己的内积，和重复的下三角）
#         rows, cols = torch.triu_indices(self.num_vectors, self.num_vectors, offset=1)
#         interactions_flat = interactions[:, rows, cols]  # Shape: [Batch, interaction_dim]
#
#         # 4. 特征大拼接 (Concatenation)
#         # 将 最初的连续特征向量 + 所有的内积数值 拼接
#         concat_features = torch.cat([v_dense, interactions_flat], dim=1)
#
#         # 5. 顶层预测
#         output = self.top_mlp(concat_features)  # Shape: [Batch, 1]
#
#         # --- 打印观察室 ---
#         if debug:
#             print("\n" + "=" * 40)
#             print("🚀 前向传播 Debug 观察室")
#             print("=" * 40)
#             print(f"1. 输入的连续数据:\t {dense_x.shape}")
#             print(f"2. 翻译后的连续向量 (v_dense): {v_dense.shape}  <-- Bottom MLP 输出")
#             print(
#                 f"3. 参加会议的总向量数:\t {all_vectors.shape[1]} 个 (1连续 + {self.num_sparse}离散), 每个长 {self.embedding_dim}")
#             print(f"4. 内积矩阵的结果:\t {interactions.shape} <-- 包含了所有两两碰撞的火花")
#             print(f"5. 提取出的交互数值:\t {interactions_flat.shape} <-- {self.num_vectors}*({self.num_vectors}-1)/2")
#             print(
#                 f"6. Top MLP 的长特征输入:\t {concat_features.shape} <-- {self.embedding_dim} + {interactions_flat.shape[1]}")
#             print(f"7. 最终的点赞预测概率:\t {output.shape}")
#             print("=" * 40 + "\n")
#
#         return output
#
#
# # ==========================================
# # 测试流水线
# # ==========================================
# if __name__ == "__main__":
#     # --- 1. 模拟环境配置 ---
#     BATCH_SIZE = 8
#     DENSE_DIMS = 13  # 13维连续特征 (比如视频完播率、用户活跃度等)
#     SPARSE_VOCABS = [1000, 500, 200]  # 3个离散特征，词表大小分别为 1000, 500, 200 (比如 UserID, 视频类别ID, 设备类型)
#     EMBEDDING_DIM = 16  # 为了方便看，维度设为16
#
#     model = SimpleDLRM(
#         dense_in_features=DENSE_DIMS,
#         sparse_vocab_sizes=SPARSE_VOCABS,
#         embedding_dim=EMBEDDING_DIM
#     )
#
#     # 定义损失函数 (BCE) 和优化器
#     criterion = nn.BCELoss()
#     optimizer = optim.Adam(model.parameters(), lr=0.01)
#
#     # --- 2. 模拟数据生成 ---
#     # 连续数据：通常要归一化，这里用 rand 生成 0-1 之间的数据
#     mock_dense_x = torch.rand(BATCH_SIZE, DENSE_DIMS)
#     # 离散数据：生成对应词表范围内的整数 ID
#     mock_sparse_x = torch.stack([
#         torch.randint(0, SPARSE_VOCABS[0], (BATCH_SIZE,)),
#         torch.randint(0, SPARSE_VOCABS[1], (BATCH_SIZE,)),
#         torch.randint(0, SPARSE_VOCABS[2], (BATCH_SIZE,))
#     ], dim=1)
#     # 真实标签：模拟用户是否点赞 (0 或 1)
#     mock_y = torch.randint(0, 2, (BATCH_SIZE, 1)).float()
#
#     # --- 3. 运行一次推理 (看清内部结构) ---
#     print("【步骤 A：单次推理观测】")
#     model.eval()  # 开启评估模式
#     with torch.no_grad():
#         predictions = model(mock_dense_x, mock_sparse_x, debug=True)
#         print(f"第一条数据的预测点赞概率: {predictions[0].item():.4f}\n")
#
#     # --- 4. 运行训练循环 (看模型如何进化) ---
#     print("【步骤 B：训练过程观测】")
#     model.train()  # 开启训练模式
#     epochs = 5
#     for epoch in range(epochs):
#         optimizer.zero_grad()
#
#         # 前向传播 (关闭 debug 避免刷屏)
#         outputs = model(mock_dense_x, mock_sparse_x, debug=False)
#
#         # 计算损失
#         loss = criterion(outputs, mock_y)
#
#         # 反向传播 (此时 Top MLP, Bottom MLP 和 Embedding 都在更新！)
#         loss.backward()
#         optimizer.step()
#
#         print(f"Epoch [{epoch + 1}/{epochs}] | Loss: {loss.item():.4f}")