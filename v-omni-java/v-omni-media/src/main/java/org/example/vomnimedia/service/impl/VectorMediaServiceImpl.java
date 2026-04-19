package org.example.vomnimedia.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.service.VectorMediaService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class VectorMediaServiceImpl implements VectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    /**
     * ① 全量写入/覆盖更新
     */
    @Override
    public void upsert(DocumentVectorMediaPo doc) throws IOException {
        try {
            // 确保 deleted 状态明确，不传 null 到 ES
            if (doc.getDeleted() == null) {
                doc.setDeleted(false);
            }

            client.index(i -> i
                    .index(INDEX)
                    .id(doc.getId())
                    .document(doc)
            );
            log.info("ES document upserted: index={}, id={}", INDEX, doc.getId());
        } catch (Exception e) {
            log.error("ES upsert failed for id: {}", doc.getId(), e);
            throw new IOException("ES写入失败", e);
        }
    }

    /**
     * ② 局部更新字段
     */
    @Override
    public void updateFields(String id, Map<String, Object> fields) throws IOException {
        if (fields == null || fields.isEmpty()) return;

        try {
            client.update(u -> u
                            .index(INDEX)
                            .id(id)
                            .doc(fields),
                    Object.class
            );
        } catch (Exception e) {
            log.error("ES update fields failed for id: {}", id, e);
            throw new IOException("ES字段更新失败", e);
        }
    }

    /**
     * ③ 逻辑删除
     * 将字段名修改为 "deleted"，与 Mapping 和 PO 保持一致
     */
    @Override
    public void deleteById(String id) throws IOException {
        try {
            // 修正：Key 从 "is_deleted" 改为 "deleted"
            Map<String, Object> deleteMap = Collections.singletonMap("deleted", true);

            client.update(u -> u
                            .index(INDEX)
                            .id(id)
                            .doc(deleteMap),
                    Object.class
            );
            log.info("ES document logically deleted (deleted=true): id={}", id);
        } catch (Exception e) {
            log.error("ES logical delete failed for id: {}", id, e);
            throw new IOException("逻辑删除文档失败，id: " + id, e);
        }
    }
}
