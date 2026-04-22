package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.service.DocumentVectorMediaService;
import org.example.vomnisearch.service.MinioService;
import org.example.vomnisearch.vo.SearchMediaVo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentVectorMediaServiceImpl implements DocumentVectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    // 字段名常量（需与 ES Mapping 严格对应）
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_DELETED = "deleted";
    private static final String FIELD_VIDEO_VECTOR = "video_embedding";
    private static final String FIELD_TEXT_VECTOR = "text_embedding";

    // 权重配置：三路召回配比
    private static final float WEIGHT_QUERY_MATCH = 0.3f;  // 文本关键词权重
    private static final float WEIGHT_TEXT_VECTOR = 0.4f;   // 标题语义向量权重
    private static final float WEIGHT_VIDEO_VECTOR = 0.3f;  // 视频画面向量权重

    @Resource
    private MinioService minioService;

    /**
     * ① 全量写入
     */
    @Override
    public void upsert(DocumentVectorMediaPo doc) throws IOException {
        if (doc.getDeleted() == null) doc.setDeleted(false);
        client.index(i -> i.index(INDEX).id(doc.getId()).document(doc));
    }

    /**
     * ② 局部更新
     */
    @Override
    public void updateFields(String id, Map<String, Object> fields) throws IOException {
        if (fields == null || fields.isEmpty()) return;
        client.update(u -> u.index(INDEX).id(id).doc(fields), Object.class);
    }

    /**
     * ③ 逻辑删除
     */
    @Override
    public void deleteById(String id) throws IOException {
        client.update(u -> u
                        .index(INDEX)
                        .id(id)
                        .doc(Collections.singletonMap(FIELD_DELETED, true)),
                Object.class
        );
    }

    /**
     * 核心方法：多路混合搜索（文本 + 双向量）
     */
    @Override
    public List<SearchMediaVo> hybridSearch(String queryText, float[] queryVector, int page, int size) throws IOException {
        // 1. 向量转换：ES Client KnnQuery 接收 List<Float>
        List<Float> vectorList = new ArrayList<>();
        if (queryVector != null) {
            for (float v : queryVector) vectorList.add(v);
        }

        // 2. 基础过滤条件：必须是未删除的数据
        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        // 3. 第一路：文本关键词匹配查询 (Boolean Query)
        // 使用 boost 调整该路在整体评分中的权重
        Query textMatchQuery = Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field(FIELD_TITLE).query(queryText).boost(2.0f))) // 标题匹配更重要
                .should(s -> s.match(m -> m.field(FIELD_AUTHOR).query(queryText)))
                .minimumShouldMatch("1")
                .filter(filterQuery)
        ));

        // 4. 第二路 & 第三路：双向量 KNN 检索
        List<KnnQuery> knnQueries = new ArrayList<>();
        if (!vectorList.isEmpty()) {
            // A. 标题语义向量路
            knnQueries.add(KnnQuery.of(k -> k
                    .field(FIELD_TEXT_VECTOR)
                    .queryVector(vectorList)
                    .k(size)
                    .numCandidates(100)
                    .boost(WEIGHT_TEXT_VECTOR)
                    .filter(filterQuery)
            ));

            // B. 视频画面向量路
            knnQueries.add(KnnQuery.of(k -> k
                    .field(FIELD_VIDEO_VECTOR)
                    .queryVector(vectorList)
                    .k(size)
                    .numCandidates(100)
                    .boost(WEIGHT_VIDEO_VECTOR)
                    .filter(filterQuery)
            ));
        }

        // 5. 执行搜索请求
        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.bool(b -> b
                        .must(textMatchQuery)
                        .boost(WEIGHT_QUERY_MATCH) // 设置文本路整体权重
                ))
                .knn(knnQueries) // 注入多路 KNN 查询
                .from((page - 1) * size)
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        // 6. 结果映射转换
        return response.hits().hits().stream()
                .map(hit -> {
                    DocumentVectorMediaPo po = hit.source();
                    if (po == null) return null;

                    return SearchMediaVo.builder()
                            .mediaId(po.getId())
                            .title(po.getTitle())
                            .author(po.getAuthor())
                            .coverUrl(minioService.generateCoverUrl(po.getCoverPath()))
                            .avatarUrl(minioService.generateAvatarUrl(po.getAvatarPath()))
                            .publishedDate(po.getCreateTime() != null ?
                                    po.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() : null)
                            .likeCount(String.valueOf(po.getLikeCount()))
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 基础文本搜索
     */
    @Override
    public List<DocumentVectorMediaPo> textSearch(String queryText, String author, int size) throws IOException {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        boolBuilder.filter(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        if (queryText != null && !queryText.isBlank()) {
            boolBuilder.must(Query.of(q -> q.match(m -> m.field(FIELD_TITLE).query(queryText))));
        }
        if (author != null && !author.isBlank()) {
            boolBuilder.must(Query.of(q -> q.match(m -> m.field(FIELD_AUTHOR).query(author))));
        }

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.bool(boolBuilder.build()))
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);
        return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
    }

    /**
     * 文本分词分析
     */
    @Override
    public List<String> analyzeText(String text) throws IOException {
        AnalyzeRequest request = AnalyzeRequest.of(a -> a.index(INDEX).analyzer("ik_smart").text(text));
        AnalyzeResponse response = client.indices().analyze(request);
        return response.tokens().stream().map(AnalyzeToken::token).toList();
    }
}