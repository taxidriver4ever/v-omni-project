package org.example.vomniinteract.service.impl;

import jakarta.annotation.Resource;
import org.example.vomniinteract.service.InteractService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InteractServiceImpl implements InteractService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Long> doOrCancelLikeScript;

    @Resource
    private KafkaTemplate<String,String> kafkaTemplate;

    @Override
    public Long doLike(Long mediaId, Long userId) {
        Long execute = stringRedisTemplate.execute(
                doOrCancelLikeScript,
                List.of("media:like:times:user_id:media_id:" + mediaId, "media:media_id:counts"),
                userId,
                mediaId,
                "1"
        );
        if(execute.equals(0L)) return 0L;
        kafkaTemplate.send("do-or-cancel-like-topic","1:" + mediaId + ":" + userId);
        return execute;
    }

    @Override
    public Long cancelLike(Long mediaId, Long userId) {
        Long execute = stringRedisTemplate.execute(
                doOrCancelLikeScript,
                List.of("media:like:times:user_id:media_id:" + mediaId, "media:media_id:counts"),
                userId,
                mediaId,
                "0"
        );
        if(execute.equals(0L)) return 0L;
        kafkaTemplate.send("do-or-cancel-like-topic","0:" + mediaId + ":" + userId);
        return execute;
    }
}
