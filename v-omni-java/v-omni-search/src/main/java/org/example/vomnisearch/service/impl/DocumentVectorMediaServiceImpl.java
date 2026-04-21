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

    // 字段名常量
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_VECTOR = "embedding";
    private static final String FIELD_DELETED = "deleted"; // 修正后的字段名

    // 权重配置
    private static final double WEIGHT_TITLE = 0.35d;
    private static final double WEIGHT_AUTHOR = 0.4d;
    private static final double WEIGHT_VECTOR = 0.1d;

    @Resource
    private MinioService minioService;
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
     * 统一混合搜索：queryText 同时匹配标题和作者，支持分页
     * @param queryText 搜索文本 (同时匹配 FIELD_TITLE 和 FIELD_AUTHOR)
     * @param queryVector 向量数据
     * @param page 页码
     * @param size 每页大小
     */
    @Override
    public List<SearchMediaVo> hybridSearch(String queryText, float[] queryVector, int page, int size) throws IOException {
        // 1. 向量转换
        List<Float> vectorList = new ArrayList<>();
        if (queryVector != null) {
            for (float v : queryVector) vectorList.add(v);
        }

        // 2. 基础过滤：未删除
        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        // 3. 构建核心查询逻辑 (Function Score)
        // 我们将标题匹配、作者匹配和向量检索(如果有) 组合在一起应用权重
        Query functionScoreQuery = Query.of(q -> q.functionScore(fs -> fs
                .query(filterQuery) // 在未删除的基础上评分
                .functions(f -> f
                        // 标题权重：WEIGHT_TITLE (0.35)
                        .filter(Query.of(q1 -> q1.match(m -> m.field(FIELD_TITLE).query(queryText))))
                        .weight(WEIGHT_TITLE)
                )
                .functions(f -> f
                        // 作者权重：WEIGHT_AUTHOR (0.4)
                        .filter(Query.of(q2 -> q2.match(m -> m.field(FIELD_AUTHOR).query(queryText))))
                        .weight(WEIGHT_AUTHOR)
                )
                .scoreMode(FunctionScoreMode.Sum)    // 各个函数得分相加
                .boostMode(FunctionBoostMode.Replace) // 替换原始得分，完全由权重决定
        ));

        // 4. 构建 KNN 向量检索 (独立路径)
        // 注意：向量检索在 ES 8 中是独立平行的，我们通过 boost 来体现 WEIGHT_VECTOR (0.1)
        KnnQuery knnQuery = vectorList.isEmpty() ? null : KnnQuery.of(k -> k
                .field(FIELD_VECTOR)
                .queryVector(vectorList)
                .k(size)
                .numCandidates(100)
                .filter(filterQuery)
                .boost((float) WEIGHT_VECTOR) // 向量权重：0.1
        );

        // 5. 执行搜索与分页
        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(functionScoreQuery) // 文本权重查询
                .knn(knnQuery)            // 向量权重查询
                .from((page - 1) * size)
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        // 6. 结果映射
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
