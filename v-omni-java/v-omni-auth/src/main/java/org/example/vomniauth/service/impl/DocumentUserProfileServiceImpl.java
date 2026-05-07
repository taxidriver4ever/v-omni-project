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
    private static final int CLUSTER_SIZE = 8;

    /**
     * 用户注册时调用：创建初始画像文档（采用随机冷启动策略）
     */
    @Override
    public void createProfileOnRegistration(String userId, Date registrationDate) {
        try {
            // 1. 创建长期矩阵 (8 * 512)
            float[] zeroCluster = initFlatKVector(CLUSTER_SIZE, VECTOR_DIM);

            // 2. 创建兴趣向量 q (512) - 使用随机初始化打破对称性
            float[] initialInterest = generateColdStartVector(VECTOR_DIM);

            DocumentUserProfilePo po = new DocumentUserProfilePo();
            po.setUserId(userId);
            po.setUpdateTime(registrationDate);
            po.setInterestVector(initialInterest);
            po.setClusterVectors(zeroCluster);
            po.setSex(0);
            po.setBirthYear(0);
            po.setCountry("未知");
            po.setProvince("未知");
            po.setCity("未知");

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
     * 更新用户人口统计学特征（性别、年龄、地理位置）
     * 采用局部更新模式，不影响已有的向量数据
     */
    @Override
    public void updateUserDemographics(String userId, int sex, int birthYear, String country, String province, String city) {
        try {
            // 1. 构建局部更新文档
            // 使用 Map 仅包含需要修改的字段，防止覆盖向量数据
            DocumentUserProfilePo updateData = new DocumentUserProfilePo();
            updateData.setSex(sex);
            updateData.setBirthYear(birthYear);
            updateData.setCountry(country != null ? country : "未知");
            updateData.setProvince(province != null ? province : "未知");
            updateData.setCity(city != null ? city : "未知");
            updateData.setUpdateTime(new Date());

            // 2. 执行 ES 局部更新 (Update Request)
            client.update(u -> u
                            .index(INDEX_NAME)
                            .id(userId)
                            .doc(updateData) // 这里 doc 方法会自动识别非空字段进行 merge
                            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True) // 根据业务实时性需求决定是否 refresh
                    , DocumentUserProfilePo.class);

            log.info("✅ 用户画像属性更新成功: userId={}, region={}-{}-{}", userId, country, province, city);
        } catch (IOException e) {
            log.error("❌ 更新用户画像属性失败: userId={}, 错误={}", userId, e.getMessage());
        }
    }

    /**
     * 生成随机冷启动向量
     * 策略：生成一个非常小的随机向量，确保模长不为 0
     */
    private float[] generateColdStartVector(int dim) {
        float[] vector = new float[dim];
        Random random = new Random();
        double squaredSum = 0.0;

        for(int i = 0; i < dim; i++) {
            vector[i] = (float) random.nextGaussian();
            squaredSum += vector[i] * vector[i];
        }

        float norm = (float) Math.sqrt(squaredSum);

        // 3. 归一化：将向量投影到单位超球面
        // 注意：防止模长极小时出现除以零（虽然概率极低）
        float epsilon = 1e-10f;
        for (int i = 0; i < dim; i++) {
            vector[i] /= (norm + epsilon);
        }

        return vector;
    }

    private float[] initFlatKVector(int numEntities, int dimension) {
        // 总长度 = 实体个数 * 每个实体的维度
        int totalLength = numEntities * dimension;
        float[] flatK = new float[totalLength];
        Random random = new Random();

        for (int i = 0; i < numEntities; i++) {
            int offset = i * dimension; // 当前段落的起始位置
            double segmentSquaredSum = 0;

            // 1. 对当前段落进行正态分布采样
            for (int j = 0; j < dimension; j++) {
                float val = (float) random.nextGaussian();
                flatK[offset + j] = val;
                segmentSquaredSum += val * val;
            }

            // 2. 对当前段落进行局部归一化
            float segmentNorm = (float) Math.sqrt(segmentSquaredSum) + 1e-10f;
            for (int j = 0; j < dimension; j++) {
                flatK[offset + j] /= segmentNorm;
            }
        }
        return flatK;
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