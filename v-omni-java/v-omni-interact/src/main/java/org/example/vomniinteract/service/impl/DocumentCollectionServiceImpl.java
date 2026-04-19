package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentCollectionPo;
import org.example.vomniinteract.service.DocumentCollectionService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentCollectionServiceImpl implements DocumentCollectionService {

    private final ElasticsearchClient client;
    private final StringRedisTemplate redisTemplate;

    private static final String INDEX = "v_omni_collection";

    // ZSet 缓存 Key：每个用户维护一个按时间排序的收藏 ID 列表
    // Key 结构: user:collection:list:{userId}
    private static final String COLLECTION_ZSET_PREFIX = "interact:collection:user_id:";

    @Override
    public void upsert(DocumentCollectionPo doc) throws IOException {
        String docId = doc.getUserId() + "_" + doc.getMediaId();
        try {
            // 1. ES 持久化
            doc.setId(docId);
            client.index(i -> i.index(INDEX).id(docId).document(doc));

            // 2. ZSet 缓存维护 (核心：按时间戳排序)
            String zsetKey = COLLECTION_ZSET_PREFIX + doc.getUserId();
            long timestamp = doc.getCreateTime() != null ?
                    doc.getCreateTime().getTime() : System.currentTimeMillis();

            // Score 为时间戳，Member 为 mediaId (或者 docId)
            redisTemplate.opsForZSet().add(zsetKey, doc.getMediaId(), timestamp);

            // 3. 限制长度（可选）：比如只保留用户最近的 1000 个收藏，防止内存无限增长
            // redisTemplate.opsForZSet().remRangeByRank(zsetKey, 0, -1001);
            redisTemplate.expire(zsetKey, Duration.ofDays(7));

            log.info("收藏成功，ZSet 排序已更新: user={}, media={}", doc.getUserId(), doc.getMediaId());
        } catch (Exception e) {
            log.error("收藏写入失败", e);
            throw new IOException(e);
        }
    }

    @Override
    public void delete(String userId, String mediaId) throws IOException {
        String docId = userId + "_" + mediaId;
        try {
            // 1. ES 逻辑删除
            client.update(u -> u.index(INDEX).id(docId).doc(Map.of("deleted", true)), Object.class);

            // 2. ZSet 缓存移除
            String zsetKey = COLLECTION_ZSET_PREFIX + userId;
            redisTemplate.opsForZSet().remove(zsetKey, mediaId);

            log.info("从 ZSet 移除收藏: {}", docId);
        } catch (Exception e) {
            log.error("删除收藏失败", e);
            throw new IOException(e);
        }
    }
}
