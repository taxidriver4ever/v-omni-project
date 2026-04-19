package org.example.vomnisearch.scheduler;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class HotWordScheduler {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Boolean>decayScript;

    private final static String HOT_WORDS_KEY = "hot_words:global";

    // 每天凌晨 0 点执行，或者每小时跑一次
    @Scheduled(cron = "0 0 0 * * ?")
    public void decayHotWords() {

        // 参数：Key, 衰减系数 0.5, 阈值 0.1
        stringRedisTemplate.execute(decayScript, Collections.singletonList(HOT_WORDS_KEY), "0.5", "0.3");
    }
}

