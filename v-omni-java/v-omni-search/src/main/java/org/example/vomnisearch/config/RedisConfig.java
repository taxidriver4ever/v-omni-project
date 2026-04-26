package org.example.vomnisearch.config;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // 1. 显式配置 Lettuce 工厂 (解决 Redisson 劫持导致 UnsupportedOperationException 的关键)
    @Bean
    public RedisConnectionFactory lettuceConnectionFactory(RedisProperties redisProperties) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisProperties.getHost());
        config.setPort(redisProperties.getPort());
        // 确保能读到你 yml 里的 password
        if (redisProperties.getPassword() != null) {
            config.setPassword(redisProperties.getPassword());
        }
        config.setDatabase(redisProperties.getDatabase());
        return new LettuceConnectionFactory(config);
    }

    // 2. 定义通用的 RedisTemplate<String, Object> (解决 Application Failed to Start 的关键)
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory lettuceConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(lettuceConnectionFactory);

        GenericJackson2JsonRedisSerializer jacksonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jacksonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // 3. 定义 StringRedisTemplate (mediaTransitionService 需要它)
    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory lettuceConnectionFactory) {
        return new StringRedisTemplate(lettuceConnectionFactory);
    }

    // 4. 定义你之前用到的 byteRedisTemplate
    @Bean
    public RedisTemplate<String, byte[]> byteRedisTemplate(RedisConnectionFactory lettuceConnectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(lettuceConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /* ---------------------------------------------------------
     * Lua 脚本注入区域
     * --------------------------------------------------------- */
    @Bean
    public DefaultRedisScript<Boolean> decayScript() {
        DefaultRedisScript<Boolean> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/decay.lua"));
        redisScript.setResultType(Boolean.class);
        return redisScript;
    }

    @Bean
    public DefaultRedisScript<Boolean> upsertScript() {
        DefaultRedisScript<Boolean> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/upsert.lua"));
        redisScript.setResultType(Boolean.class);
        return redisScript;
    }
}