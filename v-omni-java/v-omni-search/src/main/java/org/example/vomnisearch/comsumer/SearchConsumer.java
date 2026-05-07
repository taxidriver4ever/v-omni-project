package org.example.vomnisearch.comsumer;

import com.google.common.primitives.Floats;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.dto.SearchHistoryDTO;
import org.example.vomnisearch.dto.UserSearchVectorDto;
import org.example.vomnisearch.grpc.*;
import org.example.vomnisearch.mapper.UserSearchHistoryMapper;
import org.example.vomnisearch.po.DocumentUserBehaviorHistoryPo;
import org.example.vomnisearch.po.UserSearchHistoryPo;
import org.example.vomnisearch.service.*;
import org.example.vomnisearch.util.SnowflakeIdWorker;
import org.example.vomnisearch.util.VectorUtil;
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
import java.util.stream.IntStream;

/**
 * V-Omni 全功能消费者
 * 职能：热搜、搜索历史、行为存证、向量提取、gRPC 画像进化
 * 逻辑：Global 池 (24条定长) + 步进计数器 (每5次触发)
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
    private DocumentUserBehaviorHistoryService documentUserBehaviorHistoryService;
    @Resource
    private DocumentUserProfileService documentUserProfileService;
    @Resource
    private UserModelServiceGrpc.UserModelServiceBlockingStub userModelStub;
    @Resource
    private HotWordRedisService hotWordRedisService;

    // Redis Key 定义
    private final static String K_GLOBAL_PREFIX = "behavior:global:k:user_id:";
    private final static String V_GLOBAL_PREFIX = "behavior:global:v:user_id:";
    private final static String COUNTER_PREFIX = "behavior:counter:user_id:";

    // 架构参数
    private final static int GLOBAL_CAPACITY = 24;
    private final static int TRIGGER_STEP = 4;
    private final static int VECTOR_DIM = 512;
    private final static int BIZ_DIM = 4;

    @PostConstruct
    public void init() {
        try {
            stopWordService.importFromDirectory();
        } catch (IOException e) {
            log.error("初始化停用词失败", e);
        }
    }

    // ======================== Kafka 监听器部分 ========================

    @KafkaListener(topics = "search-content-topic", groupId = "v-omni-search-group")
    public void userFeatureConsume(@NotNull UserSearchVectorDto dto, Acknowledgment ack) {
        if (updateBehaviorSequence(dto.getUserId(), dto.getMediaId(), "SEARCH")) {
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = "hot-word-topic", groupId = "v-omni-search-group")
    public void hotWordConsume(@NotNull String message, Acknowledgment ack) {
        try {
            String cleanInput = message.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]", " ").trim().replaceAll("\\s+", " ");
            if (cleanInput.length() >= 2 && cleanInput.length() <= 20 && !stopWordService.isStopWord(cleanInput)) {
                hotWordRedisService.incrementHotWord(cleanInput, 6.0);
                try {
                    List<String> tokens = documentVectorMediaService.analyzeText(cleanInput);
                    for (String token : tokens) {
                        if (!token.equalsIgnoreCase(cleanInput) && token.length() >= 2 && !stopWordService.isStopWord(token)) {
                            hotWordRedisService.incrementHotWord(token, 4.0);
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

    @KafkaListener(topics = "user-history-topic", groupId = "v-omni-search-group")
    public void userHistoryConsume(@NotNull SearchHistoryDTO dto, Acknowledgment ack) {
        try {
            Long userId = dto.getUserId();
            String keyword = dto.getKeyword();
            Date now = new Date();
            UserSearchHistoryPo po = new UserSearchHistoryPo(snowflakeIdWorker.nextId(), userId, keyword, now, now);
            userSearchHistoryMapper.addUserSearchHistoryIfAbsentUpdateTime(po);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("搜索历史存证异常: {}", e.getMessage());
        }
    }

    // ======================== 核心逻辑处理部分 ========================

    private boolean updateBehaviorSequence(String userId, String mediaId, String behaviorType) {
        try {
            if (userId == null || mediaId == null) return true;

            // 1. 获取语义向量 (K) 与 业务特征向量 (V)
            byte[] mediaVector512 = documentVectorMediaService.getVectorByMediaId(mediaId);
            if (mediaVector512 == null) return true;

            float[] businessMeta = getBusinessMetaVector(mediaId);
            byte[] vSnapshotVector = combineVectorWithMeta(mediaVector512, businessMeta);

            // 2. 存入行为记录 (数据库)
            saveBehaviorRecord(userId, mediaId, behaviorType);

            String kGlobal = K_GLOBAL_PREFIX + userId;
            String vGlobal = V_GLOBAL_PREFIX + userId;
            String counterKey = COUNTER_PREFIX + userId;

            // 3. 直接存入 Global 池并进行定长裁剪 (保持最新的 24 条)
            byteRedisTemplate.opsForList().rightPush(kGlobal, mediaVector512);
            byteRedisTemplate.opsForList().trim(kGlobal, -GLOBAL_CAPACITY, -1);

            byteRedisTemplate.opsForList().rightPush(vGlobal, vSnapshotVector);
            byteRedisTemplate.opsForList().trim(vGlobal, -GLOBAL_CAPACITY, -1);

            // 4. 步进计数并触发演化逻辑
            Long count = stringRedisTemplate.opsForValue().increment(counterKey);
            stringRedisTemplate.expire(counterKey, 7, TimeUnit.DAYS); // 设置计数器 7 天过期

            if (count != null && count % TRIGGER_STEP == 0) {
                processEvolution(userId, kGlobal, vGlobal);
            }

            // 设置池子过期时间
            expireKeys(kGlobal, vGlobal);
            return true;
        } catch (Exception e) {
            log.error("画像闭环更新失败: {}", e.getMessage());
            return false;
        }
    }

    private void processEvolution(String userId, String kGlobal, String vGlobal) {
        // 获取 Global 池内容
        List<byte[]> globalKList = byteRedisTemplate.opsForList().range(kGlobal, 0, -1);
        long globalSize = (globalKList == null) ? 0 : globalKList.size();

        // 必须满足 24 条数据才发送给 Python
        if (globalSize < (long) GLOBAL_CAPACITY) {
            log.info("⏳ 用户 [{}] Global 池未满 (size={}/24)，跳过本次演化", userId, globalSize);
            return;
        }

        log.info("🧠 用户 [{}] 满足触发条件 (计数达标且池子已满)，执行画像融合", userId);
        try {
            List<byte[]> globalVList = byteRedisTemplate.opsForList().range(vGlobal, 0, -1);

            // 获取当前 Query 向量
            float[] currentQuery = documentUserProfileService.getUserQueryVector(userId);

            // 获取长期兴趣质心
            List<InterestCentroid> longTermCentroids = documentUserProfileService.getInterestCentroids(userId);

            // 调用 gRPC 发送 24 个向量及业务标签
            float[] evolvedUserVec = invokePythonInterestFusion(currentQuery, globalKList, globalVList, longTermCentroids);

            if (evolvedUserVec != null) {
                documentUserProfileService.updateUserProfile(userId, evolvedUserVec, new Date());
                log.info("✅ 用户 [{}] 画像进化成功", userId);
            }
        } catch (Exception e) {
            log.error("❌ gRPC 演化链路异常: ", e);
        }
    }

    private float[] invokePythonInterestFusion(float[] query, List<byte[]> sK, List<byte[]> sV, List<InterestCentroid> lC) {
        UserInterestRequest.Builder builder = UserInterestRequest.newBuilder();

        if (query != null) {
            for (float f : query) builder.addQueryEmbedding(f);
        }

        // 发送 Global 池中的 24 条数据
        if (sK != null && sV != null) {
            int limit = Math.min(sK.size(), sV.size());
            for (int i = 0; i < limit; i++) {
                ShortTermItem item = ShortTermItem.newBuilder()
                        .addAllEmbedding(Floats.asList(bytesToFloats(sK.get(i))))
                        .addAllBizLabels(Floats.asList(bytesToFloats(sV.get(i), VECTOR_DIM, BIZ_DIM)))
                        .build();
                builder.addShortTerm(item);
            }
        }

        if (lC != null) {
            builder.addAllLongTerm(lC);
        }

        try {
            UserInterestResponse response = userModelStub.getUserInterestVector(builder.build());
            if ("ok".equals(response.getStatus())) {
                return Floats.toArray(response.getUserVectorList());
            }
        } catch (Exception e) {
            log.error("gRPC 调用失败: {}", e.getMessage());
        }
        return null;
    }

    // ======================== 工具方法 ========================

    private float[] bytesToFloats(byte[] bytes) {
        if (bytes == null) return new float[VECTOR_DIM];
        FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] floats = new float[fb.remaining()];
        fb.get(floats);
        return floats;
    }

    private float[] bytesToFloats(byte[] bytes, int offset, int length) {
        if (bytes == null || bytes.length < (offset + length) * 4) return new float[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(offset * 4);
        float[] result = new float[length];
        for (int i = 0; i < length; i++) result[i] = buffer.getFloat();
        return result;
    }

    private float[] getBusinessMetaVector(String mediaId) {
        float[] meta = new float[4];
        meta[0] = 0.0f; // placeholder
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