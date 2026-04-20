package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import org.example.vomnisearch.service.EmbeddingService;
import org.example.vomnisearch.service.MinioService;
import org.example.vomnisearch.service.SearchService;
import org.example.vomnisearch.service.DocumentVectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class SearchServiceImpl implements SearchService {

    private final static String HOT_WORD_TOPIC = "hot-word-topic";

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private MinioService minioService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    private static final String HISTORY_PREFIX = "search:keyword:user_id:";


    @Override
    public List<String> searchVideo(@NotNull String content) throws Exception {
        kafkaTemplate.send(HOT_WORD_TOPIC,content);
        float[][] vector = embeddingService.getVector(content);
        if (vector == null || vector.length == 0) {
            return Collections.emptyList();
        }
        List<String> strings = documentVectorMediaService.hybridSearchIds(content, vector[0], content, 10);
        if(strings == null || strings.isEmpty()) return List.of();
        List<String> results = new ArrayList<>();
        for (String string : strings) {
            String urlKey = "search:url:media-id:" + string;
            String redisUrl = stringRedisTemplate.opsForValue().get(urlKey);
            if(redisUrl != null) {
                results.add(redisUrl);
                continue;
            }
            String s = minioService.generateHlsPlaybackUrl(string);
            stringRedisTemplate.opsForValue().setIfAbsent(urlKey, s, 29, TimeUnit.MINUTES);
            results.add(s);
        }
        return results;
    }

    @Override
    public List<String> getUserHistory(Long userId) {
        String redisKey = HISTORY_PREFIX + userId;

        // reverseRange(key, start, end) 是闭区间
        // 0 到 9 代表取按分数从大到小排的前 10 个元素
        Set<String> historySet = stringRedisTemplate.opsForZSet().reverseRange(redisKey, 0, 9);

        if (historySet == null || historySet.isEmpty()) {
            return Collections.emptyList();
        }

        // 转为 List 返回给前端，保持顺序
        return new ArrayList<>(historySet);
    }

    @Override
    public void removeHistory(String userId, String keyword) {
        // 仅删除 Redis 视图，用户搜不到这行历史，但 MySQL 数据依旧存在用于画像
        stringRedisTemplate.opsForZSet().remove(HISTORY_PREFIX + userId, keyword);
    }

    @Override
    public void clearAllHistory(String userId) {
        // 直接删除整个 ZSet
        stringRedisTemplate.delete(HISTORY_PREFIX + userId);
    }
}
