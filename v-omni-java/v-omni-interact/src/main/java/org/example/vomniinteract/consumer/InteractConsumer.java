package org.example.vomniinteract.consumer;

import com.google.common.primitives.Floats;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.CommentMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.po.*;
import org.example.vomniinteract.service.*;
import org.example.vomniinteract.grpc.*; // 确保导入了 gRPC 生成的类
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 互动服务消费者
 * 职能：处理点赞、收藏、评论逻辑，并维护用户行为滑动窗口触发 gRPC 画像进化
 */
@Component
@Slf4j
public class InteractConsumer {

    @Resource
    private LikeMapper likeMapper;
    @Resource
    private CollectionMapper collectionMapper;
    @Resource
    private CommentMapper commentMapper;
    @Resource
    private CommentLikeMapper commentLikeMapper;
    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;
    @Resource
    private DocumentCommentService documentCommentService;
    @Resource
    private DocumentMediaInteractionService documentMediaInteractionService;
    @Resource
    private DocumentVectorMediaService documentVectorMediaService;
    @Resource
    private DocumentUserProfileService documentUserProfileService;

    // ================= 核心演化组件注入 =================
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisTemplate<String, byte[]> byteRedisTemplate;
    @Resource
    private UserModelServiceGrpc.UserModelServiceBlockingStub userModelStub;

    // Redis Key 定义 (与 SearchConsumer 保持一致)
    private final static String K_GLOBAL_PREFIX = "behavior:global:k:user_id:";
    private final static String V_GLOBAL_PREFIX = "behavior:global:v:user_id:";
    private final static String COUNTER_PREFIX = "behavior:counter:user_id:";

    // 演化参数
    private final static int GLOBAL_CAPACITY = 24;
    private final static int TRIGGER_STEP = 4;
    private final static int VECTOR_DIM = 512;
    private final static int BIZ_DIM = 4;

    /**
     * 1. 视频点赞消费者
     */
    @Transactional
    @KafkaListener(topics = "database-like-topic", groupId = "v-omni-interaction-group")
    public void databaseLikeTopicConsume(List<DoLikeDto> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 1. 行为折叠（你原有的逻辑）
        Map<String, DoLikeDto> foldedMap = new LinkedHashMap<>();
        for (DoLikeDto msg : messages) {
            String key = msg.getUserId() + ":" + msg.getMediaId();
            foldedMap.put(key, msg);
        }

        // 2. 准备 ES 批量更新的数据结构：mediaId -> (fieldName -> changeAmount)
        Map<String, Map<String, Integer>> esUpdateMap = new HashMap<>();

        foldedMap.forEach((key, finalMsg) -> {
            Long userId = Long.parseLong(finalMsg.getUserId());
            Long mediaId = Long.parseLong(finalMsg.getMediaId());
            String mIdStr = finalMsg.getMediaId();

            if ("like".equals(finalMsg.getAction())) {
                // --- 数据库操作 ---
                LikePo likePo = LikePo.builder()
                        .id(snowflakeIdWorker.nextId())
                        .userId(userId)
                        .mediaId(mediaId)
                        .createTime(finalMsg.getCreateTime())
                        .build();
                int rows = likeMapper.insertLike(likePo);

                // --- ES 计数累加逻辑 ---
                // 只有当数据库插入成功（即不是重复点赞）时，才增加 ES 计数
                if (rows > 0) {
                    esUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                            .merge("like_count", 1, Integer::sum);
                }

            } else {
                // --- 数据库操作 ---
                int rows = likeMapper.deleteLike(userId, mediaId);

                // --- ES 计数扣减逻辑 ---
                // 只有当数据库实际删除了记录（即之前确实点过赞）时，才减少 ES 计数
                if (rows > 0) {
                    esUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                            .merge("like_count", -1, Integer::sum);
                }
            }
        });

        // 3. 调用你的 ES 批量更新函数
        if (!esUpdateMap.isEmpty()) {
            documentVectorMediaService.bulkUpdateCounts(esUpdateMap);
        }
    }

    /**
     * 2. 视频收藏消费者
     */
    @Transactional
    @KafkaListener(topics = "database-collection-topic", groupId = "v-omni-interaction-group")
    public void databaseCollectionTopicConsume(List<DoCollectionDto> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 1. 行为折叠（你原有的逻辑）
        Map<String, DoCollectionDto> foldedMap = new LinkedHashMap<>();
        for (DoCollectionDto msg : messages) {
            String key = msg.getUserId() + ":" + msg.getMediaId();
            foldedMap.put(key, msg);
        }

        // 2. 准备 ES 批量更新的数据结构：mediaId -> (fieldName -> changeAmount)
        Map<String, Map<String, Integer>> esUpdateMap = new HashMap<>();

        foldedMap.forEach((key, finalMsg) -> {
            Long userId = Long.parseLong(finalMsg.getUserId());
            Long mediaId = Long.parseLong(finalMsg.getMediaId());
            String mIdStr = finalMsg.getMediaId();

            if ("collection".equals(finalMsg.getAction())) {
                // --- 数据库操作 ---
                CollectionPo collectionPo = CollectionPo.builder()
                        .id(snowflakeIdWorker.nextId())
                        .userId(userId)
                        .mediaId(mediaId)
                        .createTime(finalMsg.getCreateTime())
                        .build();
                int rows = collectionMapper.insertCollection(collectionPo);

                // --- ES 计数累加逻辑 ---
                // 只有当数据库插入成功（即不是重复点赞）时，才增加 ES 计数
                if (rows > 0) {
                    esUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                            .merge("collection_count", 1, Integer::sum);
                }

            } else {
                // --- 数据库操作 ---
                int rows = collectionMapper.deleteCollection(userId, mediaId);

                // --- ES 计数扣减逻辑 ---
                // 只有当数据库实际删除了记录（即之前确实点过赞）时，才减少 ES 计数
                if (rows > 0) {
                    esUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                            .merge("collection_count", -1, Integer::sum);
                }
            }
        });

        // 3. 调用你的 ES 批量更新函数
        if (!esUpdateMap.isEmpty()) {
            documentVectorMediaService.bulkUpdateCounts(esUpdateMap);
        }
    }

    /**
     * 3. 评论点赞消费者
     */
    @Transactional
    @KafkaListener(topics = "database-comment-like-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentLikeTopicConsume(List<DoCommentLikeDto> messages) {
        Map<Long, Integer> esLikeUpdates = new HashMap<>();
        for (DoCommentLikeDto m : messages) {
            Long uId = Long.parseLong(m.getUserId());
            Long cId = Long.parseLong(m.getCommentId());
            boolean isAdd = !"0".equals(m.getAction());
            if (isAdd) {
                commentLikeMapper.insertCommentLike(snowflakeIdWorker.nextId(), cId, uId, m.getCreateTime());
            } else {
                commentLikeMapper.deleteCommentLike(cId, uId);
            }
            esLikeUpdates.merge(cId, isAdd ? 1 : -1, Integer::sum);
        }
        if (!esLikeUpdates.isEmpty()) {
            documentCommentService.bulkUpdateCommentLikeCount(esLikeUpdates);
        }
    }

    /**
     * 4. 评论发布/删除消费者
     */
    @Transactional
    @KafkaListener(topics = "database-comment-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentTopicConsume(List<DoCommentDto> messages) {
        for(DoCommentDto m : messages) {
            String action = m.getAction();
            if ("comment".equals(action)) {
                CommentPo commentPo = CommentPo.builder()
                        .id(snowflakeIdWorker.nextId())
                        .userId(Long.parseLong(m.getUserId()))
                        .mediaId(Long.parseLong(m.getMediaId()))
                        .rootId(Long.parseLong(m.getRootId()))
                        .parentId(Long.parseLong(m.getParentId()))
                        .createTime(m.getCreateTime())
                        .content(m.getContent())
                        .build();
                commentMapper.insertComment(commentPo);
            }
            else {
                if(m.getRootId().equals("0")) {
                    commentMapper.deleteRepliesByRootId(Long.parseLong(m.getId()));
                }
                commentMapper.deleteCommentById(Long.parseLong(m.getId()));
            }
        }
    }

    /**
     * 5. 互动数据统计消费者
     */
    @KafkaListener(topics = "interaction-count-topic", groupId = "v-omni-interaction-group")
    public void handleInteractionCountConsume(List<InteractionCountDto> messages) {
        if (messages.isEmpty()) return;
        Map<String, Map<String, Integer>> bulkUpdates = new HashMap<>();
        for (InteractionCountDto m : messages) {
            String field = switch (m.getType()) {
                case "LIKE" -> "like_count";
                case "COMMENT" -> "comment_count";
                case "COLLECT" -> "collect_count";
                default -> null;
            };
            if (field != null) {
                bulkUpdates.computeIfAbsent(m.getMediaId(), k -> new HashMap<>())
                        .merge(field, m.getChange(), Integer::sum);
            }
        }
        documentVectorMediaService.bulkUpdateCounts(bulkUpdates);
    }

    // =========================================================
    // =============== 核心演化逻辑：行为池维护与演化 ================
    // =========================================================

    private void updateBehaviorSequence(String userId, String mediaId, String behaviorType) {
        try {
            // 1. 获取视频语义向量 (K) 与 业务特征 (V)
            byte[] mediaVector512 = documentVectorMediaService.getVectorByMediaId(mediaId);
            if (mediaVector512 == null) return;

            float[] businessMeta = getBusinessMetaVector(mediaId, behaviorType);
            byte[] vSnapshotVector = combineVectorWithMeta(mediaVector512, businessMeta);

            String kGlobal = K_GLOBAL_PREFIX + userId;
            String vGlobal = V_GLOBAL_PREFIX + userId;
            String counterKey = COUNTER_PREFIX + userId;

            // 2. 更新 Redis 滑动窗口池 (定长 24)
            byteRedisTemplate.opsForList().rightPush(kGlobal, mediaVector512);
            byteRedisTemplate.opsForList().trim(kGlobal, -GLOBAL_CAPACITY, -1);

            byteRedisTemplate.opsForList().rightPush(vGlobal, vSnapshotVector);
            byteRedisTemplate.opsForList().trim(vGlobal, -GLOBAL_CAPACITY, -1);

            // 3. 步进计数，每 4 次行为触发一次深度演化
            Long count = stringRedisTemplate.opsForValue().increment(counterKey);
            stringRedisTemplate.expire(counterKey, 7, TimeUnit.DAYS);

            if (count != null && count % TRIGGER_STEP == 0) {
                processEvolution(userId, kGlobal, vGlobal);
            }

            // 设置池子有效期
            byteRedisTemplate.expire(kGlobal, 7, TimeUnit.DAYS);
            byteRedisTemplate.expire(vGlobal, 7, TimeUnit.DAYS);

            log.info("📊 用户 {} 行为序列已更新: {}", userId, behaviorType);
        } catch (Exception e) {
            log.error("❌ 画像演化链路异常: {}", e.getMessage());
        }
    }

    private void processEvolution(String userId, String kGlobal, String vGlobal) {
        List<byte[]> globalKList = byteRedisTemplate.opsForList().range(kGlobal, 0, -1);
        if (globalKList == null || globalKList.size() < GLOBAL_CAPACITY) {
            log.info("⏳ 用户 {} 池子未满 ({} / 24)，跳过深度演化", userId, globalKList == null ? 0 : globalKList.size());
            return;
        }

        try {
            List<byte[]> globalVList = byteRedisTemplate.opsForList().range(vGlobal, 0, -1);
            float[] currentQuery = documentUserProfileService.getUserQueryVector(userId);
            List<InterestCentroid> longTermCentroids = documentUserProfileService.getInterestCentroids(userId);

            // 构建 gRPC 请求
            UserInterestRequest.Builder builder = UserInterestRequest.newBuilder();
            if (currentQuery != null) {
                for (float f : currentQuery) builder.addQueryEmbedding(f);
            }

            for (int i = 0; i < globalKList.size(); i++) {
                ShortTermItem item = ShortTermItem.newBuilder()
                        .addAllEmbedding(Floats.asList(bytesToFloats(globalKList.get(i))))
                        .addAllBizLabels(Floats.asList(bytesToFloats(globalVList.get(i), VECTOR_DIM, BIZ_DIM)))
                        .build();
                builder.addShortTerm(item);
            }

            if (longTermCentroids != null) builder.addAllLongTerm(longTermCentroids);

            // 调用 Python 模型
            UserInterestResponse response = userModelStub.getUserInterestVector(builder.build());
            if ("ok".equals(response.getStatus())) {
                float[] evolvedUserVec = Floats.toArray(response.getUserVectorList());
                documentUserProfileService.updateUserProfile(userId, evolvedUserVec, new Date());
                log.info("✅ 互动链路：用户 [{}] 画像演化成功", userId);
            }
        } catch (Exception e) {
            log.error("❌ gRPC 演化请求失败: ", e);
        }
    }

    // ======================== 工具辅助方法 ========================

    private float[] getBusinessMetaVector(String mediaId, String behaviorType) {
        float[] meta = new float[4];
        // 第 0 位作为权重标识：点赞收藏给予比搜索更高的固定分值
        meta[0] = behaviorType.equals("COLLECT") ? 2.0f : 1.5f;
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

    private float[] bytesToFloats(byte[] bytes) {
        FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] floats = new float[fb.remaining()];
        fb.get(floats);
        return floats;
    }

    private float[] bytesToFloats(byte[] bytes, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(offset * 4);
        float[] result = new float[length];
        for (int i = 0; i < length; i++) result[i] = buffer.getFloat();
        return result;
    }
}