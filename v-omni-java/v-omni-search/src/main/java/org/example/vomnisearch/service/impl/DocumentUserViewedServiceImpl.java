package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.service.DocumentUserViewedService;
import org.example.vomnisearch.service.DocumentVectorMediaService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DocumentUserViewedServiceImpl implements DocumentUserViewedService {

    @Resource
    private ElasticsearchClient client;

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private org.example.vomnisearch.service.VectorService vectorService; // 你的 ONNX 服务

    @Resource
    private org.example.vomnisearch.service.UserRecommendationRedisService redisService;

    @Override
    public void saveUserViewHistory(String userId, String mediaId) throws IOException {
        // 1. 获取当前视频向量
        DocumentVectorMediaPo po = documentVectorMediaService.getVectorById(mediaId);

        if (po == null || po.getVideoEmbedding() == null || po.getTextEmbedding() == null) {
            log.warn("视频数据不完整，跳过足迹记录: {}", mediaId);
            return;
        }

        // 2. 融合当前视频向量：画面 + 标题
        float[] currentFusedVector = new float[512];
        for (int i = 0; i < 512; i++) {
            currentFusedVector[i] = (po.getVideoEmbedding().get(i) + po.getTextEmbedding().get(i)) / 2.0f;
        }

        // 3. 存入 user_view_history_index (保存燃料)
        Map<String, Object> history = new HashMap<>();
        history.put("user_id", userId);
        history.put("media_id", mediaId);
        history.put("create_time", System.currentTimeMillis());
        history.put("view_vector", currentFusedVector);

        client.index(i -> i.index("user_view_history_index").document(history));

        // 4. 触发演化：喂给 ONNX 模型更新“雪球”
        try {
            evolveInterest(userId, currentFusedVector);
        } catch (Exception e) {
            log.error("用户兴趣演化失败", e);
        }
    }

    /**
     * 核心演化逻辑：从 ES 捞取上一次记录，配合当前记录和 Redis 里的长期兴趣进行融合
     */
    private void evolveInterest(String userId, float[] currentVector) throws Exception {
        // 1. 从足迹索引中获取该用户最近的 2 条记录（包含刚存入的那条）
        // 目的是为了拿到“上一次”的向量 lastVector
        var response = client.search(s -> s
                        .index("user_view_history_index")
                        .query(q -> q.term(t -> t.field("user_id").value(userId)))
                        .sort(so -> so.field(f -> f.field("create_time").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                        .size(2),
                Map.class
        );

        List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = response.hits().hits();

        // 如果是新用户，只有 1 条记录，无法做 sequence 融合，直接存为初始兴趣
        if (hits.size() < 2) {
            redisService.saveInterestVector(Long.valueOf(userId), currentVector);
            return;
        }

        // 2. 准备模型输入：lastVector (历史) 和 currentInterest (长期)
        // 提取上一次行为的向量 (第 0 个是当前的，第 1 个是上一个)
        float[] lastVector = extractVectorFromMap(hits.get(1).source().get("view_vector"));

        // 获取 Redis 中的旧兴趣向量作为 long_term_vector
        float[] oldInterest = redisService.getInterestVector(Long.valueOf(userId));
        if (oldInterest == null) oldInterest = lastVector;

        // 3. 喂给 ONNX 模型
        // 调用你 ClipVectorServiceImpl 里的 fuseUserInterest 方法
        float[] newInterestVector = vectorService.fuseUserInterest(currentVector, lastVector, oldInterest);

        // 4. 更新 Redis 中的“雪球”
        redisService.saveInterestVector(Long.valueOf(userId), newInterestVector);
        log.info("用户 {} 兴趣雪球演化成功", userId);
    }

    /**
     * 将 ES 返回的 List<Double> 转换为 float[]
     */
    private float[] extractVectorFromMap(Object obj) {
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            float[] vec = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                vec[i] = ((Number) list.get(i)).floatValue();
            }
            return vec;
        }
        return null;
    }
}