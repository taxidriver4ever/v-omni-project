package org.example.vomniinteract.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.DoCollectionDto;
import org.example.vomniinteract.dto.DoCommentDto;
import org.example.vomniinteract.dto.DoLikeDto;
import org.example.vomniinteract.mapper.CollectionMapper;
import org.example.vomniinteract.mapper.CommentMapper;
import org.example.vomniinteract.mapper.LikeMapper;
import org.example.vomniinteract.po.*;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.jetbrains.annotations.NotNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private SnowflakeIdWorker snowflakeIdWorker;

    @Transactional
    @KafkaListener(topics = "database-like-topic", groupId = "v-omni-media-group")
    public void databaseLikeTopicConsume(@NotNull DoLikeDto message) {
        String action = message.getAction();
        String mediaId = message.getMediaId();
        String userId = message.getUserId();
        if("0".equals(action)) likeMapper.deleteLike(Long.parseLong(userId), Long.parseLong(mediaId));
        else {
            LikePo likePo = LikePo.builder()
                    .id(snowflakeIdWorker.nextId())
                    .userId(Long.parseLong(userId))
                    .mediaId(Long.parseLong(mediaId))
                    .createTime(message.getCreateTime())
                    .build();
            likeMapper.insertLike(likePo);
        }
    }

    @Transactional
    @KafkaListener(topics = "database-collection-topic", groupId = "v-omni-media-group")
    public void databaseCollectionTopicConsume(@NotNull DoCollectionDto message) {
        String action = message.getAction();
        String mediaId = message.getMediaId();
        String userId = message.getUserId();
        if(action.equals("0")) collectionMapper.deleteCollection(Long.parseLong(userId),Long.parseLong(mediaId));
        else {
            CollectionPo collectionPo = CollectionPo.builder()
                    .id(snowflakeIdWorker.nextId())
                    .userId(Long.parseLong(userId))
                    .mediaId(Long.parseLong(mediaId))
                    .createTime(message.getCreateTime())
                    .build();
            collectionMapper.insertCollection(collectionPo);
        }
    }

    @Transactional
    @KafkaListener(topics = "database-comment-topic", groupId = "v-omni-media-group")
    public void databaseCommentTopicConsume(@NotNull DoCommentDto message) {
        String action = message.getAction();
        String rootId = message.getRootId();
        if("0".equals(action)) {
            String id = message.getId();
            if(rootId == null || "0".equals(rootId)) commentMapper.deleteCommentById(Long.parseLong(id));
            else {
                commentMapper.deleteCommentByRootId(Long.parseLong(rootId));
                commentMapper.deleteCommentById(Long.parseLong(id));
            }
        }
        else {
            Long id = snowflakeIdWorker.nextId();
            message.setId(String.valueOf(id));
            CommentPo commentPo = CommentPo.builder()
                    .id(id)
                    .userId(Long.parseLong(message.getUserId()))
                    .parentId(Long.parseLong(message.getParentId()))
                    .rootId(Long.parseLong(rootId))
                    .mediaId(Long.parseLong(message.getMediaId()))
                    .content(message.getContent())
                    .createTime(message.getCreateTime())
                            .build();
            commentMapper.insertComment(commentPo);
        }
    }
}
