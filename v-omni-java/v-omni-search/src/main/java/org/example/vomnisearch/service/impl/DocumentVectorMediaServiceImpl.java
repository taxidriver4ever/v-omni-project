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
import java.nio.ByteBuffer;
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
    private static final String FIELD_VIDEO_VECTOR = "video_embedding"; // 统一使用融合后的 512D 向量

    // 权重配置：混合检索配比
    private static final float WEIGHT_QUERY_MATCH = 0.3f;   // 文本关键词权重
    private static final float WEIGHT_VIDEO_VECTOR = 0.7f;  // 融合向量权重

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
     * 核心方法：混合搜索（文本 + 融合向量）
     */
    @Override
    public List<SearchMediaVo> hybridSearch(String queryText, float[] queryVector, int page, int size) throws IOException {
        List<Float> vectorList = new ArrayList<>();
        if (queryVector != null) {
            for (float v : queryVector) vectorList.add(v);
        }

        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        // 文本关键词匹配查询
        Query textMatchQuery = Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field(FIELD_TITLE).query(queryText).boost(2.0f)))
                .should(s -> s.match(m -> m.field(FIELD_AUTHOR).query(queryText)))
                .minimumShouldMatch("1")
                .filter(filterQuery)
        ));

        // 向量 KNN 检索 (单路融合向量)
        List<KnnQuery> knnQueries = new ArrayList<>();
        if (!vectorList.isEmpty()) {
            knnQueries.add(KnnQuery.of(k -> k
                    .field(FIELD_VIDEO_VECTOR)
                    .queryVector(vectorList)
                    .k(size)
                    .numCandidates(100)
                    .boost(WEIGHT_VIDEO_VECTOR)
                    .filter(filterQuery)
            ));
        }

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.bool(b -> b
                        .must(textMatchQuery)
                        .boost(WEIGHT_QUERY_MATCH)
                ))
                .knn(knnQueries)
                .from((page - 1) * size)
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

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
     * 随机推荐
     */
    @Override
    public List<RecommendMediaVo> recommendRandom(int size) throws IOException {
        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.functionScore(fs -> fs
                        .query(filterQuery)
                        .functions(f -> f.randomScore(rs -> rs))
                        .boostMode(FunctionBoostMode.Replace)
                ))
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(this::convertToVo)
                .collect(Collectors.toList());
    }

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

        List<String> excludeIds = redisService.getRecentSeenIds(userId, 500);

        List<Float> vectorList = new ArrayList<>();
        for (float v : interestVector) vectorList.add(v);

        Query filterQuery = Query.of(f -> f.bool(b -> b
                .must(t -> t.term(m -> m.field(FIELD_DELETED).value(false)))
                .mustNot(m -> m.ids(i -> i.values(excludeIds)))
        ));

        int recallSize = size * 3;

        // 单路 KNN 召回 (基于融合画像)
        KnnQuery videoKnn = KnnQuery.of(k -> k
                .field(FIELD_VIDEO_VECTOR)
                .queryVector(vectorList)
                .k(recallSize)
                .numCandidates(200)
                .filter(filterQuery)
        );

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .knn(videoKnn)
                .size(recallSize)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .filter(po -> !bloomFilter.contains(po.getId()))
                .map(this::convertToVo)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public boolean availableMedia(String mediaId) throws IOException {
        SearchResponse<Void> response = client.search(s -> s
                        .index(INDEX)
                        .query(q -> q.bool(b -> b
                                .must(m -> m.ids(i -> i.values(mediaId)))
                                .must(m -> m.term(t -> t.field(FIELD_DELETED).value(false)))
                        ))
                        .size(0),
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
                        .sourceIncludes(FIELD_VIDEO_VECTOR), // 仅查询单个向量字段
                DocumentVectorMediaPo.class
        );

        return response.source();
    }

    @Override
    public byte[] getVectorByMediaId(String mediaId) throws IOException {
        if (mediaId == null || mediaId.isBlank()) return null;

        GetResponse<DocumentVectorMediaPo> response = client.get(g -> g
                        .index(INDEX)
                        .id(mediaId)
                        .sourceIncludes(FIELD_VIDEO_VECTOR), // 仅查询单个向量字段
                DocumentVectorMediaPo.class
        );

        DocumentVectorMediaPo po = response.source();
        if (po == null || po.getVideoEmbedding() == null) {
            log.warn("MediaId: {} 向量数据不完整", mediaId);
            return null;
        }

        List<Float> vVec = po.getVideoEmbedding();

        if (vVec.size() != 512) {
            log.error("向量维度异常，预期512: video={}", vVec.size());
            return null;
        }

        // 512 floats * 4 bytes = 2048 bytes
        ByteBuffer buffer = ByteBuffer.allocate(512 * 4);
        float scaleFactor = 3.0f;

        for (Float f : vVec) {
            buffer.putFloat(f * scaleFactor);
        }

        return buffer.array();
    }

    @Override
    public List<RecommendMediaVo> searchByProfileVector(float[] userQueryVector, int size) throws IOException {
        if (userQueryVector == null || userQueryVector.length == 0) {
            log.warn("用户向量为空，降级为随机推荐");
            return recommendRandom(size);
        }

        List<Float> vectorList = new ArrayList<>(userQueryVector.length);
        for (float v : userQueryVector) {
            vectorList.add(v);
        }

        Query filterQuery = Query.of(f -> f.term(t -> t.field(FIELD_DELETED).value(false)));

        // 单路 KNN 根据画像召回
        KnnQuery videoKnn = KnnQuery.of(k -> k
                .field(FIELD_VIDEO_VECTOR)
                .queryVector(vectorList)
                .k(size)
                .numCandidates(100)
                .filter(filterQuery)
        );

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .knn(videoKnn)
                .size(size)
        );

        SearchResponse<DocumentVectorMediaPo> response = client.search(request, DocumentVectorMediaPo.class);

        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(this::convertToVo)
                .collect(Collectors.toList());
    }
}