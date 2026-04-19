package org.example.vomniinteract.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.UserLikeDocumentPo;
import org.example.vomniinteract.service.UserLikeDocumentService;
import org.example.vomniinteract.service.VectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

@Component
@Slf4j
public class InteractConsumer {

    @Resource
    private UserLikeDocumentService userLikeDocumentService;

    @Resource
    private VectorMediaService vectorMediaService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 视频元数据 Hash 的 Key 前缀
    private static final String MEDIA_INFO_PREFIX = "media:info:";
    // Hash 中的字段名
    private static final String FIELD_TITLE = "title";

    private static final String AUTHOR_TITLE = "author";

    @KafkaListener(topics = "do-or-cancel-like-topic", groupId = "v-omni-media-group")
    public void doOrCancelLikeTopicConsume(@NotNull String message) {
        try {
            String[] split = message.split(":");
            if (split.length < 3) return;

            String action = split[0];
            String mediaId = split[1];
            String userId = split[2];

            if ("1".equals(action)) {
                // 1. 使用 HGET 从哈希表中获取标题
                String title = (String) stringRedisTemplate.opsForHash().get(MEDIA_INFO_PREFIX + mediaId, FIELD_TITLE);

                // 2. 缓存失效处理
                if (title == null) {
                    title = vectorMediaService.findTitleById(mediaId);
                    if (title != null) {
                        // 回写 Hash 表
                        stringRedisTemplate.opsForHash().put(MEDIA_INFO_PREFIX + mediaId, FIELD_TITLE, title);
                        // 记得给整个 Hash 设置过期时间（防止冷数据堆积）
                        stringRedisTemplate.expire(MEDIA_INFO_PREFIX + mediaId, Duration.ofDays(1));
                    }
                }

                // 3. 构建并写入 ES
                UserLikeDocumentPo po = UserLikeDocumentPo.builder()
                        .userId(userId)
                        .mediaId(mediaId)
                        .title(title != null ? title : "未知视频")
                        .deleted(false)
                        .createTime(new Date())
                        .build();

                userLikeDocumentService.upsert(po);

            } else {
                // 取消点赞
                userLikeDocumentService.delete(userId, mediaId);
            }
        } catch (Exception e) {
            log.error("消费点赞消息异常: {}", message, e);
        }
    }
}

