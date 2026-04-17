package org.example.vomnimedia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * 显式配置 Lettuce 连接工厂，确保连接池参数生效
     */
    @Bean
    @Primary
    public RedisConnectionFactory lettuceConnectionFactory() {
        // 连接池配置会自动从 spring.redis.lettuce.pool 读取
        // 无需手动 set，但需要确保 application.yml 中有对应配置（见下方说明）
        return new LettuceConnectionFactory();
    }


    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // 使用 GenericJackson2JsonRedisSerializer，它可以自动处理多种类型
        GenericJackson2JsonRedisSerializer jacksonSerializer =
                new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisTemplate<String, byte[]> byteRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JdkSerializationRedisSerializer()); // 或直接用默认
        template.afterPropertiesSet();
        return template;
    }

    /* ---------------------------------------------------------
     * Lua 脚本注入区域
     * --------------------------------------------------------- */
}