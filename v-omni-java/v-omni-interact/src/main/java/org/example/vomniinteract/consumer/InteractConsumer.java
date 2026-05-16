package org.example.vomniinteract.consumer;

import io.lettuce.core.KeyValue;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.CommentMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.po.*;
import org.example.vomniinteract.service.*;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.nd4j.shade.protobuf.common.primitives.Floats;
import org.example.vomniinteract.grpc.*;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Resource
    private UserModelServiceGrpc.UserModelServiceBlockingStub userModelStub;
    @Resource
    private DocumentUserBehaviorHistoryService documentUserBehaviorHistoryService;


    // ================= 核心演化组件注入 =================
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisTemplate<String, byte[]> byteRedisTemplate;
    @Resource
    private RedisCommands<String, String> redisCommands; // Lettuce 的 RedisTimeSeries sync API

    private static final String TS_MEDIA_LIKE_PREFIX = "interact:ts:media:like:";
    private static final String TS_MEDIA_COLLECTION_PREFIX = "interact:ts:media:collection:";
    private static final String TS_MEDIA_COMMENT_PREFIX = "interact:ts:media:comment:";

    // Redis Key 定义 (与 SearchConsumer 保持一致)
    private final static String K_GLOBAL_PREFIX = "behavior:global:k:user_id:";
    private final static String V_GLOBAL_PREFIX = "behavior:global:v:user_id:";
    private final static String COUNTER_PREFIX = "behavior:counter:user_id:";

    private final static String MEDIA_INFO_PREFIX = "interact:media:info:";

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

        // 折叠重复行为
        Map<String, DoLikeDto> foldedMap = new LinkedHashMap<>();
        for (DoLikeDto msg : messages) {
            String key = msg.getUserId() + ":" + msg.getMediaId();
            foldedMap.put(key, msg);
        }

        List<LikePo> dbAddList = new ArrayList<>();
        List<LikePo> dbDeleteList = new ArrayList<>();
        Map<String, Map<String, Integer>> countUpdateMap = new HashMap<>();
        List<DocumentMediaInteractionPo> esInteractionsToAdd = new ArrayList<>();
        List<String> esInteractionsToDelete = new ArrayList<>();

        // 遍历折叠后的消息
        foldedMap.forEach((key, finalMsg) -> {
            Long userId = Long.parseLong(finalMsg.getUserId());
            Long mediaId = Long.parseLong(finalMsg.getMediaId());
            String mIdStr = finalMsg.getMediaId();
            String uIdStr = finalMsg.getUserId();
            Date date = finalMsg.getCreateTime();
            String interactionDocId = userId + "_" + mediaId + "_like";

            int tsValue = 0; // RedisTimeSeries 增量
            if ("like".equals(finalMsg.getAction())) {
                dbAddList.add(LikePo.builder()
                        .id(snowflakeIdWorker.nextId())
                        .userId(userId)
                        .mediaId(mediaId)
                        .createTime(date).build());

                countUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                        .merge("like_count", 1, Integer::sum);

                tsValue = 1; // 点赞 +1

                esInteractionsToAdd.add(DocumentMediaInteractionPo.builder()
                        .id(interactionDocId)
                        .userId(uIdStr)
                        .mediaId(mIdStr)
                        .behavior("like")
                        .createTime(date).build());

                updateBehaviorSequence(uIdStr,mIdStr,"like");

            } else {
                dbDeleteList.add(LikePo.builder().userId(userId).mediaId(mediaId).build());

                countUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                        .merge("like_count", -1, Integer::sum);

                tsValue = -1; // 取消点赞 -1

                esInteractionsToDelete.add(interactionDocId);

                updateBehaviorSequence(uIdStr,mIdStr,"unlike");
            }

            // =======================
            // RedisTimeSeries 记录
            // =======================
            try {
                String tsKey = TS_MEDIA_LIKE_PREFIX + mediaId;
                long tsTimestamp = date.getTime();

                // 1. 尝试创建时序 Key（独立 try-catch，专门用于防重）
                try {
                    stringRedisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Void>() {
                        @Override
                        public Void doInRedis(org.springframework.data.redis.connection.RedisConnection connection)
                                throws org.springframework.dao.DataAccessException {
                            connection.execute("TS.CREATE", new byte[][]{
                                    tsKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "RETENTION".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    String.valueOf(7 * 24 * 60 * 60 * 1000L).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "DUPLICATE_POLICY".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "SUM".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            });
                            return null;
                        }
                    });
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("key already exists")) {
                        log.debug("RedisTimeSeries Key 已存在，跳过创建: {}", tsKey);
                    } else {
                        log.warn("RedisTimeSeries 创建引发非预期异常: {}", e.getMessage());
                    }
                }

                // 2. 核心修复：使用 Lua 脚本，但是【极其关键】地指定返回值为 Long.class
                // 并且采用 GenericToStringSerializer<Long>(Long.class) 显式告诉 Spring 将结果反序列化为 Long

                // 构造专门处理 Long 的 Lua 脚本执行器
                org.springframework.data.redis.core.script.DefaultRedisScript<Long> redisScript =
                        new org.springframework.data.redis.core.script.DefaultRedisScript<>();
                redisScript.setScriptText("return redis.call('TS.INCRBY', KEYS[1], ARGV[1], 'TIMESTAMP', ARGV[2])");
                redisScript.setResultType(Long.class); // 👈 明确声明返回 Long 响应

                // 核心：使用能够正确解析长整型数字的序列化器
                org.springframework.data.redis.serializer.GenericToStringSerializer<Long> longSerializer =
                        new org.springframework.data.redis.serializer.GenericToStringSerializer<>(Long.class);

                Long resultTimestamp = stringRedisTemplate.execute(
                        redisScript,
                        stringRedisTemplate.getStringSerializer(), // KEYS[1] 的序列化器 (String)
                        longSerializer,                            // 返回值的反序列化器 (转成 Long)
                        java.util.Collections.singletonList(tsKey),// KEYS 参数列表
                        String.valueOf(tsValue),              // ARGV[1]
                        String.valueOf(tsTimestamp)                // ARGV[2]
                );

                log.info("RedisTimeSeries 写入成功: mediaId={}, 返回时间戳={}", mediaId, resultTimestamp);

            } catch (Exception e) {
                log.error("RedisTimeSeries 写入自增失败: mediaId={}, 原因={}", mediaId, e.getMessage());
                throw e; // 抛出给 Kafka 让其重试
            }
        });

        // =======================
        // 批量操作 DB 和 ES
        // =======================
        if (!dbAddList.isEmpty()) likeMapper.insertLikeBatch(dbAddList);
        if (!dbDeleteList.isEmpty()) likeMapper.deleteLikeBatch(dbDeleteList);

        if (!countUpdateMap.isEmpty()) documentVectorMediaService.bulkUpdateCounts(countUpdateMap);
        if (!esInteractionsToAdd.isEmpty() || !esInteractionsToDelete.isEmpty())
            documentMediaInteractionService.bulkSyncInteractions(esInteractionsToAdd, esInteractionsToDelete);

        log.info("V-Omni 互动补偿完成: DB新增{}, DB删除{}, ES计数更新{}, ES记录变更{}",
                dbAddList.size(), dbDeleteList.size(), countUpdateMap.size(), esInteractionsToAdd.size() + esInteractionsToDelete.size());
    }

    /**
     * 2. 视频收藏消费者
     */
    @Transactional
    @KafkaListener(topics = "database-collection-topic", groupId = "v-omni-interaction-group")
    public void databaseCollectionTopicConsume(List<DoCollectionDto> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 1. 行为折叠：保证同一用户对同一视频，批量中只保留最后一次状态
        // 注意：这里类型改为 DoCollectionDto
        Map<String, DoCollectionDto> foldedMap = new LinkedHashMap<>();
        for (DoCollectionDto msg : messages) {
            String key = msg.getUserId() + ":" + msg.getMediaId();
            foldedMap.put(key, msg);
        }

        // 准备数据集合
        List<CollectionPo> dbAddList = new ArrayList<>();
        List<CollectionPo> dbDeleteList = new ArrayList<>();

        Map<String, Map<String, Integer>> countUpdateMap = new HashMap<>(); // 更新视频总数索引 (vector_media_index)
        List<DocumentMediaInteractionPo> esInteractionsToAdd = new ArrayList<>(); // 增加个人行为记录 (interaction_index)
        List<String> esInteractionsToDelete = new ArrayList<>(); // 删除个人行为记录

        // 2. 解析折叠后的消息
        foldedMap.forEach((key, finalMsg) -> {
            Long userId = Long.parseLong(finalMsg.getUserId());
            Long mediaId = Long.parseLong(finalMsg.getMediaId());
            String mIdStr = finalMsg.getMediaId();
            String uIdStr = finalMsg.getUserId();
            Date date = finalMsg.getCreateTime();

            // 关键：后缀改为 _collect，防止覆盖点赞记录
            String interactionDocId = userId + "_" + mediaId + "_collect";

            int tsValue = 0;
            if ("collect".equals(finalMsg.getAction())) {
                // 准备 DB 批量插入 (CollectionPo)
                dbAddList.add(CollectionPo.builder()
                        .id(snowflakeIdWorker.nextId())
                        .userId(userId)
                        .mediaId(mediaId)
                        .createTime(date).build());

                // 准备 ES 计数更新 (针对 vector_media_index 里的 collect_count 字段)
                countUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                        .merge("collect_count", 1, Integer::sum);

                tsValue = 1;

                // 准备 ES 个人记录新增 (DocumentMediaInteractionPo)
                esInteractionsToAdd.add(DocumentMediaInteractionPo.builder()
                        .id(interactionDocId)
                        .userId(uIdStr)
                        .mediaId(mIdStr)
                        .behavior("collect") // 标识为收藏
                        .createTime(date).build());

                updateBehaviorSequence(uIdStr,mIdStr,"collection");
            } else {
                // 准备 DB 批量删除
                dbDeleteList.add(CollectionPo.builder().userId(userId).mediaId(mediaId).build());

                // 准备 ES 计数更新 (-1)
                countUpdateMap.computeIfAbsent(mIdStr, k -> new HashMap<>())
                        .merge("collect_count", -1, Integer::sum);

                tsValue = -1;
                // 准备 ES 个人记录删除
                esInteractionsToDelete.add(interactionDocId);
                updateBehaviorSequence(uIdStr,mIdStr,"delete_collection");
            }

            try {
                String tsKey = TS_MEDIA_COLLECTION_PREFIX + mediaId;
                long tsTimestamp = date.getTime();

                // 1. 尝试创建时序 Key（独立 try-catch，专门用于防重）
                try {
                    stringRedisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Void>() {
                        @Override
                        public Void doInRedis(org.springframework.data.redis.connection.RedisConnection connection)
                                throws org.springframework.dao.DataAccessException {
                            connection.execute("TS.CREATE", new byte[][]{
                                    tsKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "RETENTION".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    String.valueOf(7 * 24 * 60 * 60 * 1000L).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "DUPLICATE_POLICY".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "SUM".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            });
                            return null;
                        }
                    });
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("key already exists")) {
                        log.debug("RedisTimeSeries Key 已存在，跳过创建: {}", tsKey);
                    } else {
                        log.warn("RedisTimeSeries 创建引发非预期异常: {}", e.getMessage());
                    }
                }

                // 2. 核心修复：使用 Lua 脚本，但是【极其关键】地指定返回值为 Long.class
                // 并且采用 GenericToStringSerializer<Long>(Long.class) 显式告诉 Spring 将结果反序列化为 Long

                // 构造专门处理 Long 的 Lua 脚本执行器
                org.springframework.data.redis.core.script.DefaultRedisScript<Long> redisScript =
                        new org.springframework.data.redis.core.script.DefaultRedisScript<>();
                redisScript.setScriptText("return redis.call('TS.INCRBY', KEYS[1], ARGV[1], 'TIMESTAMP', ARGV[2])");
                redisScript.setResultType(Long.class); // 👈 明确声明返回 Long 响应

                // 核心：使用能够正确解析长整型数字的序列化器
                org.springframework.data.redis.serializer.GenericToStringSerializer<Long> longSerializer =
                        new org.springframework.data.redis.serializer.GenericToStringSerializer<>(Long.class);

                Long resultTimestamp = stringRedisTemplate.execute(
                        redisScript,
                        stringRedisTemplate.getStringSerializer(), // KEYS[1] 的序列化器 (String)
                        longSerializer,                            // 返回值的反序列化器 (转成 Long)
                        java.util.Collections.singletonList(tsKey),// KEYS 参数列表
                        String.valueOf(tsValue),              // ARGV[1]
                        String.valueOf(tsTimestamp)                // ARGV[2]
                );

                log.info("RedisTimeSeries 写入成功: mediaId={}, 返回时间戳={}", mediaId, resultTimestamp);

            } catch (Exception e) {
                log.error("RedisTimeSeries 写入自增失败: mediaId={}, 原因={}", mediaId, e.getMessage());
                throw e; // 抛出给 Kafka 让其重试
            }
        });

        // 3. 批量执行数据库操作 (调用 CollectionMapper)
        if (!dbAddList.isEmpty()) {
            collectionMapper.insertCollectionBatch(dbAddList);
        }
        if (!dbDeleteList.isEmpty()) {
            collectionMapper.deleteCollectionBatch(dbDeleteList);
        }

        // 4. 批量同步 ES 索引
        // A. 更新视频全局索引（针对收藏总数字段）
        if (!countUpdateMap.isEmpty()) {
            documentVectorMediaService.bulkUpdateCounts(countUpdateMap);
        }
        // B. 同步用户行为索引（个人收藏列表）
        if (!esInteractionsToAdd.isEmpty() || !esInteractionsToDelete.isEmpty()) {
            documentMediaInteractionService.bulkSyncInteractions(esInteractionsToAdd, esInteractionsToDelete);
        }

        log.info("V-Omni 收藏补偿完成: DB新增{}, DB删除{}, ES计数更新{}, ES记录变更{}",
                dbAddList.size(), dbDeleteList.size(), countUpdateMap.size(), esInteractionsToAdd.size() + esInteractionsToDelete.size());
    }

    /**
     * 3. 评论点赞消费者
     */
    @Transactional
    @KafkaListener(topics = "database-comment-like-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentLikeTopicConsume(List<DoCommentLikeDto> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 1. 行为折叠：同一用户对同一评论的点赞操作，只保留最后一次状态
        Map<String, DoCommentLikeDto> foldedMap = new LinkedHashMap<>();
        for (DoCommentLikeDto msg : messages) {
            String key = msg.getUserId() + ":" + msg.getCommentId();
            foldedMap.put(key, msg);
        }

        List<CommentLikePo> dbAddList = new ArrayList<>();
        List<CommentLikePo> dbDeleteList = new ArrayList<>();

        // 记录 ES 需要增减的数量 (Key: commentId, Value: changeCount)
        Map<Long, Integer> countUpdateMap = new HashMap<>();

        // 2. 解析折叠后的最终行为
        foldedMap.forEach((key, finalMsg) -> {
            Long userId = Long.parseLong(finalMsg.getUserId());
            Long commentId = Long.parseLong(finalMsg.getCommentId());

            if ("like".equals(finalMsg.getAction())) {
                // 构造插入的 PO
                CommentLikePo po = new CommentLikePo();
                po.setId(snowflakeIdWorker.nextId()); // 雪花算法生成 ID
                po.setUserId(userId);
                po.setCommentId(commentId);
                po.setCreateTime(finalMsg.getCreateTime());
                dbAddList.add(po);

                // ES 计数 +1
                countUpdateMap.merge(commentId, 1, Integer::sum);
            } else {
                // 构造删除的 PO (只需要关联键)
                CommentLikePo po = new CommentLikePo();
                po.setUserId(userId);
                po.setCommentId(commentId);
                dbDeleteList.add(po);

                // ES 计数 -1
                countUpdateMap.merge(commentId, -1, Integer::sum);
            }
        });

        // 3. 执行 MySQL 批量操作
        if (!dbAddList.isEmpty()) {
            commentLikeMapper.insertCommentLikeBatch(dbAddList);
        }
        if (!dbDeleteList.isEmpty()) {
            commentLikeMapper.deleteCommentLikeBatch(dbDeleteList);
        }

        // 4. 执行 ES 批量计数更新
        if (!countUpdateMap.isEmpty()) {
            // 调用你现成的 service 方法
            documentCommentService.bulkUpdateCommentLikeCount(countUpdateMap);
        }

        log.info("V-Omni 评论点赞处理完成: DB新增{}条, DB取消{}条, ES更新涉及{}条评论",
                dbAddList.size(), dbDeleteList.size(), countUpdateMap.size());
    }

    /**
     * 4. 评论发布/删除消费者
     */
    @Transactional
    @KafkaListener(topics = "database-comment-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentTopicConsume(List<DoCommentDto> messages) {
        if (messages == null || messages.isEmpty()) return;

        // --- 数据准备桶 ---
        List<CommentPo> dbSaveList = new ArrayList<>(); // MySQL 插入
        List<DocumentCommentPo> esSaveList = new ArrayList<>(); // ES 插入

        List<Long> allDeleteIds = new ArrayList<>(); // 待删除的 ID 集合 (含根和回复)
        List<Long> cascadeRootIds = new ArrayList<>(); // 待级联删除的根 ID 集合
        List<DoCommentDto> deleteMessagesForEs = new ArrayList<>(); // 传给你的 ES service

        for (DoCommentDto m : messages) {
            String action = m.getAction();
            Long cId = Long.parseLong(m.getId());
            Long rId = Long.parseLong(m.getRootId());
            String mediaId = m.getMediaId();
            String userId = m.getUserId();
            Date date = m.getCreateTime();
            int tsValue = 0;

            if ("comment".equals(action)) {
                // 1. 构造 PO 给 MySQL
                CommentPo po = CommentPo.builder()
                        .id(cId) // 假设 ID 已经在上层生成好随 DTO 传来，或者在此处生成
                        .userId(Long.parseLong(m.getUserId()))
                        .mediaId(Long.parseLong(m.getMediaId()))
                        .rootId(rId)
                        .parentId(Long.parseLong(m.getParentId()))
                        .createTime(m.getCreateTime())
                        .content(m.getContent())
                        .build();
                dbSaveList.add(po);

                // 2. 构造 PO 给 ES
                // 注意：此处你可以根据需要从 Redis 补全昵称/头像，保持搜索索引的丰富性
                DocumentCommentPo esPo = DocumentCommentPo.builder()
                        .commentId(cId)
                        .userId(Long.parseLong(m.getUserId()))
                        .mediaId(Long.parseLong(m.getMediaId()))
                        .rootId(rId)
                        .parentId(Long.parseLong(m.getParentId()))
                        .content(m.getContent())
                        .createTime(m.getCreateTime())
                        .build();
                esSaveList.add(esPo);

                tsValue = 1;

                updateBehaviorSequence(userId,mediaId,"comment");
            } else if ("delete".equals(action)) {
                allDeleteIds.add(cId);


                if (rId == 0) {
                    cascadeRootIds.add(cId);
                }
                deleteMessagesForEs.add(m);

                tsValue = -1;
                updateBehaviorSequence(userId,mediaId,"delete_comment");
            }

            try {
                String tsKey = TS_MEDIA_COMMENT_PREFIX + mediaId;
                long tsTimestamp = date.getTime();

                // 1. 尝试创建时序 Key（独立 try-catch，专门用于防重）
                try {
                    stringRedisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Void>() {
                        @Override
                        public Void doInRedis(org.springframework.data.redis.connection.RedisConnection connection)
                                throws org.springframework.dao.DataAccessException {
                            connection.execute("TS.CREATE", new byte[][]{
                                    tsKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "RETENTION".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    String.valueOf(7 * 24 * 60 * 60 * 1000L).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "DUPLICATE_POLICY".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "SUM".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            });
                            return null;
                        }
                    });
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("key already exists")) {
                        log.debug("RedisTimeSeries Key 已存在，跳过创建: {}", tsKey);
                    } else {
                        log.warn("RedisTimeSeries 创建引发非预期异常: {}", e.getMessage());
                    }
                }

                // 2. 核心修复：使用 Lua 脚本，但是【极其关键】地指定返回值为 Long.class
                // 并且采用 GenericToStringSerializer<Long>(Long.class) 显式告诉 Spring 将结果反序列化为 Long

                // 构造专门处理 Long 的 Lua 脚本执行器
                org.springframework.data.redis.core.script.DefaultRedisScript<Long> redisScript =
                        new org.springframework.data.redis.core.script.DefaultRedisScript<>();
                redisScript.setScriptText("return redis.call('TS.INCRBY', KEYS[1], ARGV[1], 'TIMESTAMP', ARGV[2])");
                redisScript.setResultType(Long.class); // 👈 明确声明返回 Long 响应

                // 核心：使用能够正确解析长整型数字的序列化器
                org.springframework.data.redis.serializer.GenericToStringSerializer<Long> longSerializer =
                        new org.springframework.data.redis.serializer.GenericToStringSerializer<>(Long.class);

                Long resultTimestamp = stringRedisTemplate.execute(
                        redisScript,
                        stringRedisTemplate.getStringSerializer(), // KEYS[1] 的序列化器 (String)
                        longSerializer,                            // 返回值的反序列化器 (转成 Long)
                        java.util.Collections.singletonList(tsKey),// KEYS 参数列表
                        String.valueOf(tsValue),              // ARGV[1]
                        String.valueOf(tsTimestamp)                // ARGV[2]
                );

                log.info("RedisTimeSeries 写入成功: mediaId={}, 返回时间戳={}", mediaId, resultTimestamp);

            } catch (Exception e) {
                log.error("RedisTimeSeries 写入自增失败: mediaId={}, 原因={}", mediaId, e.getMessage());
                throw e; // 抛出给 Kafka 让其重试
            }
        }

        // --- 执行 MySQL 批量操作 ---
        if (!dbSaveList.isEmpty()) {
            commentMapper.insertCommentBatch(dbSaveList);
        }
        if (!allDeleteIds.isEmpty()) {
            commentMapper.deleteCommentsBatch(allDeleteIds, cascadeRootIds);
        }

        // --- 执行 ES 批量操作 (调用你现有的 Service 方法) ---
        documentCommentService.bulkProcessComments(esSaveList, deleteMessagesForEs);

        log.info("评论批量处理完成: 插入{}条, 删除{}条(级联根{}条)",
                dbSaveList.size(), allDeleteIds.size(), cascadeRootIds.size());
    }


    // ======================== 核心逻辑处理部分 ========================

    private boolean updateBehaviorSequence(String userId, String mediaId, String behaviorType) {
        try {
            if (userId == null || mediaId == null) return true;

            // 1. 获取语义向量 (K) 与 业务特征向量 (V)
            List<Float> vVec = documentVectorMediaService.getVectorByMediaId(mediaId);

            // 512 floats * 4 bytes = 2048 bytes
            ByteBuffer buffer = ByteBuffer.allocate(512 * 4);

            float scaleFactor = 1.0f;
            switch (behaviorType) {
                case "like" -> scaleFactor = 3.0f;
                case "unlike" -> scaleFactor = 0.5f;
                case "collection" -> scaleFactor = 4.0f;
                case "delete_collection" -> scaleFactor = 0.3f;
                case "comment" -> scaleFactor = 2.0f;
                case "delete_comment" -> scaleFactor = 0.8f;
            }

            for (Float f : vVec) {
                buffer.putFloat(f * scaleFactor);
            }
            byte[] mediaVector512 = buffer.array();

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
        // 新的 meta 只有三维：点赞增长率、收藏增长率、评论增长率
        float[] meta = new float[3];

        meta[0] = getTimeSeriesGrowthRate(TS_MEDIA_LIKE_PREFIX + mediaId);       // 点赞增长率
        meta[1] = getTimeSeriesGrowthRate(TS_MEDIA_COLLECTION_PREFIX + mediaId); // 收藏增长率
        meta[2] = getTimeSeriesGrowthRate(TS_MEDIA_COMMENT_PREFIX + mediaId);    // 评论增长率

        return meta;
    }

    /**
     * 计算过去一天相对于前一天的增长率
     */
    private float getTimeSeriesGrowthRate(String tsKey) {
        try {
            long now = System.currentTimeMillis();
            long todayStart = getDayStartTs(now);
            long yesterdayStart = todayStart - 24 * 60 * 60 * 1000L;
            long yesterdayEnd = todayStart - 1;

            long todaySum = sumTimeSeries(tsKey, todayStart, now);
            long yesterdaySum = sumTimeSeries(tsKey, yesterdayStart, yesterdayEnd);

            if (yesterdaySum == 0) return 0.0f;
            return (float)(todaySum - yesterdaySum) / yesterdaySum;
        } catch (Exception e) {
            return 0.0f;
        }
    }

    /**
     * 获取当天 0 点时间戳
     */
    private long getDayStartTs(long ts) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * sum TS 时间序列指定区间
     */
    private long sumTimeSeries(String tsKey, long from, long to) {
        List<KeyValue<String, String>> range = redisCommands.dispatch(
                io.lettuce.core.protocol.CommandType.valueOf("TS.RANGE"),
                new io.lettuce.core.output.KeyValueListOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8)
                        .addKey(tsKey)
                        .add(from)
                        .add(to)
                        .add("AGGREGATION")
                        .add("SUM")
                        .add(3600) // 按小时聚合
        );

        return range.stream().mapToLong(kv -> Long.parseLong(kv.getValue())).sum();
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