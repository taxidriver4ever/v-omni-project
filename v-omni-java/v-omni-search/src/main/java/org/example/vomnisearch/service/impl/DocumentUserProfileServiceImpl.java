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

/**
 * 用户长期画像服务
 * 职能：仅负责 512 维原始向量的存储与读取，不参与任何权重计算
 */
@Slf4j
@Service
public class DocumentUserProfileServiceImpl implements DocumentUserProfileService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX_NAME = "user_profile_index";

    /**
     * 获取 Q (Query Vector): 用于推荐请求的 q_5_videos 字段
     */
    @Override
    public float[] getUserQueryVector(String userId) throws IOException {
        return getUserProfile(userId)
                .map(DocumentUserProfilePo::getInterestVector)
                .orElseGet(() -> {
                    log.info("用户 {} 无画像状态，返回 512 维零向量", userId);
                    return new float[512];
                });
    }

    /**
     * 获取长期头 K/V: 用于推荐请求的 long_k 和 long_v 字段
     */
    @Override
    public float[][] getLongTermKV(String userId) throws IOException {
        return getUserProfile(userId)
                .map(DocumentUserProfilePo::getReshapedMatrix)
                .orElseGet(() -> {
                    log.info("用户 {} 无长期矩阵，返回 64x512 零矩阵", userId);
                    return new float[64][512];
                });
    }

    // 辅助获取完整 PO
    private Optional<DocumentUserProfilePo> getUserProfile(String userId) throws IOException {
        GetResponse<DocumentUserProfilePo> response = client.get(g -> g
                .index(INDEX_NAME)
                .id(userId), DocumentUserProfilePo.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    /**
     * 局部更新用户画像：只更新 512 维实时兴趣向量，不影响长期矩阵字段
     */
    @Override
    public void updateUserProfile(String userId, float[] newInterestVector, Date date) throws IOException {
        try {
            // 1. 创建一个只包含需要更新字段的 PO 对象
            DocumentUserProfilePo partialPo = new DocumentUserProfilePo();
            partialPo.setInterestVector(newInterestVector);
            for(float i : newInterestVector){
                System.out.println(i + " ");
            }
            partialPo.setUpdateTime(date);

            // 2. 使用 update 接口进行局部合并
            client.update(u -> u
                            .index(INDEX_NAME)
                            .id(userId)
                            .doc(partialPo) // doc() 方法会自动处理局部字段合并
                            .docAsUpsert(true), // 如果用户文档不存在，则直接创建
                    DocumentUserProfilePo.class
            );

            log.debug("用户 {} 的实时兴趣向量已局部更新至 ES", userId);
        } catch (IOException e) {
            log.error("ES画像局部更新失败, userId: {}", userId, e);
            throw e;
        }
    }

    /**
     * 局部更新周度行为矩阵：只更新 64*512 矩阵，不影响实时兴趣向量
     */
    @Override
    public void updateWeeklyMatrix(String userId, float[] flattenedMatrix) throws IOException {
        try {
            DocumentUserProfilePo partialPo = new DocumentUserProfilePo();
            partialPo.setClusterVectors(flattenedMatrix);
            partialPo.setUpdateTime(new Date());

            client.update(u -> u
                            .index(INDEX_NAME)
                            .id(userId)
                            .doc(partialPo)
                            .docAsUpsert(true),
                    DocumentUserProfilePo.class
            );
            log.info("✅ 用户 {} 的周度行为矩阵已局部更新", userId);
        } catch (IOException e) {
            log.error("❌ 矩阵局部更新失败", e);
            throw e;
        }
    }

    /**
     * 获取纯向量数据（供召回模块使用）
     */
    @Override
    public float[] getUserInterestVector(String userId) throws IOException {
        return getUserProfile(userId)
                .map(DocumentUserProfilePo::getInterestVector)
                .orElse(new float[0]);
    }
}