package org.example.vomniauth;

import jakarta.annotation.Resource;
import org.example.vomniauth.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Random;

@SpringBootTest
class VOmniAuthApplicationTests {

    @Resource
    private UserMapper userMapper;

    @Resource
    private RBloomFilter<String> emailBloomFilter;

    @Test
    void contextLoads() {
        List<String> allEmails = userMapper.findAllEmails();
        for (String email : allEmails) {
            emailBloomFilter.add(email);
        }
    }

}
