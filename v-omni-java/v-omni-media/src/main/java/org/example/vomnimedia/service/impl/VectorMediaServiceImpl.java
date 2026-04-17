package org.example.vomnimedia.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.service.VectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class VectorMediaServiceImpl implements VectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

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
     * ③ 兼容旧写法（不推荐直接用 PO update）
     */
    @Override
    public void update(@NotNull DocumentVectorMediaPo doc) throws IOException {

        Map<String, Object> map = new HashMap<>();

        if (doc.getTitle() != null) map.put("title", doc.getTitle());
        if (doc.getAuthor() != null) map.put("author", doc.getAuthor());
        if (doc.getEmbedding() != null) map.put("embedding", doc.getEmbedding());
        if (doc.getUrl() != null) map.put("url", doc.getUrl());

        updateFields(doc.getId(), map);
    }


}
