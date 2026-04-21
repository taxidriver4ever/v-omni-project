package org.example.vomniinteract.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.*;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentLikeMapper;
import org.example.vomniinteract.mapper.CommentMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.po.*;
import org.example.vomniinteract.service.DocumentCommentService;
import org.example.vomniinteract.service.DocumentMediaInteractionService;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 1. 视频点赞消费者 (已实现批量 ES 同步)
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
            } else {
                likeMapper.deleteLike(uId, mId);
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
            } else {
                collectionMapper.deleteCollection(uId, mId);
            }
            esTasks.add(InteractionTaskDto.builder().userId(uId).mediaId(mId).actionType("COLLECT").add(isAdd).build());
        }
        documentMediaInteractionService.bulkProcessInteractions(esTasks);
    }

    /**
     * 3. 评论点赞消费者 (新增：同步更新评论表的 likeCount)
     */
    @Transactional
    @KafkaListener(topics = "database-comment-like-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentLikeTopicConsume(List<DoCommentLikeDto> messages) {
        log.info("接收到评论点赞消息: {} 条", messages.size());
        // Map 存储：Key=评论ID, Value=点赞变化量
        Map<Long, Integer> esLikeUpdates = new HashMap<>();

        for (DoCommentLikeDto m : messages) {
            Long uId = Long.parseLong(m.getUserId());
            Long cId = Long.parseLong(m.getCommentId());
            boolean isAdd = !"0".equals(m.getAction());

            // A. MySQL 操作
            if (isAdd) {
                commentLikeMapper.insertCommentLike(snowflakeIdWorker.nextId(), cId, uId, m.getCreateTime());
            } else {
                commentLikeMapper.deleteCommentLike(cId, uId);
            }

            // B. 累加 ES 变化量 (同一评论多次点赞取消在批次内合并)
            esLikeUpdates.merge(cId, isAdd ? 1 : -1, Integer::sum);
        }

        // C. 批量同步到 ES 评论索引
        if (!esLikeUpdates.isEmpty()) {
            documentCommentService.bulkUpdateCommentLikeCount(esLikeUpdates);
        }
    }

    /**
     * 4. 评论发布/删除消费者 (支持级联删除)
     */
    @Transactional
    @KafkaListener(topics = "database-comment-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentTopicConsume(List<DoCommentDto> messages) {
        // 1. 批量联查用户信息 (逻辑不变)
        List<Long> userIds = messages.stream()
                .filter(m -> !"0".equals(m.getAction()))
                .map(m -> Long.parseLong(m.getUserId())).distinct().toList();

        Map<Long, UserPo> userMap = userIds.isEmpty() ? new HashMap<>() :
                commentMapper.selectUserInfosByIds(userIds).stream().collect(Collectors.toMap(UserPo::getId, u -> u));

        List<DocumentCommentPo> saveList = new ArrayList<>();
        List<DoCommentDto> deleteMessages = new ArrayList<>();

        for (DoCommentDto m : messages) {
            boolean isAdd = !"0".equals(m.getAction());

            // 预防性转换，避免因字段缺失导致后续逻辑崩溃
            long cId = (m.getId() == null || m.getId().isEmpty()) ? 0L : Long.parseLong(m.getId());
            long rId = (m.getRootId() == null || m.getRootId().isEmpty()) ? 0L : Long.parseLong(m.getRootId());
            Long uId = Long.parseLong(m.getUserId());
            Long mId = Long.parseLong(m.getMediaId());

            if (isAdd) {
                // 如果是新评论，生成 ID
                long finalId = (cId == 0) ? snowflakeIdWorker.nextId() : cId;

                // MySQL 存入
                commentMapper.insertComment(CommentPo.builder().id(finalId).mediaId(mId).userId(uId)
                        .rootId(rId).parentId(Long.parseLong(m.getParentId()))
                        .content(m.getContent()).createTime(m.getCreateTime()).build());

                // 封装 ES 对象
                UserPo user = userMap.get(uId);
                saveList.add(DocumentCommentPo.builder().commentId(finalId).mediaId(mId).userId(uId)
                        .userName(user != null ? user.getUsername() : "用户已注销")
                        .userAvatar(user != null ? user.getAvatarPath() : "default.png")
                        .content(m.getContent()).likeCount(0).rootId(rId)
                        .parentId(Long.parseLong(m.getParentId())).createTime(m.getCreateTime()).build());
            } else {
                // --- 删除逻辑核心 ---
                // 1. MySQL 物理/逻辑删除自身
                commentMapper.deleteCommentById(cId);

                // 2. 如果是根评论，执行级联删除回复
                if (rId == 0) {
                    commentMapper.deleteRepliesByRootId(cId);
                    log.info("根评论删除，已触发 MySQL 级联清理回复. rootId: {}", cId);
                }

                deleteMessages.add(m);
            }
        }

        // 3. 批量同步到 ES (调用你之前写的分流 bulk 方法)
        if (!saveList.isEmpty() || !deleteMessages.isEmpty()) {
            documentCommentService.bulkProcessComments(saveList, deleteMessages);
        }
    }



}
