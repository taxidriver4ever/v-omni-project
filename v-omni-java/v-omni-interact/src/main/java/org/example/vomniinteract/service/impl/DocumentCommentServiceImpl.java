package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.dto.DoCommentDto;
import org.example.vomniinteract.po.CommentPo;
import org.example.vomniinteract.po.DocumentCommentPo;
import org.example.vomniinteract.service.DocumentCommentService;
import org.example.vomniinteract.vo.CommentVo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
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
    public List<CommentVo> findTopLevelComments(Long mediaId, int page, int size) {
        try {
            SearchResponse<CommentVo> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.term(t -> t.field("media_id").value(mediaId)))
                                    .must(m -> m.term(t -> t.field("root_id").value(0))) // 只查一级
                            ))
                            .sort(sort -> sort.field(f -> f.field("like_count").order(SortOrder.Desc))) // 热门排序
                            .from((page - 1) * size)
                            .size(size)
                    , CommentVo.class);

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
    public List<CommentVo> findRepliesByRootComments(Long rootId, int page, int size) {
        try {
            // 计算起始偏移量
            int from = (page - 1) * size;

            SearchResponse<CommentVo> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.term(t -> t.field("root_id").value(rootId)))
                            // 加入分页参数
                            .from(from)
                            .size(size)
                            // 排序逻辑保持不变
                            .sort(sort -> sort.field(f -> f.field("create_time").order(SortOrder.Asc)))
                    , CommentVo.class);

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("ES分页查询回复失败, rootId: {}", rootId, e);
            return Collections.emptyList();
        }
    }


    @Override
    public void bulkProcessComments(List<DocumentCommentPo> saveList, List<DoCommentDto> deleteMessages) {
        if (saveList.isEmpty() && deleteMessages.isEmpty()) return;

        BulkRequest.Builder br = new BulkRequest.Builder();

        // 1. 批量新增/更新
        for (DocumentCommentPo po : saveList) {
            br.operations(op -> op
                    .index(idx -> idx.index(INDEX_NAME).id(po.getCommentId().toString()).document(po))
            );
        }

        // 2. 区分删除逻辑
        List<Long> rootIdsForCascade = new ArrayList<>(); // 存根评论 ID，用于级联删除
        List<String> singleIdsForDelete = new ArrayList<>(); // 存普通 ID，用于直接删除

        for (DoCommentDto m : deleteMessages) {
            Long cId = Long.parseLong(m.getId());
            long rId = Long.parseLong(m.getRootId());

            if (rId == 0) {
                rootIdsForCascade.add(cId);
            } else {
                singleIdsForDelete.add(cId.toString());
            }
        }

        // 将普通删除加入 Bulk
        for (String id : singleIdsForDelete) {
            br.operations(op -> op.delete(d -> d.index(INDEX_NAME).id(id)));
        }

        try {
            // 执行 Bulk 操作
            if (!br.build().operations().isEmpty()) {
                client.bulk(br.build());
            }

            // 3. 处理级联删除 (DeleteByQuery)
            if (!rootIdsForCascade.isEmpty()) {
                client.deleteByQuery(dbq -> dbq
                        .index(INDEX_NAME)
                        .conflicts(Conflicts.Proceed)
                        .waitForCompletion(false) // 异步执行，不阻塞消费者
                        .query(q -> q.bool(b -> b
                                .should(s1 -> s1.terms(t -> t.field("root_id")
                                        .terms(v -> v.value(rootIdsForCascade.stream().map(FieldValue::of).toList()))))
                                .should(s2 -> s2.terms(t -> t.field("comment_id")
                                        .terms(v -> v.value(rootIdsForCascade.stream().map(FieldValue::of).toList()))))
                        ))
                );
            }
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
