package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import org.example.vomnisearch.service.UserRecommendationRedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UserRecommendationRedisServiceImpl implements UserRecommendationRedisService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String SEEN_ZSET_PREFIX = "user:seen:ids:";
    private static final String INTEREST_VEC_PREFIX = "user:interest:vector:";

    /**
     * 1. 获取近期已读 ID（供 ES 的 must_not 使用）
     */
    @Override
    public List<String> getRecentSeenIds(String userId, int limit) {
        String key = SEEN_ZSET_PREFIX + userId;
        // ZSet 按照分数从大到小排列（最近的在前面）
        Set<Object> set = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        if (set == null || set.isEmpty()) {
            return Collections.emptyList();
        }
        return set.stream().map(Object::toString).collect(Collectors.toList());
    }

    /**
     * 2. 写入已读记录并修剪（维持在 500 个）
     */
    @Override
    public void addSeenId(Long userId, String mediaId) {
        String key = SEEN_ZSET_PREFIX + userId;
        double now = (double) System.currentTimeMillis();

        // 添加到 ZSet
        redisTemplate.opsForZSet().add(key, mediaId, now);

        // 自动裁剪：只保留最新的 500 个，移除排名在 500 之外的（旧的）
        // Rank 是从小到大排的，0 是最旧的
        Long size = redisTemplate.opsForZSet().zCard(key);
        if (size != null && size > 500) {
            redisTemplate.opsForZSet().removeRange(key, 0, size - 501);
        }

        // 设置 30 天过期
        redisTemplate.expire(key, 30, TimeUnit.DAYS);
    }

    /**
     * 3. 存储/更新兴趣向量（雪球结果）
     */
    @Override
    public void saveInterestVector(Long userId, float[] vector) {
        String key = INTEREST_VEC_PREFIX + userId;
        // 直接存入 float[] 数组
        redisTemplate.opsForValue().set(key, vector, 30, TimeUnit.DAYS);
    }

    /**
     * 4. 读取兴趣向量
     */
    @Override
    public float[] getInterestVector(Long userId) {
        String key = INTEREST_VEC_PREFIX + userId;
        Object result = redisTemplate.opsForValue().get(key);
        if (result instanceof List) {
            // Jackson 序列化 float[] 有时会反序列化为 List<Double>
            List<Double> list = (List<Double>) result;
            float[] vec = new float[list.size()];
            for (int i = 0; i < list.size(); i++) vec[i] = list.get(i).floatValue();
            return vec;
        }
        return (float[]) result;
    }

    /**
     * 记录用户看过的视频 ID 到 ZSet
     * @param userId 用户ID
     * @param mediaId 视频ID
     */
    @Override
    public void addSeenIdToZSet(String userId, String mediaId) {
        String key = "user:seen:ids:" + userId;
        long now = System.currentTimeMillis();

        // 1. 添加到 ZSet，Score 使用当前时间戳，方便按时间排序
        redisTemplate.opsForZSet().add(key, mediaId, (double) now);

        // 2. 自动裁剪：只保留最新的 500 个
        // 计算当前总数
        Long count = redisTemplate.opsForZSet().zCard(key);
        if (count != null && count > 500) {
            // 移除从 0 开始到 (count - 501) 的元素
            // ZSet 默认按 Score（时间戳）从小到大排，0 是最旧的
            redisTemplate.opsForZSet().removeRange(key, 0, count - 501);
        }

        // 3. 设置过期时间（例如 30 天），防止冷用户长期占用内存
        redisTemplate.expire(key, 30, TimeUnit.DAYS);
    }
}
