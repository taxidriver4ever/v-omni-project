package org.example.vomnimedia.service.impl;

import jakarta.annotation.Resource;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.po.MediaPo;
import org.example.vomnimedia.service.IdentityService;
import org.example.vomnimedia.util.SnowflakeIdWorker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IdentityServiceImpl implements IdentityService {

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Long getOrCreateUserIdByEmail() {
        long id = snowflakeIdWorker.nextId();
        String stateKey = "media:state:id:" + String.valueOf(id);
        stringRedisTemplate.opsForValue().set(stateKey, MediaState.INITIAL.toString());
        return id;
    }
}
