package org.example.vomnisearch.config;

import org.redisson.Redisson;
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
}