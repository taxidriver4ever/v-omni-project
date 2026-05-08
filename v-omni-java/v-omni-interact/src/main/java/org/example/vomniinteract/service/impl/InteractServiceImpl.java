package org.example.vomniinteract.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.CommentMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.po.DocumentMediaInteractionPo;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.service.DocumentMediaInteractionService;
import org.example.vomniinteract.service.DocumentVectorMediaService;
import org.example.vomniinteract.service.InteractService;
import org.example.vomniinteract.util.SecurityUtils;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.example.vomniinteract.vo.InteractionVo;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InteractServiceImpl implements InteractService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Long> doLikeCollectionScript;

    @Resource
    private DefaultRedisScript<Long> cancelLikeCollectionScript;

    @Resource
    private DefaultRedisScript<Long> doCommentLikeScript;

    @Resource
    private DefaultRedisScript<Long> cancelCommentLikeScript;

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
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private DocumentMediaInteractionService documentMediaInteractionService;

    private final static String MEDIA_INFO_PREFIX = "interact:media:info:";

    private final static String MEDIA_LIKE_USER_ID_SET_PREFIX = "interact:like:set:media_id:";
    private final static String MEDIA_LIKE_MEDIA_ID_ZSET_PREFIX = "interact:like:zset:user_id:";
    private final static String LOCK_LIKE_KEY_PREFIX = "interact:like:lock:";

    private final static String MEDIA_COLLECTION_USER_ID_SET_PREFIX = "interact:collection:set:media_id:";
    private final static String MEDIA_COLLECTION_MEDIA_ID_ZSET_PREFIX = "interact:collection:zset:user_id:";
    private final static String LOCK_COLLECTION_KEY_PREFIX = "interact:collection:lock:";

    private final static String COMMENT_LIKE_USER_ID_SET_PREFIX = "interact:comment_like:set:comment_id:";
    private final static String LOCK_COMMENT_LIKE_KEY_PREFIX = "interact:comment_like:lock:";

    private final static int SET_TTL = 60 * 60 * 24;
    private final static int HASH_TTL = 60 * 60 * 24;

    // ================== 点赞与取消 ==================

    @Override
    public Long doLike(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Date now = new Date();
        long result = stringRedisTemplate.execute(
                doLikeCollectionScript,
                List.of(MEDIA_INFO_PREFIX + mediaId, MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId, MEDIA_LIKE_MEDIA_ID_ZSET_PREFIX + userId),
                String.valueOf(userId),
                "like_count",
                now.toInstant().toEpochMilli(),
                mediaId
        );
        if(result == -1L) {
            RLock lock = redissonClient.getLock(LOCK_LIKE_KEY_PREFIX + mediaId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId))) {
                            return 0L;
                        }

                        DocumentVectorMediaPo po = documentVectorMediaService.getById(mediaId);

                        List<Long> longs = likeMapper.selectUserIdByMediaId(Long.valueOf(mediaId));

                        if (longs != null && !longs.isEmpty()) {
                            String[] userIds = longs.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            stringRedisTemplate.opsForSet().add(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId, userIds);
                        }
                        stringRedisTemplate.opsForSet().add(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId, String.valueOf(userId));
                        stringRedisTemplate.expire(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId,  SET_TTL, TimeUnit.SECONDS);

                        if (po != null) {
                            Map<String, String> map = new HashMap<>();
                            map.put("title", po.getTitle());
                            map.put("author", po.getAuthor());
                            map.put("like_count", String.valueOf(po.getLikeCount() + 1));
                            map.put("collection_count", String.valueOf(po.getCollectionCount()));
                            map.put("comment_count", String.valueOf(po.getCommentCount()));
                            map.put("cover_path", po.getCoverPath());
                            map.put("avatar_path", po.getAvatarPath());

                            stringRedisTemplate.opsForHash().putAll(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    map
                            );
                            stringRedisTemplate.expire(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    HASH_TTL,
                                    TimeUnit.SECONDS
                            );
                            doLikeKafkaTemplate.send("database-like-topic",
                                    new DoLikeDto("like", mediaId, String.valueOf(userId), now));
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
        else if(result == 1L) {
            doLikeKafkaTemplate.send("database-like-topic",
                    new DoLikeDto("like", mediaId, String.valueOf(userId), now));
            return 1L;
        }
        return 0L;
    }

    @Override
    public Long cancelLike(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Date now = new Date();
        long result = stringRedisTemplate.execute(
                cancelLikeCollectionScript,
                List.of(MEDIA_INFO_PREFIX + mediaId, MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId, MEDIA_LIKE_MEDIA_ID_ZSET_PREFIX + userId),
                String.valueOf(userId),
                "like_count",
                now.toInstant().toEpochMilli(),
                mediaId
        );
        if(result == -1L) {
            RLock lock = redissonClient.getLock(LOCK_LIKE_KEY_PREFIX + mediaId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId))) {
                            return 0L;
                        }

                        DocumentVectorMediaPo po = documentVectorMediaService.getById(mediaId);

                        List<Long> longs = likeMapper.selectUserIdByMediaId(Long.valueOf(mediaId));

                        if (longs != null && !longs.isEmpty()) {
                            String[] userIds = longs.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            stringRedisTemplate.opsForSet().add(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId, userIds);
                        }
                        Long remove = stringRedisTemplate.opsForSet().remove(
                                MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId,
                                String.valueOf(userId)
                        );
                        if (remove != null && remove.equals(1L))
                            stringRedisTemplate.expire(MEDIA_LIKE_USER_ID_SET_PREFIX + mediaId, SET_TTL, TimeUnit.SECONDS);
                        else return 0L;

                        if (po != null) {
                            Map<String, String> map = new HashMap<>();
                            map.put("title", po.getTitle());
                            map.put("author", po.getAuthor());
                            map.put("like_count", String.valueOf(po.getLikeCount() - 1));
                            map.put("collection_count", String.valueOf(po.getCollectionCount()));
                            map.put("comment_count", String.valueOf(po.getCommentCount()));
                            map.put("cover_path", po.getCoverPath());
                            map.put("avatar_path", po.getAvatarPath());

                            stringRedisTemplate.opsForHash().putAll(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    map
                            );
                            stringRedisTemplate.expire(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    HASH_TTL,
                                    TimeUnit.SECONDS
                            );
                            doLikeKafkaTemplate.send("database-like-topic",
                                    new DoLikeDto("cancel", mediaId, String.valueOf(userId), now));
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
        else if(result == 1L) {
            doLikeKafkaTemplate.send("database-like-topic",
                    new DoLikeDto("cancel", mediaId, String.valueOf(userId), now));
            return 1L;
        }
        return 0L;
    }

    // ================== 收藏与取消 ==================

    @Override
    public Long doCollection(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Date now = new Date();
        long result = stringRedisTemplate.execute(
                doLikeCollectionScript,
                List.of(MEDIA_INFO_PREFIX + mediaId, MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, MEDIA_COLLECTION_MEDIA_ID_ZSET_PREFIX + userId),
                String.valueOf(userId),
                "collection_count",
                now.toInstant().toEpochMilli(),
                mediaId
        );
        if(result == -1L) {
            RLock lock = redissonClient.getLock(LOCK_COLLECTION_KEY_PREFIX + mediaId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId))) {
                            return 0L;
                        }

                        DocumentVectorMediaPo po = documentVectorMediaService.getById(mediaId);

                        List<Long> longs = collectionMapper.selectUserIdByMediaId(Long.parseLong(mediaId));

                        if (longs != null && !longs.isEmpty()) {
                            String[] userIds = longs.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            stringRedisTemplate.opsForSet().add(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, userIds);
                        }
                        stringRedisTemplate.opsForSet().add(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, String.valueOf(userId));
                        stringRedisTemplate.expire(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, SET_TTL, TimeUnit.SECONDS);

                        if (po != null) {
                            Map<String, String> map = new HashMap<>();
                            map.put("title", po.getTitle());
                            map.put("author", po.getAuthor());
                            map.put("like_count", String.valueOf(po.getLikeCount()));
                            map.put("collection_count", String.valueOf(po.getCollectionCount() + 1));
                            map.put("comment_count", String.valueOf(po.getCommentCount()));
                            map.put("cover_path", po.getCoverPath());
                            map.put("avatar_path", po.getAvatarPath());

                            stringRedisTemplate.opsForHash().putAll(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    map
                            );
                            stringRedisTemplate.expire(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    HASH_TTL,
                                    TimeUnit.SECONDS
                            );
                            doCollectionKafkaTemplate.send("database-collection-topic",
                                    new DoCollectionDto("collection", mediaId, String.valueOf(userId), now));
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
        else if(result == 1L) {
            doCollectionKafkaTemplate.send("database-collection-topic",
                    new DoCollectionDto("collection", mediaId, String.valueOf(userId), now));
            return 1L;
        }
        return 0L;
    }

    @Override
    public Long cancelCollection(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Date now = new Date();
        long result = stringRedisTemplate.execute(
                cancelLikeCollectionScript,
                List.of(MEDIA_INFO_PREFIX + mediaId, MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, MEDIA_COLLECTION_MEDIA_ID_ZSET_PREFIX + userId),
                String.valueOf(userId),
                "collection_count",
                now.toInstant().toEpochMilli(),
                mediaId
        );
        if(result == -1L) {
            RLock lock = redissonClient.getLock(LOCK_COLLECTION_KEY_PREFIX + mediaId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId))) {
                            return 0L;
                        }

                        DocumentVectorMediaPo po = documentVectorMediaService.getById(mediaId);

                        List<Long> longs = collectionMapper.selectUserIdByMediaId(Long.parseLong(mediaId));

                        if (longs != null && !longs.isEmpty()) {
                            String[] userIds = longs.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            stringRedisTemplate.opsForSet().add(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, userIds);
                        }
                        Long remove = stringRedisTemplate.opsForSet().remove(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId,
                                String.valueOf(userId));
                        if (remove != null && remove.equals(1L))
                            stringRedisTemplate.expire(MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId, SET_TTL, TimeUnit.SECONDS);
                        else return 0L;

                        if (po != null) {
                            Map<String, String> map = new HashMap<>();
                            map.put("title", po.getTitle());
                            map.put("author", po.getAuthor());
                            map.put("like_count", String.valueOf(po.getLikeCount()));
                            map.put("collection_count", String.valueOf(po.getCollectionCount() - 1));
                            map.put("comment_count", String.valueOf(po.getCommentCount()));
                            map.put("cover_path", po.getCoverPath());
                            map.put("avatar_path", po.getAvatarPath());

                            stringRedisTemplate.opsForHash().putAll(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    map
                            );
                            stringRedisTemplate.expire(
                                    MEDIA_INFO_PREFIX + mediaId,
                                    HASH_TTL,
                                    TimeUnit.SECONDS
                            );
                            doCollectionKafkaTemplate.send("database-collection-topic",
                                    new DoCollectionDto("cancel", mediaId, String.valueOf(userId), now));
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
        else if(result == 1L) {
            doCollectionKafkaTemplate.send("database-collection-topic",
                    new DoCollectionDto("cancel", mediaId, String.valueOf(userId), now));
            return 1L;
        }
        return 0L;
    }

    // ================== 评论点赞与取消 ==================

    @Override
    public Long doCommentLike(String commentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        long result = stringRedisTemplate.execute(
                doCommentLikeScript,
                List.of(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId),
                String.valueOf(userId)
        );
        if(result == -1L) {
            RLock lock = redissonClient.getLock(LOCK_COMMENT_LIKE_KEY_PREFIX + commentId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId))) {
                            return 0L;
                        }

                        List<Long> longs = commentLikeMapper.selectUserIdByCommentId(Long.parseLong(commentId));

                        if (longs != null && !longs.isEmpty()) {
                            String[] userIds = longs.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            stringRedisTemplate.opsForSet().add(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId, userIds);
                        }
                        stringRedisTemplate.opsForSet().add(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId, String.valueOf(userId));
                        stringRedisTemplate.expire(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId, SET_TTL, TimeUnit.SECONDS);

                        DoCommentLikeDto doCommentLikeDto = new DoCommentLikeDto(String.valueOf(userId),commentId,"like",new Date());
                        commentLikeKafkaTemplate.send("database-comment-like-topic", doCommentLikeDto);
                    } finally {
                        if (lock.isHeldByCurrentThread()) lock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("系统繁忙，请重试");
        }
        else if(result == 1L) {
            DoCommentLikeDto doCommentLikeDto = new DoCommentLikeDto(String.valueOf(userId),commentId,"like",new Date());
            commentLikeKafkaTemplate.send("database-comment-like-topic", doCommentLikeDto);
            return 1L;
        }
        return 0L;
    }

    @Override
    public Long cancelCommentLike(String commentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        long result = stringRedisTemplate.execute(
                cancelCommentLikeScript,
                List.of(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId),
                String.valueOf(userId)
        );
        if(result == -1L) {
            RLock lock = redissonClient.getLock(LOCK_COMMENT_LIKE_KEY_PREFIX + commentId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    try {
                        // Double Check
                        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId))) {
                            return 0L;
                        }

                        List<Long> longs = commentLikeMapper.selectUserIdByCommentId(Long.parseLong(commentId));

                        if (longs != null && !longs.isEmpty()) {
                            String[] userIds = longs.stream()
                                    .map(String::valueOf)
                                    .toArray(String[]::new);
                            stringRedisTemplate.opsForSet().add(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId, userIds);
                        }
                        Long remove = stringRedisTemplate.opsForSet().remove(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId,
                                String.valueOf(userId));
                        if (remove != null && remove.equals(1L))
                            stringRedisTemplate.expire(COMMENT_LIKE_USER_ID_SET_PREFIX + commentId, SET_TTL, TimeUnit.SECONDS);
                        else return 0L;

                        DoCommentLikeDto doCommentLikeDto = new DoCommentLikeDto(String.valueOf(userId),commentId,"cancel",new Date());
                        commentLikeKafkaTemplate.send("database-comment-like-topic", doCommentLikeDto);
                        return 1L;
                    } finally {
                        if (lock.isHeldByCurrentThread()) lock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("系统繁忙，请重试");
        }
        else if(result == 1L) {
            DoCommentLikeDto doCommentLikeDto = new DoCommentLikeDto(String.valueOf(userId),commentId,"cancel",new Date());
            commentLikeKafkaTemplate.send("database-comment-like-topic", doCommentLikeDto);
            return 1L;
        }
        return 0L;
    }

    @Override
    public List<InteractionVo> selectUserLike(Integer page) {
        if (page == null || page <= 0) return List.of();

        Long userId = SecurityUtils.getCurrentUserId();
        String userZsetKey = MEDIA_LIKE_MEDIA_ID_ZSET_PREFIX + userId;
        int pageSize = 10;
        List<String> mediaIds = new ArrayList<>();

        // 1. 尝试从 Redis ZSet 获取 (冷热分离：前3页)
        if (page <= 3) {
            int start = (page - 1) * pageSize;
            int end = start + pageSize - 1;
            Set<String> redisIds = stringRedisTemplate.opsForZSet().reverseRange(userZsetKey, start, end);
            if (redisIds != null && !redisIds.isEmpty()) {
                mediaIds.addAll(redisIds);
                stringRedisTemplate.expire(userZsetKey, 7, TimeUnit.DAYS);
            }
        }

        // 2. Redis 没命中或超过 3 页，从 ES 捞
        if (mediaIds.isEmpty()) {
            List<DocumentMediaInteractionPo> esInteractions = documentMediaInteractionService
                    .findUserInteractionListFromEs(userId, "like", page, pageSize);
            if (esInteractions.isEmpty()) return List.of();

            mediaIds = esInteractions.stream()
                    .map(DocumentMediaInteractionPo::getMediaId)
                    .collect(Collectors.toList());

            // 第一页缺失，触发 Top 30 异步预热
            if (page == 1) {
                documentMediaInteractionService.asyncRefreshTop30Cache(userId, userZsetKey, "like");
            }
        }

        // 3. 核心：组装完整视图数据 (Redis Pipeline + ES 差集补偿)
        return buildInteractionVos(mediaIds, userId);
    }

    @Override
    public List<InteractionVo> selectUserCollection(Integer page) {
        if (page == null || page <= 0) return List.of();

        Long userId = SecurityUtils.getCurrentUserId();
        String userZsetKey = MEDIA_COLLECTION_MEDIA_ID_ZSET_PREFIX + userId;
        int pageSize = 10;
        List<String> mediaIds = new ArrayList<>();

        // 1. 尝试从 Redis ZSet 获取 (冷热分离：前3页)
        if (page <= 3) {
            int start = (page - 1) * pageSize;
            int end = start + pageSize - 1;
            Set<String> redisIds = stringRedisTemplate.opsForZSet().reverseRange(userZsetKey, start, end);
            if (redisIds != null && !redisIds.isEmpty()) {
                mediaIds.addAll(redisIds);
                stringRedisTemplate.expire(userZsetKey, 7, TimeUnit.DAYS);
            }
        }

        // 2. Redis 没命中或超过 3 页，从 ES 捞
        if (mediaIds.isEmpty()) {
            List<DocumentMediaInteractionPo> esInteractions = documentMediaInteractionService
                    .findUserInteractionListFromEs(userId, "collection", page, pageSize);
            if (esInteractions.isEmpty()) return List.of();

            mediaIds = esInteractions.stream()
                    .map(DocumentMediaInteractionPo::getMediaId)
                    .collect(Collectors.toList());

            // 第一页缺失，触发 Top 30 异步预热
            if (page == 1) {
                documentMediaInteractionService.asyncRefreshTop30Cache(userId, userZsetKey, "collection");
            }
        }

        // 3. 核心：组装完整视图数据 (Redis Pipeline + ES 差集补偿)
        return buildInteractionVos(mediaIds, userId);
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
                "comment",
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
                commentDto.getContent(),
                commentDto.getRootId(),
                commentDto.getParentId(),
                commentDto.getMediaId(),
                String.valueOf(userId),
                "delete",
                new Date()
        );
        commentKafkaTemplate.send("database-comment-topic", doCommentDto);
        return 1L;
    }


    /**
     * 批量组装视图模型 (VO)
     */
    private List<InteractionVo> buildInteractionVos(List<String> mediaIds, Long userId) {
        Map<String, InteractionVo> voMap = new HashMap<>();
        List<String> missingIds = new ArrayList<>();

        // A. Pipeline 批量查询 Redis Hash (封面和点赞数)
        List<Object> redisResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String mid : mediaIds) {
                String key = MEDIA_INFO_PREFIX + mid;
                byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
                connection.hashCommands().hMGet(
                        rawKey,
                        "cover_path".getBytes(StandardCharsets.UTF_8),
                        "like_count".getBytes(StandardCharsets.UTF_8)
                );
            }
            return null;
        });

        for (int i = 0; i < mediaIds.size(); i++) {
            String mid = mediaIds.get(i);
            List<Object> fields = (List<Object>) redisResults.get(i);

            // 判断缓存是否命中 (只要第一个字段 cover_path 不为空即视为命中)
            if (fields != null && fields.size() >= 2 && fields.get(0) != null) {
                voMap.put(mid, InteractionVo.builder()
                        .mediaId(Long.valueOf(mid))
                        .coverUrl(new String((byte[]) fields.get(0)))
                        .likeCount(Integer.valueOf(new String((byte[]) fields.get(1))))
                        .liked(checkRealTimeLiked(mid, userId)) // 校验实时状态
                        .build());
            } else {
                missingIds.add(mid);
            }
        }

        // B. 如果有 Redis 缺失，去 ES 批量捞 Media 索引
        if (!missingIds.isEmpty()) {
            List<DocumentVectorMediaPo> esMedias = documentMediaInteractionService.findMediaListFromEs(missingIds);
            for (DocumentVectorMediaPo po : esMedias) {
                InteractionVo vo = InteractionVo.builder()
                        .mediaId(Long.valueOf(po.getId()))
                        .coverUrl(po.getCoverPath())
                        .likeCount(po.getLikeCount())
                        .liked(checkRealTimeLiked(po.getId(), userId))
                        .build();
                voMap.put(po.getId(), vo);
                // 异步回填媒体 Hash 缓存
                documentMediaInteractionService.asyncUpdateMediaHash(po);
            }
        }

        // C. 按照 mediaIds 原始顺序排列并返回
        return mediaIds.stream()
                .map(voMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 实时校验点赞状态 (面试点：解决缓存与数据库延迟的一致性方案)
     */
    private Boolean checkRealTimeLiked(String mediaId, Long userId) {
        String key = MEDIA_COLLECTION_USER_ID_SET_PREFIX + mediaId;
        // 查 Redis Set 判重
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        return isMember != null && isMember;
    }
}