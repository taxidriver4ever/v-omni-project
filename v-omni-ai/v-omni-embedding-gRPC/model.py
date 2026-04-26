import torch
import torch.nn as nn


class VOmniRecommender(nn.Module):
    def __init__(self, embed_dim=512):
        super(VOmniRecommender, self).__init__()
        self.embed_dim = embed_dim

        # 头1&2：负责捕捉短期猎奇（新鲜 K）
        # batch_first=True 表示输入数据的维度是 [Batch, Seq_Len, Dim]
        self.short_attn = nn.MultiheadAttention(embed_dim, num_heads=2, batch_first=True)

        # 头3&4：负责锚定长期人设（持久 K）
        self.long_attn = nn.MultiheadAttention(embed_dim, num_heads=2, batch_first=True)

        # 最后的特征融合层（可选：给融合后的向量做一次非线性映射）
        self.fusion_layer = nn.Sequential(
            nn.Linear(embed_dim, embed_dim),
            nn.LayerNorm(embed_dim)
        )

    def forward(self, q_5_videos, short_k, long_k, short_weights):
        """
        q_5_videos: [Batch, 5, 512] 当前触发请求的 5 个完播视频
        short_k:    [Batch, 64, 512] 最近 64 个视频特征
        long_k:     [Batch, 64, 512] 长期 64 个兴趣中心
        short_weights: [Batch, 64]   Java 算好的行为权重（如点赞2.0，完播1.5）
        """
        # 1. 构造当前意图 Q：对 5 个视频做 Mean Pooling，变成一个形状为 [Batch, 1, 512] 的向量
        query = torch.mean(q_5_videos, dim=1, keepdim=True)

        # 2. 注入行为权重：将 short_weights 扩展维度后与 short_k 相乘
        # [Batch, 64] -> [Batch, 64, 1]
        short_weights = short_weights.unsqueeze(-1)
        weighted_short_k = short_k * short_weights

        # 3. 短期注意力计算 (V = K)
        # 返回值包含 output 和 attention_weights，我们只需要 output
        short_out, _ = self.short_attn(query=query, key=weighted_short_k, value=weighted_short_k)

        # 4. 长期注意力计算 (V = K)
        # 长期向量通常已经是聚类中心，不需要额外的 action weight
        long_out, _ = self.long_attn(query=query, key=long_k, value=long_k)

        # 5. 动态权重融合
        # 工业界常态：短期兴趣占主导，长期兴趣做兜底。这里给短期 0.7，长期 0.3
        user_embedding = 0.7 * short_out + 0.3 * long_out

        # 去掉 Seq_Len 那个维度：[Batch, 1, 512] -> [Batch, 512]
        user_embedding = user_embedding.squeeze(1)

        # 过一次 LayerNorm 保证输出向量的分布稳定，这非常利于后端的 HNSW 检索
        final_vector = self.fusion_layer(user_embedding)

        return final_vector