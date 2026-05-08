package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.DocumentUserBehaviorHistoryPo;
import org.example.vomniinteract.service.DocumentUserBehaviorHistoryService;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUserBehaviorHistoryServiceImpl implements DocumentUserBehaviorHistoryService {

    private final ElasticsearchClient client;
    private static final String INDEX = "user_behavior_history_index";

    @Override
    public void saveBehavior(DocumentUserBehaviorHistoryPo po) {
        try {
            // 使用 ES 异步/同步写入。由于是流水数据，ID 可以让 ES 自动生成
            client.index(i -> i
                    .index(INDEX)
                    .document(po)
            );
        } catch (IOException e) {
            log.error("写入用户行为索引失败: {}", e.getMessage());
        }
    }
}