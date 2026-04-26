package org.example.vomniauth.config;

import org.example.vomniauth.mapper.UserMapper;
import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    // 1. 新增密码字段映射
    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;

        config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                // 2. 只有当密码不为空时才设置，避免连接没有密码的 Redis 时报错
                .setPassword(redisPassword.isBlank() ? null : redisPassword)
                .setConnectionPoolSize(24)
                .setConnectionMinimumIdleSize(8)
                .setIdleConnectionTimeout(30000);

        return Redisson.create(config);
    }

    @Bean
    public RBloomFilter<String> emailBloomFilter(RedissonClient redissonClient, UserMapper userMapper) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("auth:email:filter");

        // 初始化：预期插入100万个元素，容错率 0.03 (3%)
        // 注意：初始化只能做一次，如果已经初始化过了，这个调用会被跳过
        bloomFilter.tryInit(1000000L, 0.03);

        // 【预热逻辑】如果是新部署的项目，这里需要把数据库已有的 email 加载进去
        // 建议只在项目第一次启动或手动触发时执行
        // List<String> allEmails = userMapper.selectAllEmails();
        // allEmails.forEach(bloomFilter::add);

        return bloomFilter;
    }

    @Bean
    public RBloomFilter<String> idBloomFilter(RedissonClient redissonClient, UserMapper userMapper) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("auth:id:filter");

        // 初始化：预期插入100万个元素，容错率 0.03 (3%)
        // 注意：初始化只能做一次，如果已经初始化过了，这个调用会被跳过
        bloomFilter.tryInit(1000000L, 0.03);

        // 【预热逻辑】如果是新部署的项目，这里需要把数据库已有的 email 加载进去
        // 建议只在项目第一次启动或手动触发时执行
        // List<String> allEmails = userMapper.selectAllEmails();
        // allEmails.forEach(bloomFilter::add);

        return bloomFilter;
    }
}
