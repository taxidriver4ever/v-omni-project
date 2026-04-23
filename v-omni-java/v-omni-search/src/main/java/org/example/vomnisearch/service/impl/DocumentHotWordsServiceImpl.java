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

    private static final String INDEX_NAME = "vomni_hot_suggest";
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
}
