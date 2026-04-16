package org.example.vomniauth.config;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public JedisPoolConfig jedisPoolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setJmxEnabled(false);
        config.setMaxTotal(200);
        config.setMaxIdle(50);
        config.setMinIdle(10);
        config.setMaxWaitMillis(1000);       // 关键：避免死等
        config.setTestOnBorrow(true);        // 关键：验证连接有效性
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRunsMillis(30000);
        return config;
    }

    // 2. 定义 Jedis 连接工厂（Spring Data Redis 用）
    @Bean
    public JedisConnectionFactory jedisConnectionFactory(JedisPoolConfig poolConfig) {
        JedisConnectionFactory factory = new JedisConnectionFactory(poolConfig);
        factory.setHostName("localhost");
        factory.setPort(6379);
        factory.setTimeout(2000);
        // 如果有密码：factory.setPassword("你的密码");
        return factory;
    }

    // 3. RedisTemplate 使用 Jedis 连接工厂
    @Bean
    public RedisTemplate<String, Object> redisTemplate(JedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory); // 这里现在注入的是 Jedis 工厂了

        // ... 你的序列化配置保持不变 ...
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        Jackson2JsonRedisSerializer<Object> jacksonSerializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    // 4. 业务代码用的 JedisPool 也复用同一套配置（保持池子一致）
    @Bean
    public JedisPool jedisPool(JedisPoolConfig poolConfig) {
        // 直接利用上面的 JedisPoolConfig 创建 JedisPool，保证配置统一
        return new JedisPool(poolConfig, "localhost", 6379, 2000, null);
    }
    /* ---------------------------------------------------------
     * Lua 脚本注入区域
     * --------------------------------------------------------- */

    /**
     * 获取或者写入雪花id
     */
    @Bean
    public DefaultRedisScript<Long> getOrCreateIdScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/atom_get_or_create_id.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}