package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.GetResponse;
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
import org.example.vomnisearch.service.UserRecommendationRedisService;
import org.example.vomnisearch.vo.RecommendMediaVo;
import org.example.vomnisearch.vo.SearchMediaVo;
import org.redisson.api.RBloomFilter;
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

    @Resource
    private UserRecommendationRedisService redisService;

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

    /**
     * 为未登录用户或冷启动场景提供的随机推荐
     * @param size 需要返回的视频数量 (如 14)
     * @return 随机视频列表
     */
    @Override
    public List<RecommendMediaVo> recommendRandom(int size) throws IOException {
        // 1. 构建过滤条件：只看没被删除的视频
        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        // 2. 构建随机查询请求
        // 使用 function_score 的 random_score 保证每次请求结果的随机性
        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.functionScore(fs -> fs
                        .query(filterQuery)
                        .functions(f -> f.randomScore(rs -> rs)) // 随机打分
                        .boostMode(FunctionBoostMode.Replace)   // 完全用随机分代替相关性分
                ))
                .size(size)
        );

        // 3. 执行查询
        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        // 4. 封装结果 (由于代码重复，建议将封装逻辑提取成私有方法)
        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(this::convertToVo) // 提取出来的封装逻辑
                .collect(Collectors.toList());
    }

    /**
     * 提取出来的 VO 转换逻辑，供多个推荐方法公用
     */
    private RecommendMediaVo convertToVo(DocumentVectorMediaPo po) {
        RecommendMediaVo vo = new RecommendMediaVo();
        vo.setMediaId(po.getId());
        vo.setTitle(po.getTitle());
        vo.setAuthor(po.getAuthor());
        vo.setUserId(String.valueOf(po.getUserId()));
        vo.setLikeCount(po.getLikeCount());
        vo.setCommentCount(po.getCommentCount());
        vo.setCollectionCount(po.getCollectionCount());
        vo.setCoverUrl(minioService.generateCoverUrl(po.getCoverPath()));
        vo.setAvatarUrl(minioService.generateAvatarUrl(po.getAvatarPath()));
        return vo;
    }

    @Override
    public List<RecommendMediaVo> recommendByInterest(float[] interestVector, int size, RBloomFilter<String> bloomFilter,
                                                      String userId) throws IOException {
        if (interestVector == null || interestVector.length == 0) {
            log.warn("兴趣向量为空，执行默认热门召回");
            return Collections.emptyList();
        }

        // 1. 获取近期已读 ID 列表（建议从 Redis ZSet 获取最近 500 个）
        List<String> excludeIds = redisService.getRecentSeenIds(userId, 500);

        // 2. 转换向量格式
        List<Float> vectorList = new ArrayList<>();
        for (float v : interestVector) vectorList.add(v);

        // 3. 核心改进：构建 ES 侧的“预过滤”条件
        // 排除已删除 + 排除近期已看
        Query filterQuery = Query.of(f -> f.bool(b -> b
                .must(t -> t.term(m -> m.field(FIELD_DELETED).value(false)))
                .mustNot(m -> m.ids(i -> i.values(excludeIds)))
        ));

        // 4. 构建多路 KNN 推荐
        // 策略：k 值设大一点（比如 3 倍 size），给 Java 过滤留出余量
        int recallSize = size * 3;
        List<KnnQuery> knnQueries = Arrays.asList(
                KnnQuery.of(k -> k
                        .field(FIELD_TEXT_VECTOR)
                        .queryVector(vectorList)
                        .k(recallSize) // 增大单路召回数
                        .numCandidates(200) // 增加候选者数量提高准确度
                        .boost(0.6f)
                        .filter(filterQuery)),
                KnnQuery.of(k -> k
                        .field(FIELD_VIDEO_VECTOR)
                        .queryVector(vectorList)
                        .k(recallSize)
                        .numCandidates(200)
                        .boost(0.4f)
                        .filter(filterQuery))
        );

        // 5. 发起请求：请求的总量也要稍微多一点
        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .knn(knnQueries)
                .size(recallSize)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        // 6. 最终过滤与截断
        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                // 第二道防线：用全量布隆过滤器进行最后的判定
                .filter(po -> !bloomFilter.contains(po.getId()))
                .map(this::convertToVo)
                .limit(size) // 👈 无论召回多少，最终只给前端 size (如 14) 个
                .collect(Collectors.toList());
    }

    /**
     * 判断视频是否存在且未被逻辑删除
     */
    @Override
    public boolean availableMedia(String mediaId) throws IOException {
        SearchResponse<Void> response = client.search(s -> s
                        .index(INDEX)
                        .query(q -> q.bool(b -> b
                                .must(m -> m.ids(i -> i.values(mediaId)))
                                .must(m -> m.term(t -> t.field(FIELD_DELETED).value(false)))
                        ))
                        .size(0), // 只要 count，不需要 source
                Void.class
        );
        if (response.hits().total() != null) {
            return response.hits().total().value() > 0;
        }
        return false;
    }

    @Override
    public DocumentVectorMediaPo getVectorById(String mediaId) throws IOException {
        if (mediaId == null || mediaId.isBlank()) return null;

        GetResponse<DocumentVectorMediaPo> response = client.get(g -> g
                        .index(INDEX)
                        .id(mediaId)
                        // 直接调用 sourceIncludes，不用写 source(...)
                        .sourceIncludes(FIELD_VIDEO_VECTOR, FIELD_TEXT_VECTOR),
                DocumentVectorMediaPo.class
        );

        return response.source();
    }
}