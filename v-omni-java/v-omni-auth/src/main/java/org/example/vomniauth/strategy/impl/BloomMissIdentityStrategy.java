package org.example.vomniauth.strategy.impl;

import jakarta.annotation.Resource;
import org.example.vomniauth.strategy.IdentityResolutionStrategy;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("bloomMissStrategy")
public class BloomMissIdentityStrategy implements IdentityResolutionStrategy {

    private static final int EMAIL_TO_ID_TTL = 60 * 60;
    private static final int ID_STATE_TTL = 60 * 60;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private DefaultRedisScript<Long> getOrCreateIdScript;

    @Override
    public Long resolve(String email) {
        String emailToIdKey = "auth:id:email:" + email;

        Object cached = redisTemplate.opsForValue().get(emailToIdKey);
        if (cached != null) {
            return Long.parseLong(cached.toString());
        }

        long newId = snowflakeIdWorker.nextId();
        String stateKey = "auth:state:id:" + newId;

        List<String> keys = List.of(emailToIdKey, stateKey);
        return redisTemplate.execute(
                getOrCreateIdScript,
                keys,
                newId,
                EMAIL_TO_ID_TTL,
                ID_STATE_TTL
        );
    }
}
