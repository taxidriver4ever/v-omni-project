package org.example.vomniinteract.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.CommentMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.po.*;
import org.example.vomniinteract.service.*;
import org.example.vomniinteract.service.impl.DocumentUserProfileServiceImpl;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

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

    // ================= 新增注入 =================
    @Resource
    private VectorService vectorService;
    @Resource
    private RedisTemplate<String, Object> redisTemplate; // 用于存取用户的兴趣向量
    // ============================================

    /**
     * 1. 视频点赞消费者
     */
    @Transactional
    @KafkaListener(topics = "database-like-topic", groupId = "v-omni-interaction-group")
    public void databaseLikeTopicConsume(List<DoLikeDto> messages) {
        List<InteractionTaskDto> esTasks = new ArrayList<>();
        for (DoLikeDto m : messages) {
            Long uId = Long.parseLong(m.getUserId());
            Long mId = Long.parseLong(m.getMediaId());
            boolean isAdd = !"0".equals(m.getAction());

            if (isAdd) {
                likeMapper.insertLike(LikePo.builder().id(snowflakeIdWorker.nextId()).userId(uId).mediaId(mId).createTime(m.getCreateTime()).build());
                // 【新增】点赞触发兴趣向量演化
                triggerInterestEvolution(uId, mId, "like");
            } else {
                likeMapper.deleteLike(uId, mId);
                // 取消点赞也可以传入 "cancel_like" 让模型弱化该特征，或者直接忽略
            }
            esTasks.add(InteractionTaskDto.builder().userId(uId).mediaId(mId).actionType("LIKE").add(isAdd).build());
        }
        documentMediaInteractionService.bulkProcessInteractions(esTasks);
    }

    /**
     * 2. 视频收藏消费者
     */
    @Transactional
    @KafkaListener(topics = "database-collection-topic", groupId = "v-omni-interaction-group")
    public void databaseCollectionTopicConsume(List<DoCollectionDto> messages) {
        List<InteractionTaskDto> esTasks = new ArrayList<>();
        for (DoCollectionDto m : messages) {
            Long uId = Long.parseLong(m.getUserId());
            Long mId = Long.parseLong(m.getMediaId());
            boolean isAdd = !"0".equals(m.getAction());

            if (isAdd) {
                collectionMapper.insertCollection(CollectionPo.builder().id(snowflakeIdWorker.nextId()).userId(uId).mediaId(mId).createTime(m.getCreateTime()).build());
                // 【新增】收藏触发兴趣向量演化 (收藏的权重在模型里更高，演化幅度更大)
                triggerInterestEvolution(uId, mId, "collect");
            } else {
                collectionMapper.deleteCollection(uId, mId);
            }
            esTasks.add(InteractionTaskDto.builder().userId(uId).mediaId(mId).actionType("COLLECT").add(isAdd).build());
        }
        documentMediaInteractionService.bulkProcessInteractions(esTasks);
    }

    /**
     * 3. 评论点赞消费者 (无变化)
     */
    @Transactional
    @KafkaListener(topics = "database-comment-like-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentLikeTopicConsume(List<DoCommentLikeDto> messages) {
        log.info("接收到评论点赞消息: {} 条", messages.size());
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
     * 4. 评论发布/删除消费者 (无变化)
     */
    @Transactional
    @KafkaListener(topics = "database-comment-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentTopicConsume(List<DoCommentDto> messages) {
        List<Long> userIds = messages.stream()
                .filter(m -> !"0".equals(m.getAction()))
                .map(m -> Long.parseLong(m.getUserId())).distinct().toList();

        Map<Long, UserPo> userMap = userIds.isEmpty() ? new HashMap<>() :
                commentMapper.selectUserInfosByIds(userIds).stream().collect(Collectors.toMap(UserPo::getId, u -> u));

        List<DocumentCommentPo> saveList = new ArrayList<>();
        List<DoCommentDto> deleteMessages = new ArrayList<>();

        for (DoCommentDto m : messages) {
            boolean isAdd = !"0".equals(m.getAction());
            long cId = (m.getId() == null || m.getId().isEmpty()) ? 0L : Long.parseLong(m.getId());
            long rId = (m.getRootId() == null || m.getRootId().isEmpty()) ? 0L : Long.parseLong(m.getRootId());
            Long uId = Long.parseLong(m.getUserId());
            Long mId = Long.parseLong(m.getMediaId());

            if (isAdd) {
                long finalId = (cId == 0) ? snowflakeIdWorker.nextId() : cId;

                commentMapper.insertComment(CommentPo.builder().id(finalId).mediaId(mId).userId(uId)
                        .rootId(rId).parentId(Long.parseLong(m.getParentId()))
                        .content(m.getContent()).createTime(m.getCreateTime()).build());

                UserPo user = userMap.get(uId);
                saveList.add(DocumentCommentPo.builder().commentId(finalId).mediaId(mId).userId(uId)
                        .userName(user != null ? user.getUsername() : "用户已注销")
                        .userAvatar(user != null ? user.getAvatarPath() : "default.png")
                        .content(m.getContent()).likeCount(0).rootId(rId)
                        .parentId(Long.parseLong(m.getParentId())).createTime(m.getCreateTime()).build());

                // 可选：你也可以在这里触发 triggerInterestEvolution(uId, mId, "comment");
            } else {
                commentMapper.deleteCommentById(cId);
                if (rId == 0) {
                    commentMapper.deleteRepliesByRootId(cId);
                    log.info("根评论删除，已触发 MySQL 级联清理回复. rootId: {}", cId);
                }
                deleteMessages.add(m);
            }
        }

        if (!saveList.isEmpty() || !deleteMessages.isEmpty()) {
            documentCommentService.bulkProcessComments(saveList, deleteMessages);
        }
    }

    /**
     * 5. 互动数据统计消费者 (纯数值聚合)
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
    // =============== 新增的核心业务逻辑：兴趣演化 ================
    // =========================================================

    /**
     * 异步触发用户的兴趣向量演化
     */
    private void triggerInterestEvolution(Long userId, Long mediaId, String action) {
        try {
            // 1. 获取刚刚被互动的视频的向量特征
            // 注意：请确保 documentVectorMediaService 有能获取到 512维 向量的方法
            // 比如下面假设你的 Media 对象有个 getVector() 方法返回 float[]
            var mediaInfo = documentVectorMediaService.getById(String.valueOf(mediaId));
            // 假设 mediaInfo.getVideoEmbedding() 返回的是 List<Float>
            List<Float> embeddingList = mediaInfo.getVideoEmbedding();
            if (embeddingList == null || embeddingList.isEmpty()) {
                log.warn("视频特征不存在，无法演化兴趣。mediaId: {}", mediaId);
                return;
            }

            // 手动转换，避免对象数组开销
            float[] mediaVector = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                // 自动处理可能的 Float -> float 拆箱
                mediaVector[i] = embeddingList.get(i);
            }

            // 2. 从 Redis 获取用户之前的长期兴趣向量
            float[] oldInterest = documentUserProfileService.getUserInterestVector(String.valueOf(userId));

            if (oldInterest == null) {
                // 如果是新用户第一次互动，用 0 向量初始化 (或者用这次视频的向量作为初始兴趣)
                oldInterest = new float[512];
            }

            float[] fuseUserInterest = vectorService.fuseUserInterest(
                    oldInterest,
                    List.of(mediaVector),
                    List.of(action)
            );

            documentUserProfileService.updateUserProfile(String.valueOf(userId),mediaVector,fuseUserInterest,new Date());

            log.info("🔮 用户 {} 兴趣演化完成！动作: {}", userId, action);

        } catch (Exception e) {
            log.error("❌ 用户 {} 兴趣演化失败", userId, e);
        }
    }
}