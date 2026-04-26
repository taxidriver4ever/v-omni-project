package org.example.vomniauth.config;

import org.example.vomniauth.util.SnowflakeIdWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdConfig {
    @Bean
    public SnowflakeIdWorker snowflakeIdWorker() {
        // 在宿舍开发，workerId 和 datacenterId 可以先写死 1, 1
        // 以后多机部署可以从环境变量或 Nacos 动态获取
        return new SnowflakeIdWorker(1, 1);
    }
}
