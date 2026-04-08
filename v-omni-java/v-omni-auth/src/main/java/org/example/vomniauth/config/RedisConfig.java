package org.example.vomniauth.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * 配置自定义 RedisTemplate
     * 解决默认序列化乱码问题，方便在 Redis Desktop Manager 中查看数据
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // 使用 Jackson2JsonRedisSerializer 来序列化和反序列化 redis 的 value 值
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 必须配置这一行，否则 Jackson 无法将 JSON 还原回具体的对象（只会转成 LinkedHashMap）
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);

        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // key 采用 String 的序列化方式
        template.setKeySerializer(stringRedisSerializer);
        // hash 的 key 也采用 String 的序列化方式
        template.setHashKeySerializer(stringRedisSerializer);
        // value 序列化方式采用 jackson
        template.setValueSerializer(jackson2JsonRedisSerializer);
        // hash 的 value 序列化方式采用 jackson
        template.setHashValueSerializer(jackson2JsonRedisSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /* ---------------------------------------------------------
     * Lua 脚本注入区域
     * --------------------------------------------------------- */

    /**
     * 注册状态检查脚本
     * 逻辑：判断 state 0->1，设置过期时间
     */
    @Bean
    public DefaultRedisScript<Long> authCheckScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        // 脚本路径：src/main/resources/lua/auth_check.lua
        redisScript.setLocation(new ClassPathResource("lua/auth_check.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * 注册验证脚本
     */
    @Bean
    public DefaultRedisScript<Long> verifyCodeScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/verify_code.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }


    /**
     * 登录频率限制脚本 (防刷)
     */
    @Bean
    public DefaultRedisScript<Long> loginCodeScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/login_code.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * 登录验证脚本
     */
    @Bean
    public DefaultRedisScript<Long> loginVerifyScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/verify_login.lua"));
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}