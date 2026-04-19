package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentCommentPo;
import org.example.vomniinteract.service.DocumentCommentService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCommentServiceImpl implements DocumentCommentService {

    private final ElasticsearchClient client;
    private final StringRedisTemplate redisTemplate;

    private static final String INDEX = "v_omni_comment";
    private static final String COMMENT_ZSET_PREFIX = "interact:comments:media_id:"; // 按时间排序的缓存

    @Override
    public void upsert(DocumentCommentPo commentPo) throws IOException {
        // 1. 写入 ES
        client.update(u -> u
                        .index(INDEX)
                        .id(commentPo.getId())
                        .doc(commentPo)
                        .docAsUpsert(true),
                DocumentCommentPo.class
        );

        // 2. 写入 Redis ZSet 缓存 (仅限一级评论，Score 为时间戳)
        if ("0".equals(commentPo.getParentId())) {
            String key = COMMENT_ZSET_PREFIX + commentPo.getMediaId();
            redisTemplate.opsForZSet().add(key, commentPo.getId(), commentPo.getCreateTime().getTime());
            // 限制缓存长度，只留前 500 条热点
            redisTemplate.expire(key, Duration.ofDays(1));
        }
    }

    @Override
    public Map<String, Object> findCommentsByPage(String mediaId, String rootId, List<Object> lastSortValues) throws IOException {
        // 构造搜索请求
        SearchRequest searchRequest = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("media_id").value(mediaId)))
                        .must(m -> m.term(t -> t.field("root_id").value(rootId)))
                        .must(m -> m.term(t -> t.field("deleted").value(false)))
                ))
                // 核心：search_after 游标定位
                .searchAfter(lastSortValues != null ?
                        (FieldValue) lastSortValues.stream().map(JsonData::of).collect(Collectors.toList()).reversed() : null)
                // 排序：时间倒序 + ID 升序 (保证唯一性)
                .sort(so -> so.field(f -> f.field("create_time").order(SortOrder.Desc)))
                .sort(so -> so.field(f -> f.field("id").order(SortOrder.Asc)))
                .size(10)
        );

        SearchResponse<DocumentCommentPo> response = client.search(searchRequest, DocumentCommentPo.class);

        // 处理结果
        List<Hit<DocumentCommentPo>> hits = response.hits().hits();
        List<DocumentCommentPo> data = hits.stream()
                .map(Hit::source)
                .toList();

        // 获取最后一条的排序值，作为下一次请求的游标
        List<Object> nextCursor = null;
        if (!hits.isEmpty()) {
            nextCursor = Collections.singletonList(hits.getLast().sort());
        }

        return Map.of("list", data, "cursor", nextCursor != null ? nextCursor : List.of());
    }
}
