import torch
import numpy as np

from models.dlrm import DLRM
from models.transformer_ranker import TransformerRanker
from models.reranker import Reranker


class RankServicer:

    def __init__(self, topk=50):

        self.dlrm = DLRM()
        self.tr = TransformerRanker()
        self.reranker = Reranker()

        self.topk = topk

        self.dlrm.eval()
        self.tr.eval()

    # =========================
    # ranking1: DLRM coarse rank
    # =========================
    def rank_stage1(self, user, candidates):

        scores = []

        with torch.no_grad():

            for item in candidates:

                score = self.dlrm(user, item).item()
                scores.append((item, score))

        scores.sort(key=lambda x: -x[1])

        return scores[:self.topk]   # 保留score，不丢

    # =========================
    # ranking2: Transformer fine rank
    # =========================
    def rank_stage2(self, history, candidates_with_score):

        scores = []

        with torch.no_grad():

            for item, dlrm_score in candidates_with_score:

                tr_score = self.tr(history, item).item()

                final_score = 0.5 * dlrm_score + 0.5 * tr_score

                scores.append((item, final_score))

        scores.sort(key=lambda x: -x[1])

        return scores

    # =========================
    # step3: rerank
    # =========================
    def rank_stage3(self, scored_items):

        # ⚠️ 这里需要 embedding（你现在用item placeholder）
        enriched = []

        for item, score in scored_items:

            emb = item  # 你现在假设item就是embedding（后面要换）

            enriched.append((item, score, emb))

        return self.reranker.rerank(enriched)

    # =========================
    # full pipeline
    # =========================
    def rank(self, user, history, candidates):

        # step1: DLRM
        stage1 = self.rank_stage1(user, candidates)

        # step2: Transformer
        stage2 = self.rank_stage2(history, stage1)

        # step3: rerank
        stage3 = self.rank_stage3(stage2)

        return stage3