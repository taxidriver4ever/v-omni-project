package org.example.vomnisearch.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Date;

/**
 * 用户画像持久化对象
 * 适配 Elasticsearch 存储规范：
 * 1. 使用 @JsonProperty 映射下划线字段
 * 2. 使用 Base64 编解码处理 binary 类型的 cluster_vectors
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 忽略 ES 结果中未在类中定义的额外字段
public class DocumentUserProfilePo {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("interest_vector")
    private float[] interestVector;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    @JsonProperty("update_time")
    private Date updateTime;

    // 业务逻辑使用的原始数组，不直接序列化
    @JsonIgnore
    private float[] clusterVectors;

    /**
     * 序列化：将 float[] 转为 Base64 字符串存入 ES 的 binary 字段
     */
    @JsonProperty("cluster_vectors")
    public String getClusterVectorsBase64() {
        if (clusterVectors == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(clusterVectors.length * 4);
        for (float f : clusterVectors) {
            buffer.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * 反序列化：将 ES 中的 Base64 字符串还原为 Java 的 float[]
     */
    @JsonProperty("cluster_vectors")
    public void setClusterVectorsFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            float[] floats = new float[bytes.length / 4];
            for (int i = 0; i < floats.length; i++) {
                floats[i] = buffer.getFloat();
            }
            this.clusterVectors = floats;
        } catch (Exception e) {
            // 记录异常，防止因为个别数据格式问题导致整个 Kafka 消费者挂掉
            this.clusterVectors = null;
        }
    }

    /**
     * 辅助方法：将扁平化的数组还原为 64x512 矩阵
     * 必须添加 @JsonIgnore，否则 Jackson 会将其序列化为 "reshapedMatrix" 字段导致 ES 报错
     */
    @JsonIgnore
    public float[][] getReshapedMatrix() {
        if (clusterVectors == null || clusterVectors.length != 64 * 512) {
            return new float[0][0];
        }
        float[][] matrix = new float[64][512];
        for (int i = 0; i < 64; i++) {
            System.arraycopy(clusterVectors, i * 512, matrix[i], 0, 512);
        }
        return matrix;
    }
}