package org.example.vomniinteract.config;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

@Configuration
public class RedisTimeSeriesConfig {

    @Bean
    public RedisClient redisClient(RedisProperties redisProperties) {
        // Construct the URI dynamically: redis://password@host:port
        String userInfo = (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty())
                ? ":" + redisProperties.getPassword() + "@"
                : "";

        String uri = String.format("redis://%s%s:%d",
                userInfo,
                redisProperties.getHost(),
                redisProperties.getPort());

        return RedisClient.create(uri);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> connection(RedisClient client) {
        return client.connect();
    }

    @Bean
    public RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> connection) {
        return connection.sync();
    }
}