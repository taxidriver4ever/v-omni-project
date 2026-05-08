package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentUserProfilePo;
import org.example.vomniinteract.service.DocumentUserProfileService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.example.vomniinteract.grpc.*;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class DocumentUserProfileServiceImpl implements DocumentUserProfileService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX_NAME = "user_profile_index";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Optional<DocumentUserProfilePo> getUserProfile(String userId) throws IOException {
        try {
            // 使用 ES 的 Get API，通过 ID 直接检索
            GetResponse<DocumentUserProfilePo> response = client.get(g -> g
                            .index(INDEX_NAME)
                            .id(userId),
                    DocumentUserProfilePo.class
            );

            if (response.found()) {
                return Optional.ofNullable(response.source());
            } else {
                log.warn("未找到用户画像数据: userId = {}", userId);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.error("查询 ES 用户画像异常: {}", userId, e);
            throw e;
        }
    }

    @Override
    public float[] getUserInterestVector(String userId) throws IOException {
        // 复用现有的查询逻辑获取整个 PO
        return getUserProfile(userId)
                .map(DocumentUserProfilePo::getInterestVector)
                .orElseGet(() -> {
                    log.warn("⚠️ 用户 {} 暂无兴趣向量，返回空向量", userId);
                    return new float[0];
                });
    }

    @Override
    public float[] getUserQueryVector(String userId) throws IOException {
        return getUserProfile(userId)
                .map(DocumentUserProfilePo::getInterestVector)
                .orElseGet(() -> {
                    log.info("用户 {} 无画像状态，返回 512 维零向量", userId);
                    return new float[512];
                });
    }

    /**
     * 局部更新用户画像：只更新 512 维实时兴趣向量，不影响长期矩阵字段
     */
    @Override
    public void updateUserProfile(String userId, float[] newInterestVector, Date date) throws IOException {
        try {
            // 1. 创建一个只包含需要更新字段的 PO 对象
            DocumentUserProfilePo partialPo = new DocumentUserProfilePo();
            partialPo.setInterestVector(newInterestVector);
            partialPo.setUpdateTime(date);

            // 2. 使用 update 接口进行局部合并
            client.update(u -> u
                            .index(INDEX_NAME)
                            .id(userId)
                            .doc(partialPo) // doc() 方法会自动处理局部字段合并
                            .docAsUpsert(true), // 如果用户文档不存在，则直接创建
                    DocumentUserProfilePo.class
            );

            log.debug("用户 {} 的实时兴趣向量已局部更新至 ES", userId);
        } catch (IOException e) {
            log.error("ES画像局部更新失败, userId: {}", userId, e);
            throw e;
        }
    }

    /**
     * 获取 8 个长期兴趣质心，并动态注入 Redis 里的实时业务分
     */
    @Override
    public List<InterestCentroid> getInterestCentroids(String userId) throws IOException {
        return getUserProfile(userId)
                .map(po -> {
                    List<InterestCentroid> baseCentroids = po.getReshapedCentroids();
                    List<InterestCentroid> dynamicCentroids = new ArrayList<>();

                    for (int i = 0; i < baseCentroids.size(); i++) {
                        // 动态获取这第 i 个兴趣簇的业务表现
                        // Key 建议格式：user:cluster:biz:{userId}:{index}
                        float[] bizLabels = getDynamicClusterBizLabels(userId, i);

                        dynamicCentroids.add(baseCentroids.get(i).toBuilder()
                                .addAllBizLabels(com.google.common.primitives.Floats.asList(bizLabels))
                                .build());
                    }
                    return dynamicCentroids;
                })
                .orElseGet(() -> {
                    log.info("用户 {} 无长期数据，返回 8 个全零占位质心", userId);
                    return createDefaultEmptyCentroids();
                });
    }

    /**
     * 动态获取或计算业务维度 (4维: 权重, 点赞, 收藏, 评论)
     */
    private float[] getDynamicClusterBizLabels(String userId, int index) {
        String redisKey = "user:cluster:biz:" + userId + ":" + index;

        // 1. 尝试从 Redis 获取（这些值可以由离线任务或 Flink 实时更新）
        Map<Object, Object> stats = stringRedisTemplate.opsForHash().entries(redisKey);

        if (stats.isEmpty()) {
            // 2. 兜底逻辑：如果 Redis 没有，说明不是热点兴趣，给一个基础衰减值
            // 比如：[0.1, 0.0, 0.0, 0.0]
            return new float[]{0.1f, 0.0f, 0.0f, 0.0f};
        }

        // 3. 正常解析 Redis 里的数据
        float[] biz = new float[4];
        biz[0] = Float.parseFloat((String) stats.getOrDefault("weight", "0.5"));
        biz[1] = getLogScaleValue((String) stats.get("like"));
        biz[2] = getLogScaleValue((String) stats.get("collect"));
        biz[3] = getLogScaleValue((String) stats.get("comment"));
        return biz;
    }

    private float getLogScaleValue(String val) {
        if (val == null) return 0.0f;
        long count = Long.parseLong(val);
        return count <= 0 ? 0.0f : (float) Math.log1p(count);
    }

    private List<InterestCentroid> createDefaultEmptyCentroids() {
        List<InterestCentroid> list = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            list.add(InterestCentroid.newBuilder()
                    .addAllEmbedding(com.google.common.primitives.Floats.asList(new float[512]))
                    .addAllBizLabels(com.google.common.primitives.Floats.asList(new float[4]))
                    .build());
        }
        return list;
    }

}