package org.example.vomniinteract.config;

import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:16379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 构建地址，注意 redisson 需要 redis:// 前缀
        String address = String.format("redis://%s:%d", host, port);

        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(address);

        // 核心修复：只有当密码不为空时才设置密码
        if (password != null && !password.trim().isEmpty()) {
            serverConfig.setPassword(password);
        }

        return Redisson.create(config);
    }
}
