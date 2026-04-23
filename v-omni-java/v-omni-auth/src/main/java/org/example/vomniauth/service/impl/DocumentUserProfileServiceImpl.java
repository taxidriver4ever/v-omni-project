package org.example.vomniauth.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.po.DocumentUserProfilePo;
import org.example.vomniauth.service.DocumentUserProfileService;
import org.example.vomniauth.util.VectorUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Date;

@Slf4j
@Service
public class DocumentUserProfileServiceImpl implements DocumentUserProfileService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX_NAME = "user_profile_index";

    /**
     * 用户注册时调用：创建初始画像文档
     */
    public void createProfileOnRegistration(String userId, Date registrationDate) {
        try {
            DocumentUserProfilePo po = new DocumentUserProfilePo();
            po.setUserId(userId);
            po.setUpdateTime(registrationDate);

            // --- 核心步骤：初始化向量防止 ES 报错 ---
            // 产生一个随机单位向量作为“种子”
            float[] initialVector = VectorUtil.generateRandomUnitVector(512);

            po.setLastSearchVector(initialVector); // 占位
            po.setInterestVector(initialVector);   // 初始兴趣坐标

            // 写入 ES，ID 显式设为 userId
            client.index(i -> i
                    .index(INDEX_NAME)
                    .id(userId)
                    .document(po)
            );

            log.info("🚀 用户 {} 注册成功，画像索引已初始化完成", userId);
        } catch (IOException e) {
            log.error("❌ 初始化用户画像失败: {}", userId, e);
        }
    }
}