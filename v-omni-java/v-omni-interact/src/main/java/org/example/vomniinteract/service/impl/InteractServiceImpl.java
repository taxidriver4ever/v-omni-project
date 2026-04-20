package org.example.vomniinteract.service.impl;

import jakarta.annotation.Resource;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.service.InteractService;
import org.example.vomniinteract.util.SecurityUtils;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
    private RedissonClient redissonClient;

    @Resource
    private LikeMapper likeMapper;

    @Resource
    private CommentLikeMapper commentLikeMapper;

    @Resource
    private CollectionMapper collectionMapper; // 记得加上这个注入

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
        // 取消点赞不涉及回源，逻辑简单化
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

    @Override
    public Long doCommentLike(String commentId) {
        // 逻辑完全复用 handleInteraction，只需传入评论相关的参数
        return handleInteraction(
                commentId, "1", "comment_like",
                COMMENT_LIKE_COUNTS_PREFIX, COMMENT_LIKE_USER_ID_SET_PREFIX,
                LOCK_COMMENT_LIKE_KEY_PREFIX, "database-comment-like-topic"
        );
    }

    @Override
    public Long cancelCommentLike(String commentId) {
        // 逻辑完全复用 handleCancel
        return handleCancel(
                commentId, "comment_like",
                COMMENT_LIKE_COUNTS_PREFIX, COMMENT_LIKE_USER_ID_SET_PREFIX,
                "database-comment-like-topic"
        );
    }

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
        return 1L;
    }


    /**
     * 通用的交互处理逻辑（点赞/收藏）
     * @param type "like" 或 "collection"
     */
    private Long handleInteraction(String mediaId, String action, String type,
                                   String countPrefix, String setPrefix,
                                   String lockPrefix, String topic) {
        Long userId = SecurityUtils.getCurrentUserId();
        String setKey = setPrefix + mediaId;
        String countKey = countPrefix + mediaId;
        Date now = new Date();

        // 1. 执行 Lua 脚本
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
                        } else { // comment_like
                            // 注入并使用 commentLikeMapper
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

        // 如果是 -1，说明 Redis 没数据，直接发 Kafka 让数据库去尝试删除
        if (Objects.equals(result, 1L) || Objects.equals(result, -1L)) {
            sendToKafka(topic, "0", mediaId, userId, new Date(), type);
            return 1L;
        }
        return 0L; // 本来就没点过
    }

    /**
     * 统一 Kafka 发送逻辑
     */
    private void sendToKafka(String topic, String action, String mediaId, Long userId, Date now, String type) {
        if ("like".equals(type)) {
            doLikeKafkaTemplate.send(topic, new DoLikeDto(action, mediaId, String.valueOf(userId), now));
        } else {
            doCollectionKafkaTemplate.send(topic, new DoCollectionDto(action, mediaId, String.valueOf(userId), now));
        }
    }
}
