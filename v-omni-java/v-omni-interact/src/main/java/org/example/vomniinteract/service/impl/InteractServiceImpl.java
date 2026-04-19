package org.example.vomniinteract.service.impl;

import jakarta.annotation.Resource;
import org.apache.kafka.common.protocol.types.Field;
import org.example.vomniinteract.dto.DoLikeDTO;
import org.example.vomniinteract.service.InteractService;
import org.example.vomniinteract.util.SecurityUtils;
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
    private KafkaTemplate<String,DoLikeDTO> kafkaTemplate;


    @Override
    public Long doLike(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long execute = stringRedisTemplate.execute(
                doOrCancelLikeScript,
                List.of("interact:like:times:user_id:media_id:" + mediaId, "interact:like:media_id:counts"),
                String.valueOf(userId),
                mediaId,
                "1"
        );
        if(execute.equals(0L)) return 0L;
        DoLikeDTO doLikeDTO = new DoLikeDTO("1",mediaId,String.valueOf(userId));
        kafkaTemplate.send("database-like-topic",doLikeDTO);
        return execute;
    }

    @Override
    public Long cancelLike(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long execute = stringRedisTemplate.execute(
                doOrCancelLikeScript,
                List.of("interact:like:times:user_id:media_id:" + mediaId, "interact:like:media_id:counts"),
                String.valueOf(userId),
                mediaId,
                "0"
        );
        if(execute.equals(0L)) return 0L;
        DoLikeDTO doLikeDTO = new DoLikeDTO("0",mediaId,String.valueOf(userId));
        kafkaTemplate.send("database-like-topic",doLikeDTO);
        return execute;
    }

    @Override
    public Long doCollection(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long execute = stringRedisTemplate.execute(
                doOrCancelLikeScript,
                List.of("interact:collection:times:user_id:media_id:" + mediaId, "interact:collection:media_id:counts"),
                String.valueOf(userId),
                mediaId,
                "1"
        );
        if(execute.equals(0L)) return 0L;
        DoLikeDTO doLikeDTO = new DoLikeDTO("1",mediaId,String.valueOf(userId));
        kafkaTemplate.send("database-collection-topic",doLikeDTO);
        return execute;
    }

    @Override
    public Long cancelCollection(String mediaId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long execute = stringRedisTemplate.execute(
                doOrCancelLikeScript,
                List.of("interact:collection:times:user_id:media_id:" + mediaId, "interact:collection:media_id:counts"),
                String.valueOf(userId),
                mediaId,
                "0"
        );
        if(execute.equals(0L)) return 0L;
        DoLikeDTO doLikeDTO = new DoLikeDTO("0",mediaId,String.valueOf(userId));
        kafkaTemplate.send("database-collection-topic",doLikeDTO);
        return execute;
    }
}
