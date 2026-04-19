package org.example.vomniinteract.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.po.UserLikeDocumentPo;
import org.example.vomniinteract.service.UserLikeDocumentService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserLikeDocumentServiceImpl implements UserLikeDocumentService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX = "v_omni_user_like";

    @Override
    public void upsert(UserLikeDocumentPo doc) throws IOException {
        try {
            // 确保状态正确
            if (doc.getDeleted() == null) {
                doc.setDeleted(false);
            }
            // ID 强约束为 userId_mediaId，确保幂等
            String docId = doc.getUserId() + "_" + doc.getMediaId();
            doc.setId(docId);

            client.index(i -> i
                    .index(INDEX)
                    .id(docId)
                    .document(doc)
            );
            log.info("点赞文档写入成功: {}", docId);
        } catch (Exception e) {
            log.error("点赞文档写入失败, doc: {}", doc, e);
            throw new IOException("ES写入异常", e);
        }
    }

    @Override
    public void delete(String userId, String mediaId) throws IOException {
        String docId = userId + "_" + mediaId;
        try {
            // 逻辑删除：仅更新 deleted 字段
            // 注意：这里的 Key 必须对齐 ES 中的下划线字段名 "deleted"
            Map<String, Object> updateMap = Collections.singletonMap("deleted", true);

            client.update(u -> u
                            .index(INDEX)
                            .id(docId)
                            .doc(updateMap),
                    Object.class
            );
            log.info("点赞文档逻辑删除成功: {}", docId);
        } catch (Exception e) {
            log.error("点赞文档逻辑删除失败, id: {}", docId, e);
            throw new IOException("ES逻辑删除异常", e);
        }
    }
}
