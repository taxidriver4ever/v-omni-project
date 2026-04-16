package org.example.vomniauth;

import jakarta.annotation.Resource;
import org.example.vomniauth.domain.statemachine.AuthRule;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.po.UserPo;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.example.vomniauth.util.UsernameGenerator;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@SpringBootTest
class VOmniAuthApplicationTests {

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RBloomFilter<String> emailBloomFilter;

    @Test
    void contextLoads() throws IOException {

        for(int i = 0;i < 100000;i++)
        {
            String email = "test_" + i + "@vomni.com";
            emailBloomFilter.add(email);
        }
    }

}
