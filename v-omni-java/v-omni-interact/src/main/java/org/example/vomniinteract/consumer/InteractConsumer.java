package org.example.vomniinteract.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.DoLikeDTO;
import org.example.vomniinteract.mapper.InteractMapper;
import org.example.vomniinteract.po.*;
import org.example.vomniinteract.service.DocumentCollectionService;
import org.example.vomniinteract.service.DocumentLikeService;
import org.example.vomniinteract.service.DocumentVectorMediaService;
import org.example.vomniinteract.util.SnowflakeIdWorker;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class InteractConsumer {

    @Resource
    private DocumentLikeService documentLikeService;

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DocumentCollectionService documentCollectionService;

    @Resource
    private InteractMapper interactMapper;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private KafkaTemplate<String, DoLikeDTO> kafkaTemplate;

    private static final String MEDIA_INFO_PREFIX = "media:info:";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_AUTHOR = "author"; // 修正变量名

    @Transactional
    @KafkaListener(topics = "database-like-topic", groupId = "v-omni-media-group")
    public void databaseLikeTopicConsume(@NotNull DoLikeDTO message) {
        String action = message.getAction();
        String mediaId = message.getMediaId();
        String userId = message.getUserId();
        UserLikePo userLikePo = UserLikePo.builder()
                .id(snowflakeIdWorker.nextId())
                .likeUserId(Long.parseLong(userId))
                .mediaId(Long.parseLong(mediaId))
                .deleted(Integer.parseInt(action))
                .build();
        interactMapper.upsertUserLike(userLikePo);
        kafkaTemplate.send("do-or-cancel-like-topic", message);
    }

    @Transactional
    @KafkaListener(topics = "database-collection-topic", groupId = "v-omni-media-group")
    public void databaseCollectionTopicConsume(@NotNull DoLikeDTO message) {
        String action = message.getAction();
        String mediaId = message.getMediaId();
        String userId = message.getUserId();
        UserCollectionPo userCollectionPo = UserCollectionPo.builder()
                .id(snowflakeIdWorker.nextId())
                .collectionUserId(Long.parseLong(userId))
                .mediaId(Long.parseLong(mediaId))
                .deleted(Integer.parseInt(action))
                .build();
        interactMapper.upsertUserCollection(userCollectionPo);
        kafkaTemplate.send("do-or-cancel-collection-topic", message);
    }


    @KafkaListener(topics = "do-or-cancel-like-topic", groupId = "v-omni-media-group")
    public void doOrCancelLikeTopicConsume(@NotNull DoLikeDTO message) {
        try {
            String action = message.getAction();
            String mediaId = message.getMediaId();
            String userId = message.getUserId();
            if ("1".equals(action)) {
                // 1. HMGET 一次性获取标题和作者
                List<Object> values = stringRedisTemplate.opsForHash()
                        .multiGet(MEDIA_INFO_PREFIX + mediaId, List.of(FIELD_TITLE, FIELD_AUTHOR));

                String title = (String) values.get(0);
                String author = (String) values.get(1);

                // 2. 缓存失效处理（只要有一个为空就去查 ES 补全）
                if (title == null || author == null) {
                    DocumentVectorMediaPo media = documentVectorMediaService.findMediaBaseInfo(mediaId);
                    if (media != null) {
                        title = media.getTitle();
                        author = media.getAuthor();
                        // 3. 顺手回写 Hash
                        Map<String, String> map = Map.of(FIELD_TITLE, title, FIELD_AUTHOR, author);
                        stringRedisTemplate.opsForHash().putAll(MEDIA_INFO_PREFIX + mediaId, map);
                        stringRedisTemplate.expire(MEDIA_INFO_PREFIX + mediaId, Duration.ofDays(1));
                    }
                }

                // 4. 构建并写入 ES
                DocumentLikePo po = DocumentLikePo.builder()
                        .userId(userId)
                        .mediaId(mediaId)
                        .title(title != null ? title : "未知视频")
                        .author(author != null ? author : "未知作者")
                        .deleted(false)
                        .createTime(new Date())
                        .build();

                documentLikeService.upsert(po);

            } else {
                documentLikeService.delete(userId, mediaId);
            }
        } catch (Exception e) {
            log.error("消费点赞消息异常: {}", message, e);
        }
    }

    @KafkaListener(topics = "do-or-cancel-collection-topic", groupId = "v-omni-media-group")
    public void doOrCancelCollectionTopicConsume(@NotNull DoLikeDTO message) {
        try {
            String action = message.getAction();
            String mediaId = message.getMediaId();
            String userId = message.getUserId();
            if ("1".equals(action)) {
                // 1. HMGET 一次性获取标题和作者
                List<Object> values = stringRedisTemplate.opsForHash()
                        .multiGet(MEDIA_INFO_PREFIX + mediaId, List.of(FIELD_TITLE, FIELD_AUTHOR));

                String title = (String) values.get(0);
                String author = (String) values.get(1);

                // 2. 缓存失效处理（只要有一个为空就去查 ES 补全）
                if (title == null || author == null) {
                    DocumentVectorMediaPo media = documentVectorMediaService.findMediaBaseInfo(mediaId);
                    if (media != null) {
                        title = media.getTitle();
                        author = media.getAuthor();
                        // 3. 顺手回写 Hash
                        Map<String, String> map = Map.of(FIELD_TITLE, title, FIELD_AUTHOR, author);
                        stringRedisTemplate.opsForHash().putAll(MEDIA_INFO_PREFIX + mediaId, map);
                        stringRedisTemplate.expire(MEDIA_INFO_PREFIX + mediaId, Duration.ofDays(1));
                    }
                }

                // 4. 构建并写入 ES
                DocumentCollectionPo po = DocumentCollectionPo.builder()
                        .userId(userId)
                        .mediaId(mediaId)
                        .title(title != null ? title : "未知视频")
                        .author(author != null ? author : "未知作者")
                        .deleted(false)
                        .createTime(new Date())
                        .build();

                documentCollectionService.upsert(po);

            } else {
                documentCollectionService.delete(userId, mediaId);
            }
        } catch (Exception e) {
            log.error("消费点赞消息异常: {}", message, e);
        }
    }
}
