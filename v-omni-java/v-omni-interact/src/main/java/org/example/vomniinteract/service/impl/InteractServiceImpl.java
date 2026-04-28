package org.example.vomniinteract.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.service.InteractService;
import org.example.vomniinteract.util.SecurityUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class InteractServiceImpl implements InteractService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Long> doOrCancelScript;

    @Resource
    private KafkaTemplate<String, DoLikeDto> doLikeKafkaTemplate;

    @Resource
    private KafkaTemplate<String, DoCollectionDto> doCollectionKafkaTemplate;

    @Resource
    private KafkaTemplate<String, DoCommentDto> commentKafkaTemplate;

    @Resource
    private KafkaTemplate<String, DoCommentLikeDto> commentLikeKafkaTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private LikeMapper likeMapper;

    @Resource
    private CommentLikeMapper commentLikeMapper;

    @Resource
    private CollectionMapper collectionMapper;

    @Resource
    private DefaultRedisScript<Long> userSlidingWindowScript;

    // ================== 基础交互常量 ==================
    private final static String USER_VECTOR_WINDOW_PREFIX = "user:vector_sequence:";
    private final static int WINDOW_SIZE = 15;

    private final static String MEDIA_LIKE_COUNTS_PREFIX = "interact:like:counts:media_id:";
    private final static String MEDIA_LIKE_USER_ID_SET_PREFIX = "interact:like:set:media_id:";
    private final static String LOCK_LIKE_KEY_PREFIX = "interact:like:lock:";

    private final static String MEDIA_COLLECTION_COUNTS_PREFIX = "interact:collection:counts:media_id:";
    private final static String MEDIA_COLLECTION_USER_ID_SET_PREFIX = "interact:collection:set:media_id:";
    private final static String LOCK_COLLECTION_KEY_PREFIX = "interact:collection:lock:";

    private final static String COMMENT_LIKE_COUNTS_PREFIX = "interact:comment_like:counts:id:";
    private final static String COMMENT_LIKE_USER_ID_SET_PREFIX = "interact:comment_like:set:id:";
    private final static String LOCK_COMMENT_LIKE_KEY_PREFIX = "interact:comment_like:lock:";

    private final static int SET_TTL = 60 * 60 * 24;

    // ================== 爆发率统计常量 ==================
    // 全局热度/爆发力排行 ZSet (供推荐 AI 提取)
    private final static String MEDIA_BURST_SCORE_ZSET = "interaction:burst:score";
    // 单个视频的爆发窗口前缀
    private final static String MEDIA_EVENT_WINDOW_PREFIX = "interaction:events:window:";
    // 滑动窗口大小：15 分钟
    private final static long BURST_WINDOW_MS = 15 * 60 * 1000;


    // ================== 点赞与取消 ==================

    @Override
    public Long doLike(String mediaId) {
        return handleInteraction(
                mediaId, "1", "like",
                MEDIA_LIKE_COUNTS_PREFIX, MEDIA_LIKE_USER_ID_SET_PREFIX,
                LOCK_LIKE_KEY_PREFIX, "database-like-topic"
        );
    }

    @Override
    public Long cancelLike(String mediaId) {
        return handleCancel(mediaId, "like", MEDIA_LIKE_COUNTS_PREFIX, MEDIA_LIKE_USER_ID_SET_PREFIX, "database-like-topic");
    }

    // ================== 收藏与取消 ==================

    @Override
    public Long doCollection(String mediaId) {
        return handleInteraction(
                mediaId, "1", "collection",
                MEDIA_COLLECTION_COUNTS_PREFIX, MEDIA_COLLECTION_USER_ID_SET_PREFIX,
                LOCK_COLLECTION_KEY_PREFIX, "database-collection-topic"
        );
    }

    @Override
    public Long cancelCollection(String mediaId) {
        return handleCancel(mediaId, "collection", MEDIA_COLLECTION_COUNTS_PREFIX, MEDIA_COLLECTION_USER_ID_SET_PREFIX, "database-collection-topic");
    }

    // ================== 评论点赞与取消 ==================

    @Override
    public Long doCommentLike(String commentId) {
        return handleInteraction(
                commentId, "1", "comment_like",
                COMMENT_LIKE_COUNTS_PREFIX, COMMENT_LIKE_USER_ID_SET_PREFIX,
                LOCK_COMMENT_LIKE_KEY_PREFIX, "database-comment-like-topic"
        );
    }

    @Override
    public Long cancelCommentLike(String commentId) {
        return handleCancel(
                commentId, "comment_like",
                COMMENT_LIKE_COUNTS_PREFIX, COMMENT_LIKE_USER_ID_SET_PREFIX,
                "database-comment-like-topic"
        );
    }

    // ================== 评论发布与删除 ==================

    @Override
    public Long sendComment(CommentDto commentDto) {
        Long userId = SecurityUtils.getCurrentUserId();
        DoCommentDto doCommentDto = new DoCommentDto(
                "",
                commentDto.getContent(),
                commentDto.getRootId(),
                commentDto.getParentId(),
                commentDto.getMediaId(),
                String.valueOf(userId),
                "1",
                new Date()
        );
        commentKafkaTemplate.send("database-comment-topic", doCommentDto);
        updateUserInterestWindow(userId, commentDto.getMediaId(), "1", "comment");

        // 评论也可以计入爆发动能（可选），这里作为示例添加
        recordBurstEvent(commentDto.getMediaId(), "comment");
        return 1L;
    }

    @Override
    public Long deleteComment(CommentDto commentDto) {
        Long userId = SecurityUtils.getCurrentUserId();
        DoCommentDto doCommentDto = new DoCommentDto(
                commentDto.getId(),
                "",
                commentDto.getRootId(),
                "",
                "",
                String.valueOf(userId),
                "0",
                new Date()
        );
        commentKafkaTemplate.send("database-comment-topic", doCommentDto);
        updateUserInterestWindow(userId, commentDto.getMediaId(), "0", "comment");
        return 1L;
    }

    // ================== 核心私有逻辑处理 ==================

    /**
     * 记录视频的爆发动能事件 (滑动窗口计算)
     */
    private void recordBurstEvent(String mediaId, String type) {
        // 仅对点赞、收藏、评论计算爆发力，剔除评论点赞这种二级交互
        if (!"like".equals(type) && !"collection".equals(type) && !"comment".equals(type)) {
            return;
        }

        long now = System.currentTimeMillis();
        String windowKey = MEDIA_EVENT_WINDOW_PREFIX + type + ":" + mediaId;

        // 1. 写入当前事件 (使用唯一 UUID 防止同一毫秒的并发覆盖)
        stringRedisTemplate.opsForZSet().add(windowKey, UUID.randomUUID().toString(), (double) now);

        // 2. 移除窗口外（15分钟前）的数据
        stringRedisTemplate.opsForZSet().removeRangeByScore(windowKey, 0, (double) (now - BURST_WINDOW_MS));

        // 3. 获取当前窗口内的有效计数值
        Long count = stringRedisTemplate.opsForZSet().zCard(windowKey);

        // 4. 更新全局排行榜（供推荐 AI 提取动态权重）
        // 权重分配：点赞(1.0)，评论(1.5)，收藏(2.0)
        double weight = 1.0;
        if ("comment".equals(type)) weight = 1.5;
        if ("collection".equals(type)) weight = 2.0;

        if (count != null) {
            // 这里可以做多维度累加，简单起见，我们直接覆盖全局榜单的动能分
            // 真实的业务流中可能会结合多种 type 的总和
            stringRedisTemplate.opsForZSet().add(MEDIA_BURST_SCORE_ZSET, mediaId, count * weight);
        }

        // 5. 设置 Key 过期时间，防止冷门视频长期占用内存
        stringRedisTemplate.expire(windowKey, 30, TimeUnit.MINUTES);
    }

    /**
     * 通用的交互处理逻辑（点赞/收藏）
     */
    private Long handleInteraction(String mediaId, String action, String type,
                                   String countPrefix, String setPrefix,
                                   String lockPrefix, String topic) {
        Long userId = SecurityUtils.getCurrentUserId();
        String setKey = setPrefix + mediaId;
        String countKey = countPrefix + mediaId;
        Date now = new Date();

        // 1. 执行 Lua 脚本 (尝试扣减或增加)
        Long result = stringRedisTemplate.execute(doOrCancelScript, List.of(countKey, setKey), String.valueOf(userId), action);

        // 2. 重复操作处理 (0 表示已点赞/已收藏)
        if (Objects.equals(result, 0L)) return 0L;

        // 3. 缓存缺失回源 (-1)
        if (Objects.equals(result, -1L)) {
            RLock lock = redissonClient.getLock(lockPrefix + mediaId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey))) {
                            return handleInteraction(mediaId, action, type, countPrefix, setPrefix, lockPrefix, topic);
                        }

                        Long dbId;
                        if ("like".equals(type)) {
                            dbId = likeMapper.selectLikeIdByUserIdAndMediaId(userId, Long.parseLong(mediaId));
                        } else if ("collection".equals(type)) {
                            dbId = collectionMapper.selectCollectionIdByUserIdAndMediaId(userId, Long.parseLong(mediaId));
                        } else {
                            dbId = commentLikeMapper.selectLikeIdByUserIdAndCommentId(userId, Long.parseLong(mediaId));
                        }

                        if (dbId != null) {
                            // 数据库已有记录，同步至 Redis
                            stringRedisTemplate.opsForSet().add(setKey, String.valueOf(userId));
                            stringRedisTemplate.expire(setKey, SET_TTL, TimeUnit.SECONDS);
                            return 0L;
                        } else {
                            // 数据库无记录，真操作，发 Kafka
                            sendToKafka(topic, action, mediaId, userId, now, type);
                            return 1L;
                        }
                    } finally {
                        if (lock.isHeldByCurrentThread()) lock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("系统繁忙，请重试");
        }

        // 4. Lua 执行成功，发 Kafka
        sendToKafka(topic, action, mediaId, userId, now, type);
        return result;
    }

    /**
     * 通用的取消逻辑（取消操作不回源，提高吞吐量）
     */
    private Long handleCancel(String mediaId, String type, String countPrefix, String setPrefix, String topic) {
        Long userId = SecurityUtils.getCurrentUserId();
        String setKey = setPrefix + mediaId;
        String countKey = countPrefix + mediaId;

        Long result = stringRedisTemplate.execute(doOrCancelScript, List.of(countKey, setKey), String.valueOf(userId), "0");

        if (Objects.equals(result, 1L) || Objects.equals(result, -1L)) {
            sendToKafka(topic, "0", mediaId, userId, new Date(), type);
            return 1L;
        }
        return 0L;
    }

    /**
     * 统一 Kafka 发送逻辑与动能记录
     */
    private void sendToKafka(String topic, String action, String mediaId, Long userId, Date now, String type) {
        // 1. 发送 Kafka 异步落库
        if ("like".equals(type)) {
            doLikeKafkaTemplate.send(topic, new DoLikeDto(action, mediaId, String.valueOf(userId), now));
        } else if ("collection".equals(type)) {
            doCollectionKafkaTemplate.send(topic, new DoCollectionDto(action, mediaId, String.valueOf(userId), now));
        } else if ("comment_like".equals(type)) {
            commentLikeKafkaTemplate.send(topic, new DoCommentLikeDto(String.valueOf(userId), mediaId, action, now));
        }

        // 2. 行为特征与动能记录集成
        if (!"comment_like".equals(type)) {
            // 记录用户维度行为窗口
            updateUserInterestWindow(userId, mediaId, action, type);

            // 记录视频维度爆发动能 (仅记录新增动作)
            if ("1".equals(action)) {
                recordBurstEvent(mediaId, type);
            }
        }
    }

    /**
     * 更新用户行为滑动窗口
     */
    private void updateUserInterestWindow(Long userId, String mediaId, String action, String type) {
        String actionTag = "1".equals(action) ? type : "cancel_" + type;
        String actionData = mediaId + ":" + actionTag;
        String userWindowKey = USER_VECTOR_WINDOW_PREFIX + userId;

        stringRedisTemplate.execute(
                userSlidingWindowScript,
                List.of(userWindowKey),
                mediaId,
                actionTag,
                String.valueOf(WINDOW_SIZE)
        );

        log.debug("更新用户{}兴趣窗口: {}", userId, actionData);
    }
}