package org.example.vomniauth.strategy.impl;

import jakarta.annotation.Resource;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.po.UserPo;
import org.example.vomniauth.strategy.IdentityResolutionStrategy;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component("bloomHitStrategy")
public class BloomHitIdentityStrategy implements IdentityResolutionStrategy {

    private static final int EMAIL_TO_ID_TTL = 60 * 60;
    private static final int ID_STATE_TTL = 60 * 60;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private UserMapper userMapper;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Override
    public Long resolve(String email) {
        String cacheKey = "auth:id:email:" + email;
        String lockKey = "lock:auth:id:email:" + email;

        // 1. 快速检查缓存
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return Long.parseLong(cached);
        }

        RLock lock = redissonClient.getLock(lockKey);
        int maxRetry = 3;
        while (maxRetry-- > 0) {
            try {
                if (lock.tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        // 2. 双重检查
                        cached = stringRedisTemplate.opsForValue().get(cacheKey);
                        if (cached != null) {
                            return Long.parseLong(cached);
                        }

                        // 3. 查询数据库
                        UserPo userPo = userMapper.findIdStateByEmail(email);

                        if (userPo == null) {
                            // 新用户：预分配ID
                            Long newId = snowflakeIdWorker.nextId();
                            String stateKey = "auth:state:id:" + newId;

                            stringRedisTemplate.opsForValue().set(
                                    stateKey,
                                    AuthState.INITIAL.toString(),
                                    ID_STATE_TTL,
                                    TimeUnit.SECONDS
                            );
                            stringRedisTemplate.opsForValue().set(cacheKey, newId.toString(), EMAIL_TO_ID_TTL, TimeUnit.SECONDS);
                            return newId;
                        } else {
                            // 已注册用户：缓存真实状态并返回0
                            Long existingId = userPo.getId();
                            String stateKey = "auth:state:id:" + existingId;

                            stringRedisTemplate.opsForValue().set(
                                    stateKey,
                                    userPo.getState(),
                                    ID_STATE_TTL,
                                    TimeUnit.SECONDS
                            );
                            stringRedisTemplate.opsForValue().set(cacheKey, existingId.toString(), EMAIL_TO_ID_TTL, TimeUnit.SECONDS);
                            return 0L;
                        }

                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                }
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ID解析被中断", e);
            }
        }
        throw new RuntimeException("系统繁忙，请稍后重试");
    }
}
