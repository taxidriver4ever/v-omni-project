import numpy as np


class Reranker:

    def __init__(self,
                 diversity_weight=0.3,
                 novelty_weight=0.2):

        self.diversity_weight = diversity_weight
        self.novelty_weight = novelty_weight

    # ------------------------
    # cosine similarity
    # ------------------------
    def cosine(self, a, b):

        a = np.array(a)
        b = np.array(b)

        return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-8)

    # ------------------------
    # rerank entry
    # ------------------------
    def rerank(self, scored_items):

        """
        scored_items: [(item, score, embedding)]
        """

        selected = []

        for item, score, emb in scored_items:

            diversity_penalty = 0

            # 计算与已选集合的相似度
            for s_item, s_score, s_emb in selected:

                sim = self.cosine(emb, s_emb)

                diversity_penalty += sim

            diversity_penalty = diversity_penalty * self.diversity_weight

            final_score = score - diversity_penalty

            selected.append((item, final_score, emb))

        # sort again
        selected.sort(key=lambda x: -x[1])

        return selected