package org.example.vomnisearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.po.DocumentUserProfilePo;
import org.example.vomnisearch.service.DocumentUserProfileService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Date;
import java.util.Optional;

@Slf4j
@Service
public class DocumentUserProfileServiceImpl implements DocumentUserProfileService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX_NAME = "user_profile_index";

    @Override
    public Optional<DocumentUserProfilePo> getUserProfile(String userId) throws IOException {
        try {
            // 使用 ES 的 Get API，通过 ID 直接检索
            GetResponse<DocumentUserProfilePo> response = client.get(g -> g
                            .index(INDEX_NAME)
                            .id(userId),
                    DocumentUserProfilePo.class
            );

            if (response.found()) {
                return Optional.ofNullable(response.source());
            } else {
                log.warn("未找到用户画像数据: userId = {}", userId);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.error("查询 ES 用户画像异常: {}", userId, e);
            throw e;
        }
    }

    @Override
    public void updateUserProfile(String userId, float[] currentQueryVector, float[] newInterestVector, Date date) throws IOException {
        try {
            DocumentUserProfilePo po = new DocumentUserProfilePo();
            po.setUserId(userId);
            po.setLastSearchVector(currentQueryVector);
            po.setInterestVector(newInterestVector);
            po.setUpdateTime(date); // 更新为当前时间

            // 写入 ES
            client.index(i -> i
                    .index(INDEX_NAME)
                    .id(userId) // 核心：使用 userId 作为文档 ID
                    .document(po)
            );

            log.info("✅ 用户画像已更新: userId = {}", userId);
        } catch (IOException e) {
            log.error("❌ 更新用户画像失败: userId = {}", userId, e);
            throw e;
        }
    }

    @Override
    public float[] getUserInterestVector(String userId) throws IOException {
        // 复用现有的查询逻辑获取整个 PO
        return getUserProfile(userId)
                .map(DocumentUserProfilePo::getInterestVector)
                .orElseGet(() -> {
                    log.warn("⚠️ 用户 {} 暂无兴趣向量，返回空向量", userId);
                    return new float[0];
                });
    }
}