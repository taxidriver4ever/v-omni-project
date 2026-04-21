package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentMediaInteractionPo;
import org.example.vomniinteract.service.DocumentMediaInteractionService;
import org.example.vomniinteract.vo.InteractionVo;
import org.example.vomniinteract.dto.InteractionTaskDto; // 建议放在dto包
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentMediaInteractionServiceImpl implements DocumentMediaInteractionService {

    @Autowired
    private ElasticsearchClient client;

    @Value("${minio.base-url}")
    private String minioBaseUrl;

    private static final String INDEX_NAME = "user_media_interaction_index";

    // 1. 批量插入视频文档 (初始化)
    @Override
    public void bulkInsertMediaDocs(List<DocumentMediaInteractionPo> pos) {
        if (pos == null || pos.isEmpty()) return;
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (DocumentMediaInteractionPo po : pos) {
            br.operations(op -> op
                    .index(idx -> idx.index(INDEX_NAME).id(po.getMediaId().toString()).document(po))
            );
        }
        executeBulk(br, "批量插入视频文档");
    }

    // 2. 批量物理删除视频文档
    @Override
    public void bulkDeleteMediaDocs(List<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return;
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (Long id : mediaIds) {
            br.operations(op -> op.delete(d -> d.index(INDEX_NAME).id(id.toString())));
        }
        executeBulk(br, "批量删除视频文档");
    }

    // 3. 批量处理互动 (点赞/收藏，削峰填谷核心)
    @Override
    public void bulkProcessInteractions(List<InteractionTaskDto> tasks) {
        if (tasks == null || tasks.isEmpty()) return;
        BulkRequest.Builder br = new BulkRequest.Builder();

        for (InteractionTaskDto task : tasks) {
            String script = getScript(task);

            br.operations(op -> op
                    .update(u -> u
                            .index(INDEX_NAME)
                            .id(task.getMediaId().toString())
                            .action(a -> a
                                    .script(s -> s.inline(i -> i.source(script).params("uid", JsonData.of(task.getUserId()))))
                                    .upsert(JsonData.of(DocumentMediaInteractionPo.builder()
                                            .mediaId(task.getMediaId()).likeCount(0).likeUserIds(new ArrayList<>()).collectUserIds(new ArrayList<>()).build()))
                            )
                    )
            );
        }
        executeBulk(br, "批量处理互动操作");
    }

    private static @NotNull String getScript(InteractionTaskDto task) {
        String arrayField = "like".equalsIgnoreCase(task.getActionType()) ? "like_user_ids" : "collect_user_ids";
        String countField = "like".equalsIgnoreCase(task.getActionType()) ? "like_count" : null;

        // 脚本保持原子性，防止重复点赞导致计数错误
        return task.getAdd()
                ? "if (!ctx._source." + arrayField + ".contains(params.uid)) { ctx._source." + arrayField + ".add(params.uid); " + (countField != null ? "ctx._source." + countField + "++;" : "") + " }"
                : "if (ctx._source." + arrayField + ".contains(params.uid)) { ctx._source." + arrayField + ".remove(ctx._source." + arrayField + ".indexOf(params.uid)); " + (countField != null ? "ctx._source." + countField + "--; " : "") + "}";
    }

    // 4. 分页查询
    @Override
    public List<InteractionVo> findUserInteractionList(Long userId, String actionType, int page, int size) {
        String filterField = "like".equalsIgnoreCase(actionType) ? "like_user_ids" : "collect_user_ids";
        try {
            SearchResponse<DocumentMediaInteractionPo> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.term(t -> t.field(filterField).value(userId)))
                            .from((page - 1) * size)
                            .size(size)
                    , DocumentMediaInteractionPo.class);

            return response.hits().hits().stream().map(hit -> {
                DocumentMediaInteractionPo po = hit.source();
                boolean hasLiked = po.getLikeUserIds() != null && po.getLikeUserIds().contains(userId);
                return InteractionVo.builder()
                        .mediaId(po.getMediaId())
                        .coverUrl(minioBaseUrl + "/v-omni-covers/" + po.getCoverPath())
                        .likeCount(po.getLikeCount())
                        .liked(hasLiked)
                        .build();
            }).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("ES查询异常", e);
            return Collections.emptyList();
        }
    }

    private void executeBulk(BulkRequest.Builder br, String actionName) {
        try {
            BulkResponse result = client.bulk(br.build());
            if (result.errors()) {
                log.warn("{} 部分执行失败", actionName);
            }
        } catch (IOException e) {
            log.error("{} 执行异常", actionName, e);
        }
    }
}
