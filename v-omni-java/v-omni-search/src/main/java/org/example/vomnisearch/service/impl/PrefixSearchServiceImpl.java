package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.example.vomnisearch.po.PrefixSearchPo;
import org.example.vomnisearch.service.PrefixSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PrefixSearchServiceImpl implements PrefixSearchService {

    @Resource
    private ElasticsearchClient esClient; // 新版 Java API Client

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String INDEX_NAME = "vomni_hot_suggest";
    private static final String HOT_WORDS_KEY = "hot_words:global";

    /**
     * 前缀联想查询 - 使用新版 ES Client
     */
    @Override
    public List<PrefixSearchPo> getSuggestions(String prefix) throws IOException {
        try {
            // 使用 Lambda 风格构建查询，非常硬核
            SearchResponse<PrefixSearchPo> response = esClient.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q
                                    .match(m -> m
                                            .field("word")
                                            .query(prefix)
                                    )
                            )
                            .sort(so -> so
                                    .field(f -> f
                                            .field("score")
                                            .order(SortOrder.Desc)
                                    )
                            )
                            .size(10),
                    PrefixSearchPo.class // 直接反序列化为 PO 对象
            );

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (IOException e) {
            log.error("ES 联想查询异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 定时同步：Redis ZSet -> ES 索引
     * 每 5 分钟同步一次 Top 1000
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    private void syncRedisToEs() {
        Set<ZSetOperations.TypedTuple<String>> topWords =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(HOT_WORDS_KEY, 0, 1000);

        if (topWords == null || topWords.isEmpty()) return;

        topWords.forEach(tuple -> {
            String word = tuple.getValue();
            Double score = tuple.getScore();

            PrefixSearchPo po = new PrefixSearchPo(word, score);

            try {
                // 使用 Fluent API 进行 Index 操作
                esClient.index(i -> i
                        .index(INDEX_NAME)
                        .id(word) // 以词作为 ID 实现幂等 Upsert
                        .document(po)
                );
            } catch (IOException e) {
                log.error("同步热词 [{}] 到 ES 失败: {}", word, e.getMessage());
            }
        });
        log.info("成功同步 {} 条热词数据至 ES", topWords.size());
    }
}
