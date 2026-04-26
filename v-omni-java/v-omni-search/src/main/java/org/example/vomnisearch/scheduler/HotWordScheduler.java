package org.example.vomnisearch.scheduler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.Set;

@Component
@Slf4j
public class HotWordScheduler {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Boolean>decayScript;

    @Resource
    private ElasticsearchClient esClient;

    private final static String HOT_WORDS_KEY = "hot_words:global";

    private static final String INDEX_NAME = "vomni_hot_suggest";

    // 每天凌晨 0 点执行，或者每小时跑一次
    @Scheduled(cron = "0 0 0 * * ?")
    public void decayHotWords() {

        // 参数：Key, 衰减系数 0.5, 阈值 0.1
        stringRedisTemplate.execute(decayScript, Collections.singletonList(HOT_WORDS_KEY), "0.5", "0.3");
    }
}

