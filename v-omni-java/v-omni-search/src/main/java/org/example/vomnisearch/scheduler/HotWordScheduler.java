package org.example.vomnisearch.scheduler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.po.DocumentHotWordsPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
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

    /**
     * 定时同步：Redis ZSet -> ES 索引
     * 核心改进：由单个同步改为 Bulk 批量同步，提升吞吐量
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void syncRedisToEs() {
        Set<ZSetOperations.TypedTuple<String>> topWords =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(HOT_WORDS_KEY, 0, 1000);

        if (topWords == null || topWords.isEmpty()) return;

        try {
            BulkRequest.Builder br = new BulkRequest.Builder();

            for (ZSetOperations.TypedTuple<String> tuple : topWords) {
                String word = tuple.getValue();
                Double score = tuple.getScore();
                if (word == null) continue;

                DocumentHotWordsPo po = new DocumentHotWordsPo(word, score, false,new Date(),new Date());
                // 确保同步进来的词默认是未删除的

                // 将每个词的索引操作加入 Bulk 队列
                br.operations(op -> op
                        .index(idx -> idx
                                .index(INDEX_NAME)
                                .id(word) // 以词为 ID 实现幂等
                                .document(po)
                        )
                );
            }

            // 一次网络往返完成 1000 条数据写入
            esClient.bulk(br.build());
            log.info("成功批量同步 {} 条热词数据至 ES", topWords.size());

        } catch (IOException e) {
            log.error("同步热词到 ES 失败: {}", e.getMessage());
        }
    }
}

