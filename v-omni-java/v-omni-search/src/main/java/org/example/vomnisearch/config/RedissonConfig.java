package org.example.vomnisearch.config;

import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    // 从配置文件读取 Redis 连接信息
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * 创建 RedissonClient 基础 Bean（必须！）
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;

        config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                // 调低连接池大小，避免资源占用过多（生产环境按需调整）
                .setConnectionPoolSize(24)
                .setConnectionMinimumIdleSize(8)
                .setIdleConnectionTimeout(30000);

        return Redisson.create(config);
    }
}
