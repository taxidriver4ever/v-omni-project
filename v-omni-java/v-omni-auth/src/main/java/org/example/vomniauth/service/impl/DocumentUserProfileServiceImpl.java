package org.example.vomniauth.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.po.DocumentUserProfilePo;
import org.example.vomniauth.service.DocumentUserProfileService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Date;
import java.util.Random;

@Slf4j
@Service
public class DocumentUserProfileServiceImpl implements DocumentUserProfileService {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX_NAME = "user_profile_index";
    private static final int VECTOR_DIM = 512;
    private static final int CLUSTER_SIZE = 64;

    /**
     * 用户注册时调用：创建初始画像文档（采用随机冷启动策略）
     */
    @Override
    public void createProfileOnRegistration(String userId, Date registrationDate) {
        try {
            // 1. 创建长期矩阵 (64 * 512)
            // 长期矩阵通常在演化逻辑触发前保持全 0 是安全的，因为它不直接用于 ES 的 Cosine 检索
            float[] zeroCluster = new float[CLUSTER_SIZE * VECTOR_DIM];

            // 2. 创建兴趣向量 q (512) - 使用随机初始化打破对称性
            float[] initialInterest = generateColdStartVector(VECTOR_DIM);

            DocumentUserProfilePo po = new DocumentUserProfilePo();
            po.setUserId(userId);
            po.setUpdateTime(registrationDate);
            po.setInterestVector(initialInterest);
            po.setClusterVectors(zeroCluster);

            // 3. 写入 ES
            client.index(i -> i
                    .index(INDEX_NAME)
                    .id(userId)
                    .document(po)
            );

            log.info("🚀 用户 {} 注册成功。冷启动向量已生成，模长: {}", userId, calculateMagnitude(initialInterest));
        } catch (IOException e) {
            log.error("❌ 初始化用户画像失败: userId={}, 错误={}", userId, e.getMessage());
        }
    }

    /**
     * 生成随机冷启动向量
     * 策略：生成一个非常小的随机向量，确保模长不为 0
     */
    private float[] generateColdStartVector(int dim) {
        float[] vector = new float[dim];
        Random random = new Random();

        for (int i = 0; i < dim; i++) {
            // 产生 -0.01 到 0.01 之间的随机数
            // 这种分布可以让新用户的初始推荐具有微小的随机差异
            vector[i] = (random.nextFloat() - 0.5f) * 0.02f;
        }

        // 安全检查：万一全是 0（概率极低），强制首位偏移
        if (calculateMagnitude(vector) == 0) {
            vector[0] = 1e-6f;
        }

        return vector;
    }

    /**
     * 计算向量模长，用于验证
     */
    private double calculateMagnitude(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }
}