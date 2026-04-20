package org.example.vomnisearch.service.impl;

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
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.service.DocumentVectorMediaService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentVectorMediaServiceImpl implements DocumentVectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    // 字段名常量
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_VECTOR = "embedding";
    private static final String FIELD_DELETED = "deleted"; // 修正后的字段名

    // 权重配置
    private static final double WEIGHT_TITLE = 0.35d;
    private static final double WEIGHT_AUTHOR = 0.4d;
    private static final double WEIGHT_VECTOR = 0.1d;

    /**
     * ① 全量写入（带默认值检查）
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
     * 混合搜索 ID 列表：强制加入 deleted 过滤
     */
    @Override
    public List<String> hybridSearchIds(String queryText, float[] queryVector, String author, int size) throws IOException {
        List<Float> vectorList = new ArrayList<>();
        for (float v : queryVector) vectorList.add(v);

        // 1. 构建布尔过滤：必须是未删除的
        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        // 2. 构建文本查询
        Query textQuery = Query.of(q -> q.bool(b -> b
                .must(filterQuery) // 强制过滤
                .should(s -> s.match(m -> m.field(FIELD_TITLE).query(queryText).boost(2.0f)))
                .should(s -> s.match(m -> m.field(FIELD_AUTHOR).query(author != null ? author : "").boost(1.0f)))
        ));

        // 3. 构建 KNN 查询（增加预过滤）
        KnnQuery knnQuery = KnnQuery.of(k -> k
                .field(FIELD_VECTOR)
                .queryVector(vectorList)
                .k(size)
                .numCandidates(size * 10L)
                .filter(filterQuery) // kNN 内部预过滤，极大提升准确率
                .boost(0.8f)
        );

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(textQuery)
                .knn(knnQuery)
                .size(size)
                .source(SourceConfig.of(sc -> sc.fetch(false)))
        );

        SearchResponse<Void> response = client.search(request, Void.class);
        return response.hits().hits().stream().map(Hit::id).collect(Collectors.toList());
    }

    /**
     * 混合搜索对象：Function Score 模式
     */
    @Override
    public List<DocumentVectorMediaPo> hybridSearch(String queryText, float[] queryVector, String author, int size) throws IOException {
        // 构建带过滤的基础查询
        Query baseQuery = Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field(FIELD_DELETED).value(false)))
        ));

        FunctionScoreQuery functionScoreQuery = FunctionScoreQuery.of(fs -> fs
                .query(baseQuery)
                .functions(buildScoreFunctions(queryText, queryVector, author))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Replace)
        );

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.functionScore(functionScoreQuery))
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);
        return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
    }

    /**
     * 文本搜索：增加 deleted 过滤
     */
    @Override
    public List<DocumentVectorMediaPo> textSearch(String queryText, String author, int size) throws IOException {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // 核心：强制过滤已删除的数据
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

    @Override
    public List<String> analyzeText(String text) throws IOException {
        AnalyzeRequest request = AnalyzeRequest.of(a -> a.index(INDEX).analyzer("ik_smart").text(text));
        AnalyzeResponse response = client.indices().analyze(request);
        return response.tokens().stream().map(AnalyzeToken::token).toList();
    }

    private List<FunctionScore> buildScoreFunctions(String queryText, float[] queryVector, String author) {
        List<FunctionScore> functions = new ArrayList<>();

        functions.add(FunctionScore.of(f -> f
                .filter(Query.of(q -> q.match(m -> m.field(FIELD_TITLE).query(queryText))))
                .weight(WEIGHT_TITLE)
        ));

        if (author != null && !author.isBlank()) {
            functions.add(FunctionScore.of(f -> f
                    .filter(Query.of(q -> q.match(m -> m.field(FIELD_AUTHOR).query(author))))
                    .weight(WEIGHT_AUTHOR)
            ));
        }

        functions.add(FunctionScore.of(f -> f
                .scriptScore(s -> s
                        .script(script -> script
                                .inline(inline -> inline
                                        .source("cosineSimilarity(params.query_vector, '" + FIELD_VECTOR + "') + 1.0")
                                        .params("query_vector", JsonData.of(queryVector))
                                )
                        )
                )
                .weight(WEIGHT_VECTOR)
        ));

        return functions;
    }
}
