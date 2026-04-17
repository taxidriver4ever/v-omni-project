package org.example.vomnimedia.config;

import org.example.vomnimedia.mapper.MediaMapper;
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

    @Bean
    public RBloomFilter<String> idBloomFilter(RedissonClient redissonClient, MediaMapper mediaMapper) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("media:id:filter");

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
