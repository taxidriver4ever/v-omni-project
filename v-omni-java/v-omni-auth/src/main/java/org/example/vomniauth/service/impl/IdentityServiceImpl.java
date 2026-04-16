package org.example.vomniauth.service.impl;

import jakarta.annotation.Resource;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.service.IdentityService;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    private RedissonClient redissonClient;

    @Resource
    private DefaultRedisScript<Long> getOrCreateIdScript;

    @Override
    public Long getOrCreateUserIdByEmail(String email) {
        String emailToIdKey = "auth:id:email:" + email;

        Object o = redisTemplate.opsForValue().get(emailToIdKey);
        if (o != null) return Long.parseLong(o.toString());

        long id = snowflakeIdWorker.nextId();
        String stateKey = "auth:state:id:" + id;

        List<String>keys = List.of(emailToIdKey,stateKey);
        return redisTemplate.execute(
                getOrCreateIdScript,
                keys,
                id,
                EMAIL_TO_ID_TTL,
                ID_STATE_TTL
        );
    }

    @Override
    public Long getIdByEmail(String email) {
        String cacheKey = "auth:id:email:" + email;
        String lockKey = "lock:auth:id:email:" + email;

        Object cacheObj = redisTemplate.opsForValue().get(cacheKey);
        if (cacheObj != null) {
            long id = Long.parseLong(cacheObj.toString());
            return id > 0 ? id : 0L;
        }

        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 等待最多 3 秒，锁持有最多 10 秒
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);

            if (!locked) {
                // 没抢到锁，短暂等待后重试（防止惊群）
                Thread.sleep(30);
                return getIdByEmail(email); // 递归重试（或改成 while 循环）
            }

            cacheObj = redisTemplate.opsForValue().get(cacheKey);

            if (cacheObj != null) return Long.parseLong(cacheObj.toString());

            Long id = userMapper.findIdByEmail(email);

            if (id == null)
                return 0L;
            redisTemplate.opsForValue().set(cacheKey, id, EMAIL_TO_ID_TTL, TimeUnit.SECONDS);
            return id;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取邮箱对应ID时被中断", e);
        } finally {
            lock.unlock();
        }
    }

}
