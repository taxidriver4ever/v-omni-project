package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentMediaInteractionPo;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.service.DocumentMediaInteractionService;
import org.example.vomniinteract.vo.InteractionVo;
import org.example.vomniinteract.dto.InteractionTaskDto; // 建议放在dto包
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentMediaInteractionServiceImpl implements DocumentMediaInteractionService {

    @Resource
    private ElasticsearchClient client;
    private static final String INTERACT_INDEX = "user_media_interaction_index";
    private static final String MEDIA_INDEX = "vector_media_index";
    private final static String MEDIA_INFO_PREFIX = "interact:media:info:";


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 批量同步用户的交互记录 (新增点赞/取消点赞)
     * 对应消费者中的 interactionEsService.bulkSyncInteractions(esInteractionsToAdd, esInteractionsToDelete)
     *
     * @param addList   需要新增的交互记录
     * @param deleteIds 需要删除的交互记录文档 ID 列表
     */
    @Override
    public void bulkSyncInteractions(List<DocumentMediaInteractionPo> addList, List<String> deleteIds) {
        if ((addList == null || addList.isEmpty()) && (deleteIds == null || deleteIds.isEmpty())) {
            return;
        }

        List<BulkOperation> operations = new ArrayList<>();

        // 1. 处理新增记录 (Index Operation)
        // ES 的 Index 操作天然支持 Upsert：如果指定 ID 已存在则覆盖，不存在则新建。
        // 这完美契合你的重试/幂等性需求。
        if (addList != null) {
            for (DocumentMediaInteractionPo po : addList) {
                operations.add(new BulkOperation.Builder()
                        .index(idx -> idx
                                .index(INTERACT_INDEX)
                                .id(po.getId()) // 使用拼接好的唯一 ID: userId_mediaId_behavior
                                .document(po)      // 序列化 PO 对象写入 ES
                        )
                        .build());
            }
        }

        // 2. 处理删除记录 (Delete Operation)
        if (deleteIds != null) {
            for (String docId : deleteIds) {
                operations.add(new BulkOperation.Builder()
                        .delete(del -> del
                                .index(INTERACT_INDEX)
                                .id(docId) // 根据唯一 ID 删除文档
                        )
                        .build());
            }
        }

        // 3. 执行批量请求
        if (!operations.isEmpty()) {
            executeBulk(operations, "同步用户交互记录(user_media_interaction_index)");
        }
    }

    /**
     * 抽取公共的 Bulk 执行与结果解析方法
     */
    private void executeBulk(List<BulkOperation> operations, String actionName) {
        try {
            BulkRequest bulkRequest = BulkRequest.of(b -> b.operations(operations));
            BulkResponse response = client.bulk(bulkRequest);

            if (response.errors()) {
                response.items().forEach(item -> {
                    if (item.error() != null) {
                        log.error("ES [{}] 批量操作失败，文档ID: {}, 原因: {}",
                                actionName, item.id(), item.error().reason());
                    }
                });
            } else {
                log.info("ES [{}] 成功，共处理 {} 条操作", actionName, operations.size());
            }
        } catch (IOException e) {
            log.error("执行 ES Bulk [{}] 发生网络异常: ", actionName, e);
        }
    }


    /**
     * 从 ES 捞取交互记录列表 (点赞历史 ID)
     */
    @Override
    public List<DocumentMediaInteractionPo> findUserInteractionListFromEs(Long userId, String behavior, Integer page, Integer size) {
        try {
            int from = (page - 1) * size;
            SearchResponse<DocumentMediaInteractionPo> response = client.search(s -> s
                            .index(INTERACT_INDEX)
                            .from(from).size(size)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.term(t -> t.field("user_id").value(userId)))
                                    .must(m -> m.term(t -> t.field("behavior").value(behavior)))
                            ))
                            .sort(sort -> sort.field(f -> f.field("create_time").order(SortOrder.Desc))),
                    DocumentMediaInteractionPo.class
            );
            return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("ES查询点赞历史失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量查询视频详情 (用于 Redis 缺失时的补偿)
     */
    @Override
    public List<DocumentVectorMediaPo> findMediaListFromEs(List<String> mediaIds) {
        try {
            SearchResponse<DocumentVectorMediaPo> response = client.search(s -> s
                            .index(MEDIA_INDEX)
                            .query(q -> q.ids(i -> i.values(mediaIds))),
                    DocumentVectorMediaPo.class
            );
            return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("ES批量查询媒体详情失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 异步刷新前 30 条点赞记录到 ZSet
     */
    @Override
    public void asyncRefreshTop30Cache(Long userId, String zsetKey, String behavior) {
        CompletableFuture.runAsync(() -> {
            List<DocumentMediaInteractionPo> top30 = findUserInteractionListFromEs(userId, behavior, 1, 30);
            if (!top30.isEmpty()) {
                Set<ZSetOperations.TypedTuple<String>> tuples = top30.stream()
                        .map(po -> (ZSetOperations.TypedTuple<String>) new DefaultTypedTuple<>(
                                po.getMediaId(), (double) po.getCreateTime().getTime()))
                        .collect(Collectors.toSet());

                stringRedisTemplate.opsForZSet().add(zsetKey, tuples);
                stringRedisTemplate.opsForZSet().removeRange(zsetKey, 0, -31);
                stringRedisTemplate.expire(zsetKey, 7, TimeUnit.DAYS);
                log.info("异步回写用户[{}]点赞Top30缓存完成", userId);
            }
        });
    }

    /**
     * 异步回填媒体详情 Hash
     */
    @Override
    public void asyncUpdateMediaHash(DocumentVectorMediaPo po) {
        CompletableFuture.runAsync(() -> {
            String key = MEDIA_INFO_PREFIX + po.getId();
            Map<String, String> map = new HashMap<>();
            map.put("cover_path", po.getCoverPath());
            map.put("like_count", String.valueOf(po.getLikeCount()));
            stringRedisTemplate.opsForHash().putAll(key, map);
            stringRedisTemplate.expire(key, 24, TimeUnit.HOURS);
        });
    }

}
