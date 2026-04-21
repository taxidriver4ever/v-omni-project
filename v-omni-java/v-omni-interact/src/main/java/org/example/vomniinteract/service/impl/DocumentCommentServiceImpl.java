package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentCommentPo;
import org.example.vomniinteract.service.DocumentCommentService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentCommentServiceImpl implements DocumentCommentService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX_NAME = "user_media_comments_index";

    @Override
    public void saveComment(DocumentCommentPo po) {
        try {
            client.index(i -> i
                    .index(INDEX_NAME)
                    .id(po.getCommentId().toString())
                    .document(po)
            );
        } catch (IOException e) {
            log.error("ES存入评论失败", e);
        }
    }

    @Override
    public void deleteComment(Long commentId) {
        try {
            client.delete(d -> d.index(INDEX_NAME).id(commentId.toString()));
        } catch (IOException e) {
            log.error("ES删除评论失败", e);
        }
    }

    @Override
    public void updateCommentLikeCount(Long commentId, boolean isAdd) {
        String script = isAdd ? "ctx._source.like_count++" : "ctx._source.like_count--";
        try {
            client.update(u -> u
                            .index(INDEX_NAME)
                            .id(commentId.toString())
                            .script(s -> s.inline(i -> i.source(script)))
                    , DocumentCommentPo.class);
        } catch (IOException e) {
            log.error("更新评论点赞数失败", e);
        }
    }

    @Override
    public List<DocumentCommentPo> findTopLevelComments(Long mediaId, int page, int size) {
        try {
            SearchResponse<DocumentCommentPo> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.term(t -> t.field("media_id").value(mediaId)))
                                    .must(m -> m.term(t -> t.field("root_id").value(0))) // 只查一级
                            ))
                            .sort(sort -> sort.field(f -> f.field("like_count").order(SortOrder.Desc))) // 热门排序
                            .from((page - 1) * size)
                            .size(size)
                    , DocumentCommentPo.class);

            return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public long getTotalCommentCount(Long mediaId) {
        try {
            CountResponse count = client.count(c -> c
                    .index(INDEX_NAME)
                    .query(q -> q.term(t -> t.field("media_id").value(mediaId)))
            );
            return count.count();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public List<DocumentCommentPo> findRepliesByRootId(Long rootId) {
        try {
            SearchResponse<DocumentCommentPo> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.term(t -> t.field("root_id").value(rootId)))
                            .sort(sort -> sort.field(f -> f.field("create_time").order(SortOrder.Asc))) // 回复按时间正序
                    , DocumentCommentPo.class);
            return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    // 在 DocumentCommentServiceImpl 中补充
    @Override
    public void bulkProcessComments(List<DocumentCommentPo> saveList, List<Long> deleteList) {
        if (saveList.isEmpty() && deleteList.isEmpty()) return;

        BulkRequest.Builder br = new BulkRequest.Builder();

        // 批量新增
        for (DocumentCommentPo po : saveList) {
            br.operations(op -> op
                    .index(idx -> idx.index(INDEX_NAME).id(po.getCommentId().toString()).document(po))
            );
        }

        // 批量删除
        for (Long id : deleteList) {
            br.operations(op -> op
                    .delete(d -> d.index(INDEX_NAME).id(id.toString()))
            );
        }

        try {
            client.bulk(br.build());
        } catch (IOException e) {
            log.error("ES批量同步评论失败", e);
        }
    }

    // 在 DocumentCommentServiceImpl 中新增批量更新点赞数方法
    @Override
    public void bulkUpdateCommentLikeCount(Map<Long, Integer> updateMap) {
        if (updateMap.isEmpty()) return;

        BulkRequest.Builder br = new BulkRequest.Builder();

        updateMap.forEach((commentId, change) -> {
            // 使用脚本进行原子自增/自减
            String scriptSource = "ctx._source.like_count += params.change";
            br.operations(op -> op
                    .update(u -> u
                            .index(INDEX_NAME)
                            .id(commentId.toString())
                            .action(a -> a
                                    .script(s -> s
                                            .inline(i -> i
                                                    .source(scriptSource)
                                                    .params("change", co.elastic.clients.json.JsonData.of(change))
                                            )
                                    )
                            )
                    )
            );
        });

        try {
            client.bulk(br.build());
        } catch (IOException e) {
            log.error("ES批量更新评论点赞数失败", e);
        }
    }



}
