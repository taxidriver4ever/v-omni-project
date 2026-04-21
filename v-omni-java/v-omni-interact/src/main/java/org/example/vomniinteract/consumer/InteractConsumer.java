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
     * 4. 评论发布/删除消费者 (含联查用户信息)
     */
    @Transactional
    @KafkaListener(topics = "database-comment-topic", groupId = "v-omni-interaction-group")
    public void databaseCommentTopicConsume(List<DoCommentDto> messages) {
        // 1. 批量联查用户信息
        List<Long> userIds = messages.stream()
                .filter(m -> !"0".equals(m.getAction()))
                .map(m -> Long.parseLong(m.getUserId())).distinct().toList();

        Map<Long, UserPo> userMap = userIds.isEmpty() ? new HashMap<>() :
                commentMapper.selectUserInfosByIds(userIds).stream().collect(Collectors.toMap(UserPo::getId, u -> u));

        List<DocumentCommentPo> saveList = new ArrayList<>();
        List<Long> deleteList = new ArrayList<>();

        for (DoCommentDto m : messages) {
            boolean isAdd = !"0".equals(m.getAction());
            Long uId = Long.parseLong(m.getUserId());
            Long mId = Long.parseLong(m.getMediaId());

            if (isAdd) {
                Long cId = (m.getId() == null || "0".equals(m.getId())) ? snowflakeIdWorker.nextId() : Long.parseLong(m.getId());
                // MySQL 存入
                commentMapper.insertComment(CommentPo.builder().id(cId).mediaId(mId).userId(uId)
                        .rootId(Long.parseLong(m.getRootId())).parentId(Long.parseLong(m.getParentId()))
                        .content(m.getContent()).createTime(m.getCreateTime()).build());

                // 封装 ES 对象
                UserPo user = userMap.get(uId);
                saveList.add(DocumentCommentPo.builder().commentId(cId).mediaId(mId).userId(uId)
                        .userName(user != null ? user.getUsername() : "用户已注销")
                        .userAvatar(user != null ? user.getAvatarPath() : "default.png")
                        .content(m.getContent()).likeCount(0).rootId(Long.parseLong(m.getRootId()))
                        .parentId(Long.parseLong(m.getParentId())).createTime(m.getCreateTime()).build());
            } else {
                Long cId = Long.parseLong(m.getId());
                commentMapper.deleteCommentById(cId);
                deleteList.add(cId);
            }
        }

        // 批量执行 ES 写入/删除
        if (!saveList.isEmpty() || !deleteList.isEmpty()) {
            documentCommentService.bulkProcessComments(saveList, deleteList);
        }
    }
}
