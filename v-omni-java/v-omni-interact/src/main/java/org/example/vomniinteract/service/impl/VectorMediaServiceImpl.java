package org.example.vomniinteract.service.impl;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.service.VectorMediaService;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
@Service
public class VectorMediaServiceImpl implements VectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    /**
     * 根据 ID 查找视频标题
     * 核心优化：仅获取 title 字段，排除 embedding 等大字段
     */
    @Override
    public String findTitleById(String id) throws IOException {
        try {
            SearchResponse<DocumentVectorMediaPo> response = client.search(s -> s
                            .index(INDEX)
                            // 1. 精确匹配 ID
                            .query(q -> q.ids(i -> i.values(id)))
                            // 2. 核心性能优化：只取 title，不取别的数据
                            .source(source -> source.filter(f -> f.includes("title")))
                            .size(1),
                    DocumentVectorMediaPo.class
            );

            // 3. 提取结果
            return response.hits().hits().stream()
                    .findFirst()
                    .map(hit -> hit.source() != null ? hit.source().getTitle() : null)
                    .orElse(null);

        } catch (Exception e) {
            log.error("ES 根据ID查询标题失败, id: {}", id, e);
            return null;
        }
    }

}
