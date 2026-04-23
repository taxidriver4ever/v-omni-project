package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.service.DocumentVectorMediaService;
import co.elastic.clients.json.JsonData;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentVectorMediaServiceImpl implements DocumentVectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

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
                // 脚本逻辑：ctx._source.like_count += params.like_count
                scriptSource.append("ctx._source.").append(fieldName).append(" += params.").append(fieldName).append("; ");
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
                                    // 若文档不存在不处理，防止因延迟导致的脏数据
                                    .upsert(null)
                            )
                    )
            );
        });

        executeBulk(br, "同步视频互动计数");
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