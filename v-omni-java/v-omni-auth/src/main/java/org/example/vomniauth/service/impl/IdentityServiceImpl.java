package org.example.vomniauth.service.impl;

import jakarta.annotation.Resource;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.po.UserPo;
import org.example.vomniauth.service.IdentityService;
import org.example.vomniauth.strategy.IdentityResolutionStrategy;
import org.example.vomniauth.strategy.IdentityStrategySelector;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class IdentityServiceImpl implements IdentityService {

    private static final int EMAIL_TO_ID_TTL = 60 * 60;

    private static final int ID_STATE_TTL = 60 * 60;

    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private DefaultRedisScript<Long> getOrCreateIdScript;

    @Resource
    private IdentityStrategySelector identityStrategySelector;

    @Resource
    private RBloomFilter<String> emailBloomFilter;

    @Override
    public Long getOrCreateUserIdByEmail(String email) {
        boolean contains = emailBloomFilter.contains(email);
        IdentityResolutionStrategy select = identityStrategySelector.select(contains);
        return select.resolve(email);
    }

    @Override
    public Long getIdByEmail(String email) {
        String cacheKey = "auth:id:email:" + email;
        String lockKey = "lock:auth:id:email:" + email;

        // 1. 查缓存，处理空值
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return Long.parseLong(cached);

        RLock lock = redissonClient.getLock(lockKey);
        int maxRetry = 3;
        while (maxRetry-- > 0) {
            try {
                if (lock.tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        // 双重检查
                        cached = stringRedisTemplate.opsForValue().get(cacheKey);
                        if (cached != null) return Long.parseLong(cached);

                        Long id = userMapper.findIdByEmail(email);

                        if(id == null) return 0L;

                        String stateKey = "auth:state:id:" + id;

                        stringRedisTemplate.opsForValue().set(
                                stateKey,
                                AuthState.REGISTERED.toString(),
                                ID_STATE_TTL,
                                TimeUnit.SECONDS
                        );
                        stringRedisTemplate.opsForValue().set(cacheKey, id.toString(), EMAIL_TO_ID_TTL, TimeUnit.SECONDS);

                        return id;
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                }
                // 未获取到锁，短暂休眠后重试
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("获取ID操作被中断", e);
            }
        }
        throw new RuntimeException("系统繁忙，请稍后重试");
    }

}
