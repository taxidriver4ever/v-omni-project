package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.service.DocumentVectorMediaService;
import co.elastic.clients.json.JsonData;
import org.example.vomniinteract.vo.RecommendMediaVo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentVectorMediaServiceImpl implements DocumentVectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    private static final String FIELD_VIDEO_VECTOR = "video_embedding";

    /**
     * 【补充方法】根据 ID 获取视频文档（包含向量）
     * 用于 InteractConsumer 获取特征向量，推动兴趣演化
     */
    @Override
    public DocumentVectorMediaPo getById(String id) {
        try {
            GetResponse<DocumentVectorMediaPo> response = client.get(g -> g
                            .index(INDEX)
                            .id(id),
                    DocumentVectorMediaPo.class
            );

            if (response.found()) {
                return response.source();
            }
            return null;
        } catch (IOException e) {
            log.error("ES 获取视频向量异常, id: {}", id, e);
            return null;
        }
    }

    /**
     * 根据 ID 获取推荐视图对象
     * 实现了从持久层 PO 到展示层 VO 的转换，过滤掉大体积的向量数据
     */
    @Override
    public RecommendMediaVo getRecommendVoById(String id) {
        // 1. 调用已有的 getById 方法获取 ES 文档
        DocumentVectorMediaPo po = this.getById(id);

        if (po == null) {
            log.warn("未能在 ES 中找到 ID 为 {} 的视频文档", id);
            return null;
        }

        // 2. 转换为 VO
        RecommendMediaVo vo = new RecommendMediaVo();
        vo.setMediaId(po.getId());
        vo.setTitle(po.getTitle());
        vo.setUserId(po.getUserId());
        vo.setAuthor(po.getAuthor());

        // 处理计数字段，防止空指针（虽然 ES 默认通常有值）
        vo.setLikeCount(po.getLikeCount() != null ? po.getLikeCount() : 0);
        vo.setCommentCount(po.getCommentCount() != null ? po.getCommentCount() : 0);
        vo.setCollectionCount(po.getCollectionCount() != null ? po.getCollectionCount() : 0);

        // 路径转换逻辑：假设你的 VO 需要的是完整的 URL
        // 这里你可以根据实际的域名配置进行拼接
        vo.setCoverUrl(po.getCoverPath());
        vo.setAvatarUrl(po.getAvatarPath());

        // 注意：mediaUrl 在你的 PO 中似乎没有对应字段，可能需要从其他服务获取或拼接
        // vo.setMediaUrl("https://cdn.vomni.com/video/" + po.getId());

        return vo;
    }

    /**
     * 局部更新方法
     */
    @Override
    public void updateFields(String id, Map<String, Object> fields) throws IOException {
        if (fields == null || fields.isEmpty()) return;
        client.update(u -> u.index(INDEX).id(id).doc(fields), Object.class);
    }

    /**
     * 批量更新互动计数（点赞、收藏、评论数）
     * 使用 Painless 脚本保证原子性自增
     */
    @Override
    public void bulkUpdateCounts(Map<String, Map<String, Integer>> bulkUpdates) {
        if (bulkUpdates == null || bulkUpdates.isEmpty()) return;

        BulkRequest.Builder br = new BulkRequest.Builder();

        bulkUpdates.forEach((mediaId, fields) -> {
            StringBuilder scriptSource = new StringBuilder();
            Map<String, JsonData> params = new HashMap<>();

            fields.forEach((fieldName, change) -> {
                // 更加严谨的脚本：如果字段不存在则初始化，存在则累加
                scriptSource.append("ctx._source.").append(fieldName)
                        .append(" = (ctx._source.").append(fieldName).append(" ?: 0) + params.")
                        .append(fieldName).append("; ");
                params.put(fieldName, JsonData.of(change));
            });

            br.operations(op -> op
                    .update(u -> u
                            .index(INDEX)
                            .id(mediaId)
                            .action(a -> a
                                    .script(s -> s
                                            .inline(i -> i
                                                    .source(scriptSource.toString())
                                                    .params(params)
                                            )
                                    )
                                    // 若文档不存在则不执行，防止产生没有视频信息的脏计数文档
                                    .upsert(null)
                            )
                    )
            );
        });

        executeBulk(br, "同步视频互动计数");
    }

    @Override
    public List<Float> getVectorByMediaId(String mediaId) throws IOException {
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

        return vVec;
    }

    /**
     * 通用 Bulk 执行器
     */
    private void executeBulk(BulkRequest.Builder br, String actionName) {
        try {
            BulkResponse result = client.bulk(br.build());
            if (result.errors()) {
                result.items().stream()
                        .filter(item -> item.error() != null)
                        .forEach(item -> log.warn("{} 失败, id: {}, 原因: {}",
                                actionName, item.id(), item.error().reason()));
            } else {
                log.info("{} 执行成功, 数量: {}", actionName, result.items().size());
            }
        } catch (IOException e) {
            log.error("{} 执行异常", actionName, e);
        }
    }
}