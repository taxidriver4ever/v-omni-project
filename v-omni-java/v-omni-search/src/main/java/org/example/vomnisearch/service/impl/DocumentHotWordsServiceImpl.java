package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.po.DocumentHotWordsPo;
import org.example.vomnisearch.service.DocumentHotWordsService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentHotWordsServiceImpl implements DocumentHotWordsService {

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String INDEX_NAME = "vomni_hot_suggest";
    private static final String HOT_WORDS_KEY = "hot_words:global";

    // 字段名常量，必须与 Mapping 一致
    private static final String FIELD_WORD = "word";
    private static final String FIELD_SCORE = "score";
    private static final String FIELD_DELETED = "deleted";

    /**
     * 前缀联想查询
     * 核心改进：增加了 deleted 过滤，防止违规词被联想出来
     */
    @Override
    public List<DocumentHotWordsPo> getSuggestions(String prefix) throws IOException {
        try {
            SearchResponse<DocumentHotWordsPo> response = esClient.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.bool(b -> b
                                    // 1. 文本匹配
                                    .must(m -> m.match(ma -> ma.field(FIELD_WORD).query(prefix)))
                                    // 2. 逻辑删除过滤（死门）
                                    .filter(f -> f.term(t -> t.field(FIELD_DELETED).value(false)))
                            ))
                            .sort(so -> so.field(f -> f.field(FIELD_SCORE).order(SortOrder.Desc)))
                            .size(10),
                    DocumentHotWordsPo.class
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
