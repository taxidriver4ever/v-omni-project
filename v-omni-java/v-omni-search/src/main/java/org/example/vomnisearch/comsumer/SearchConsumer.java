package org.example.vomnisearch.comsumer;

import com.google.common.primitives.Floats;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.dto.SearchHistoryDTO;
import org.example.vomnisearch.dto.UserIdAndMediaIdDto;
import org.example.vomnisearch.dto.UserSearchVectorDto;
import org.example.vomnisearch.grpc.RecommendRequest;
import org.example.vomnisearch.grpc.RecommendResponse;
import org.example.vomnisearch.grpc.RecommenderGrpc;
import org.example.vomnisearch.grpc.Vector;
import org.example.vomnisearch.mapper.UserSearchHistoryMapper;
import org.example.vomnisearch.po.DocumentUserBehaviorHistoryPo;
import org.example.vomnisearch.po.UserSearchHistoryPo;
import org.example.vomnisearch.service.*;
import org.example.vomnisearch.util.SnowflakeIdWorker;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * V-Omni 全功能消费者
 * 职能：热搜、搜索历史、行为存证、向量缩放(x3)、gRPC 画像进化
 */
@Component
@Slf4j
public class SearchConsumer {

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisTemplate<String, byte[]> byteRedisTemplate;
    @Resource
    private StopWordService stopWordService;
    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;
    @Resource
    private UserSearchHistoryMapper userSearchHistoryMapper;
    @Resource
    private DocumentUserViewedService documentUserViewedService;
    @Resource
    private DocumentUserBehaviorHistoryService documentUserBehaviorHistoryService;
    @Resource
    private DocumentUserProfileService documentUserProfileService;
    @Resource
    private RecommenderGrpc.RecommenderBlockingStub recommenderStub;
    @Resource
    private HotWordRedisService hotWordRedisService;

    private final static String K_GLOBAL_PREFIX = "behavior:global:k:user_id:";
    private final static String K_CURRENT_PREFIX = "behavior:current:k:user_id:";
    private final static String V_SNAPSHOT_PREFIX = "behavior:snapshot:v:user_id:";

    private final static int TOTAL_SEQUENCE_LEN = 64;
    private final static int VECTOR_DIM = 512;

    @PostConstruct
    public void init() {
        try {
            stopWordService.importFromDirectory();
        } catch (IOException e) {
            log.error("初始化停用词失败", e);
        }
    }

    // ======================== Kafka 监听器部分 (全部归位) ========================

    /**
     * 1. 搜索内容向量消费
     */
    @KafkaListener(topics = "search-content-topic", groupId = "v-omni-search-group")
    public void userFeatureConsume(@NotNull UserSearchVectorDto dto, Acknowledgment ack) {
        if (updateBehaviorSequence(dto.getUserId(), dto.getMediaId(), "SEARCH")) {
            ack.acknowledge();
        }
    }

    /**
     * 2. 视频点击/观看行为消费
     */
    @KafkaListener(topics = "handle-viewed-topic", groupId = "v-omni-search-group")
    public void handleViewedTopic(@NotNull UserIdAndMediaIdDto dto, Acknowledgment ack) {
        try {
            documentUserViewedService.saveUserViewHistory(dto.getUserId(), dto.getMediaId());
            if (updateBehaviorSequence(dto.getUserId(), dto.getMediaId(), "CLICK")) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("点击行为消费异常: {}", e.getMessage());
        }
    }

    /**
     * 3. 热词统计消费
     */
    @KafkaListener(topics = "hot-word-topic", groupId = "v-omni-search-group")
    public void hotWordConsume(@NotNull String message, Acknowledgment ack) {
        try {
            String cleanInput = message.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]", " ").trim().replaceAll("\\s+", " ");
            if (cleanInput.length() >= 2 && cleanInput.length() <= 20 && !stopWordService.isStopWord(cleanInput)) {
                executeUpsert(cleanInput, "6");
                try {
                    List<String> tokens = documentVectorMediaService.analyzeText(cleanInput);
                    for (String token : tokens) {
                        if (!token.equalsIgnoreCase(cleanInput) && token.length() >= 2 && !stopWordService.isStopWord(token)) {
                            executeUpsert(token, "4");
                        }
                    }
                } catch (Exception e) {
                    log.error("热词分词分析失败: {}", e.getMessage());
                }
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("热词消费异常: {}", e.getMessage());
        }
    }

    /**
     * 4. 搜索历史存证消费
     */
    @KafkaListener(topics = "user-history-topic", groupId = "v-omni-search-group")
    public void userHistoryConsume(@NotNull SearchHistoryDTO dto, Acknowledgment ack) {
        try {
            Long userId = dto.getUserId();
            String keyword = dto.getKeyword();
            String redisKey = "search:keyword:user_id:" + userId;
            stringRedisTemplate.opsForZSet().add(redisKey, keyword, System.currentTimeMillis());
            stringRedisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, System.currentTimeMillis() - (7 * 86400000L));
            stringRedisTemplate.expire(redisKey, 30, TimeUnit.DAYS);

            Date now = new Date();
            UserSearchHistoryPo po = new UserSearchHistoryPo(snowflakeIdWorker.nextId(), userId, keyword, now, now);
            userSearchHistoryMapper.addUserSearchHistoryIfAbsentUpdateTime(po);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("搜索历史存证异常: {}", e.getMessage());
        }
    }

    // ======================== 核心逻辑处理部分 (带向量缩放) ========================

    private boolean updateBehaviorSequence(String userId, String mediaId, String behaviorType) {
        try {
            if (userId == null || mediaId == null) return true;

            // 获取向量：这里调用的 Service 内部已经做了 (float * 3.0f) 的缩放
            byte[] mediaVector1024 = documentVectorMediaService.getVectorByMediaId(mediaId);
            if (mediaVector1024 == null) return true;

            float[] businessMeta = getBusinessMetaVector(mediaId);
            byte[] snapshotVector1040 = combineVectorWithMeta(mediaVector1024, businessMeta);

            saveBehaviorRecord(userId, mediaId, behaviorType);

            String kCurrent = K_CURRENT_PREFIX + userId;
            String kGlobal = K_GLOBAL_PREFIX + userId;
            String vSnapshot = V_SNAPSHOT_PREFIX + userId;

            byteRedisTemplate.opsForList().rightPush(vSnapshot, snapshotVector1040);
            byteRedisTemplate.opsForList().trim(vSnapshot, -64, -1);

            processEvolutionAndDecay(userId, kCurrent, kGlobal, vSnapshot, mediaVector1024);

            expireKeys(kCurrent, kGlobal, vSnapshot);
            return true;
        } catch (Exception e) {
            log.error("画像闭环更新失败: {}", e.getMessage());
            return false;
        }
    }

    private void processEvolutionAndDecay(String userId, String kCurrent, String kGlobal, String vSnapshot, byte[] newK) {
        Long currentSize = byteRedisTemplate.opsForList().size(kCurrent);
        if (currentSize != null && currentSize >= 5L) {
            log.info("🔥 触发 V-Omni 进化: 用户 {}", userId);
            try {
                float[] oldQ = documentUserProfileService.getUserQueryVector(userId);
                float[][] longKV = documentUserProfileService.getLongTermKV(userId);
                List<byte[]> shortK = byteRedisTemplate.opsForList().range(kCurrent, 0, -1);
                List<byte[]> shortV = byteRedisTemplate.opsForList().range(vSnapshot, -5, -1);

                float[] newQ = invokePythonRecommender(userId, oldQ, longKV, shortK, shortV);

                if (newQ != null) {
                    documentUserProfileService.updateUserProfile(userId, newQ, new Date());
                }
                moveCurrentToGlobal(kCurrent, kGlobal);
            } catch (Exception e) {
                log.error("演化链路异常", e);
            }
        }
        byteRedisTemplate.opsForList().rightPush(kCurrent, newK);
    }

    private float[] invokePythonRecommender(String userId, float[] oldQ, float[][] longKV,
                                            List<byte[]> shortK, List<byte[]> shortV) {
        RecommendRequest.Builder builder = RecommendRequest.newBuilder()
                .setUserId(userId)
                .addQ5Videos(toVector(oldQ));

        int longSize = (longKV != null) ? longKV.length : 0;
        int shortSize = (shortK != null) ? shortK.size() : 0;
        int currentTotal = longSize + shortSize;

        if (longKV != null) {
            for (float[] row : longKV) {
                builder.addLongK(toVector(row));
                builder.addLongV(toVector(row));
            }
        }

        if (currentTotal < TOTAL_SEQUENCE_LEN) {
            float[] zeroPadding = new float[VECTOR_DIM];
            Vector zeroVec = toVector(zeroPadding);
            for (int i = 0; i < (TOTAL_SEQUENCE_LEN - currentTotal); i++) {
                builder.addLongK(zeroVec);
                builder.addLongV(zeroVec);
            }
        }

        if (shortK != null) shortK.forEach(b -> builder.addShortK(toVector(bytesToFloats(b))));
        if (shortV != null) shortV.forEach(b -> builder.addShortV(toVector(bytesToFloats(b))));

        try {
            RecommendResponse response = recommenderStub.getUserEmbedding(builder.build());
            if ("success".equals(response.getStatus())) {
                return Floats.toArray(response.getUserEmbeddingList());
            }
        } catch (Exception e) {
            log.error("gRPC 推理失败: {}", e.getMessage());
        }
        return null;
    }

    private void moveCurrentToGlobal(String kCurrent, String kGlobal) {
        List<byte[]> pending = byteRedisTemplate.opsForList().leftPop(kCurrent, 5);
        if (pending == null) return;

        List<byte[]> existingGlobal = byteRedisTemplate.opsForList().range(kGlobal, 0, -1);
        List<byte[]> finalGlobal = new ArrayList<>();

        if (existingGlobal != null) {
            for (byte[] v : existingGlobal) finalGlobal.add(applyDecay(v, 0.9f, 0.01f));
            if (finalGlobal.size() > 59) finalGlobal = finalGlobal.subList(finalGlobal.size() - 59, finalGlobal.size());
        }
        finalGlobal.addAll(pending);
        byteRedisTemplate.delete(kGlobal);
        byteRedisTemplate.opsForList().rightPushAll(kGlobal, finalGlobal);
    }

    private byte[] applyDecay(byte[] data, float factor, float minLimit) {
        float[] vector = bytesToFloats(data);
        for (int i = 0; i < vector.length; i++) vector[i] = Math.max(vector[i] * factor, minLimit);
        ByteBuffer out = ByteBuffer.allocate(data.length);
        for (float f : vector) out.putFloat(f);
        return out.array();
    }

    private void executeUpsert(String word, String score) {
        hotWordRedisService.incrementHotWord(word, Double.parseDouble(score));
    }

    private Vector toVector(float[] values) {
        return Vector.newBuilder().addAllValues(Floats.asList(values)).build();
    }

    private float[] bytesToFloats(byte[] bytes) {
        if (bytes == null) return new float[VECTOR_DIM];
        FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] floats = new float[fb.remaining()];
        fb.get(floats);
        return floats;
    }

    private float[] getBusinessMetaVector(String mediaId) {
        float[] meta = new float[4];
        meta[0] = 0.0f;
        meta[1] = getZSetLogCount("interaction:events:window:like:" + mediaId);
        meta[2] = getZSetLogCount("interaction:events:window:collection:" + mediaId);
        meta[3] = getZSetLogCount("interaction:events:window:comment:" + mediaId);
        return meta;
    }

    private float getZSetLogCount(String key) {
        Long count = stringRedisTemplate.opsForZSet().zCard(key);
        return (count == null || count == 0) ? 0.0f : (float) Math.log1p(count);
    }

    private byte[] combineVectorWithMeta(byte[] semantic, float[] meta) {
        ByteBuffer buffer = ByteBuffer.allocate(semantic.length + (meta.length * 4));
        buffer.put(semantic);
        for (float f : meta) buffer.putFloat(f);
        return buffer.array();
    }

    private void saveBehaviorRecord(String userId, String mediaId, String type) {
        DocumentUserBehaviorHistoryPo po = new DocumentUserBehaviorHistoryPo();
        po.setUserId(userId);
        po.setMediaId(mediaId);
        po.setBehaviorType(type);
        po.setCreateTime(new Date());
        documentUserBehaviorHistoryService.saveBehavior(po);
    }

    private void expireKeys(String... keys) {
        for (String k : keys) byteRedisTemplate.expire(k, 7, TimeUnit.DAYS);
    }
}