package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.Script;
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
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.service.VectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class VectorMediaServiceImpl implements VectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    // 字段名常量（根据实际索引定义调整）
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_AUTHOR = "author";  // 用户账号/昵称字段
    private static final String FIELD_VECTOR = "embedding";    // 视频向量字段

    // 权重配置（可外部化）
    private static final double WEIGHT_TITLE = 0.35d;
    private static final double WEIGHT_AUTHOR = 0.4d;
    private static final double WEIGHT_VECTOR = 0.1d;

    /**
     * ① 全量写入（第一次进入 ES 用）
     */
    @Override
    public void upsert(DocumentVectorMediaPo doc) throws IOException {
        client.index(i -> i
                .index(INDEX)
                .id(doc.getId())
                .document(doc)
        );
    }

    /**
     * ② 局部更新（推荐使用）
     */
    @Override
    public void updateFields(String id, Map<String, Object> fields) throws IOException {

        if (fields == null || fields.isEmpty()) return;

        client.update(u -> u
                        .index(INDEX)
                        .id(id)
                        .doc(fields),
                Object.class
        );
    }

    /**
     * 混合搜索：标题模糊匹配(30%) + 作者账号匹配(20%) + 语义向量相似度(30%)
     *
     * @param queryText   用户输入的搜索关键词
     * @param queryVector 查询向量（与视频向量同维度）
     * @param author      作者账号精确匹配（可选，传 null 则忽略）
     * @param size        返回结果数量
     * @return 搜索结果列表
     */
    @Override
    public List<String> hybridSearchIds(String queryText, float[] queryVector, String author, int size) throws IOException {

        // 1. 处理 float[] 转为 List<Float>，解决 queryVector 报错
        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) {
            vectorList.add(v);
        }

        // 2. 构建文本查询
        Query textQuery = Query.of(q -> q
                .bool(b -> b
                        .should(s -> s.match(m -> m.field("title").query(queryText).boost(2.0f)))
                        .should(s -> s.match(m -> m.field("author").query(author).boost(1.0f)))
                )
        );

        // 3. 构建 KNN 查询
        KnnQuery knnQuery = KnnQuery.of(k -> k
                .field("embedding")
                .queryVector(vectorList) // 传入处理后的 List<Float>
                .k(10)
                .numCandidates(15)
                .boost(0.8f)
        );

        // 4. 发送搜索请求，修正 source 写法
        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(textQuery)
                .knn(knnQuery)
                .size(size)
                // 修正：使用 source(sc -> sc.fetch(false)) 关闭 source 抓取
                .source(SourceConfig.of(sc -> sc.fetch(false)))
        );

        SearchResponse<Void> response = client.search(request, Void.class);

        return response.hits().hits().stream()
                .map(Hit::id)
                .collect(Collectors.toList());
    }


    /**
     * 混合搜索：返回完整的文档对象（包含标题、作者、向量等）
     *
     * @param queryText   用户输入的搜索关键词
     * @param queryVector 查询向量（与视频向量同维度）
     * @param author      作者账号精确匹配（可选，传 null 则忽略）
     * @param size        返回结果数量
     * @return 搜索结果列表（完整对象）
     */
    @Override
    public List<DocumentVectorMediaPo> hybridSearch(String queryText,
                                                    float[] queryVector,
                                                    String author,
                                                    int size) throws IOException {
        // 构建 function_score 查询
        FunctionScoreQuery functionScoreQuery = FunctionScoreQuery.of(fs -> fs
                .query(q -> q.bool(b -> b))
                .functions(buildScoreFunctions(queryText, queryVector, author))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Replace)
        );

        // 构建搜索请求，指定返回 _source 字段
        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.functionScore(functionScoreQuery))
                .size(size)
        );

        // 指定文档类型为 DocumentVectorMediaPo
        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        // 提取 _source 内容
        return response.hits().hits().stream()
                .map(Hit::source)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> analyzeText(String text) throws IOException {
        // 构造请求，指定使用 ik_smart (粗粒度) 或 ik_max_word (细粒度)
        AnalyzeRequest request = AnalyzeRequest.of(a -> a
                .index(INDEX) // 可选，如果指定了索引，会使用该索引定义的词典
                .analyzer("ik_smart")
                .text(text)
        );

        AnalyzeResponse response = client.indices().analyze(request);

        // 提取分词结果
        return response.tokens().stream()
                .map(AnalyzeToken::token)
                .toList();
    }


    /**
     * 构建各评分函数列表
     */
    private List<FunctionScore> buildScoreFunctions(String queryText, float[] queryVector, String author) {
        List<FunctionScore> functions = new ArrayList<>();

        // 1. 标题模糊匹配得分 (BM25)，权重 0.3
        functions.add(FunctionScore.of(f -> f
                .filter(Query.of(q -> q.match(m -> m.field(FIELD_TITLE).query(queryText))))
                .weight(WEIGHT_TITLE)
        ));

        // 2. 作者账号精确/分词匹配，权重 0.2
        if (author != null && !author.isBlank()) {
            functions.add(FunctionScore.of(f -> f
                    .filter(Query.of(q -> q.match(m -> m.field(FIELD_AUTHOR).query(author))))
                    .weight(WEIGHT_AUTHOR)
            ));
        } else {
            // 如果没传作者，权重直接补到标题上，保持总分归一
            functions.add(FunctionScore.of(f -> f
                    .filter(Query.of(q -> q.matchAll(m -> m)))
                    .weight(0.0d)
            ));
        }

        // 3. 语义向量相似度 (余弦相似度)，权重 0.3
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

    /**
     * 简化版：只按文本搜索（标题 + 作者），用于纯关键词场景
     */
    @Override
    public List<DocumentVectorMediaPo> textSearch(String queryText, String author, int size) throws IOException {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
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


}
