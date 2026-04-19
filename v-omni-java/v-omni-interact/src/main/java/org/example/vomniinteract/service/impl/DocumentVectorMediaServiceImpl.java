package org.example.vomniinteract.service.impl;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.service.DocumentVectorMediaService;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentVectorMediaServiceImpl implements DocumentVectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    /**
     * 根据 ID 查找视频基础信息（标题和作者）
     * 仅获取必要字段，排除 embedding 向量
     */
    @Override
    public DocumentVectorMediaPo findMediaBaseInfo(String id) {
        try {
            SearchResponse<DocumentVectorMediaPo> response = client.search(s -> s
                            .index(INDEX)
                            .query(q -> q.ids(i -> i.values(id)))
                            // 核心优化：同时包含 title 和 author
                            .source(source -> source.filter(f -> f.includes("title", "author")))
                            .size(1),
                    DocumentVectorMediaPo.class
            );

            return response.hits().hits().stream()
                    .findFirst()
                    .map(Hit::source)
                    .orElse(null);
        } catch (Exception e) {
            log.error("ES 查询视频基础信息失败, id: {}", id, e);
            return null;
        }
    }


}
