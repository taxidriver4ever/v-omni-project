import numpy as np
import torch


class RerankEnvironment:
    def __init__(self, candidates, top_k, emb_dim, div_weight=0.3):
        self.candidates = candidates
        self.top_k = top_k
        self.emb_dim = emb_dim
        self.div_weight = div_weight
        self.selected = []
        self.remaining = [c.copy() for c in candidates]

    def reset(self):
        self.selected = []
        self.remaining = [c.copy() for c in self.candidates]
        return self._get_all_remaining_states()

    def step(self, action_index):
        item = self.remaining.pop(action_index)

        # 计算即时奖励 (基于完播率 + 多样性惩罚)
        sim_penalty = 0
        if self.selected:
            sims = [np.dot(item['emb'], s['emb']) / (np.linalg.norm(item['emb']) * np.linalg.norm(s['emb']) + 1e-8)
                    for s in self.selected]
            sim_penalty = sum(sims)

        # 训练阶段注入的奖励公式
        reward = item['score'] - self.div_weight * sim_penalty
        self.selected.append(item)

        done = len(self.selected) >= self.top_k or not self.remaining
        next_states = self._get_all_remaining_states() if not done else None
        return reward, next_states, done

    def _get_all_remaining_states(self):
        if not self.remaining: return None
        # 特征工程：[候选emb, 已选平均emb, 候选原始分数]
        states = []
        s_emb = np.mean([s['emb'] for s in self.selected], axis=0) if self.selected else np.zeros(self.emb_dim)
        for c in self.remaining:
            states.append(np.concatenate([c['emb'], s_emb, [c['score']]]))
        return torch.tensor(np.array(states), dtype=torch.float32)