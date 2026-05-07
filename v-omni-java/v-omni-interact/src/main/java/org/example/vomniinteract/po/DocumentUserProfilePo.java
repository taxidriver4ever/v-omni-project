package org.example.vomniinteract.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentUserProfilePo {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("interest_vector")
    private float[] interestVector;

    @JsonProperty("sex")
    private int sex;

    @JsonProperty("birth_year")
    private int birthYear;

    @JsonProperty("country")
    private String country;

    @JsonProperty("province")
    private String province;

    @JsonProperty("city")
    private String city;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    @JsonProperty("update_time")
    private Date updateTime;

    @JsonIgnore
    private float[] clusterVectors; // 存储 8 * 512 = 4096 个浮点数

    @JsonProperty("cluster_vectors")
    public String getClusterVectorsBase64() {
        if (clusterVectors == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(clusterVectors.length * 4);
        for (float f : clusterVectors) {
            buffer.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

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
            log.error("解析用户 {} 的质心向量失败", userId, e);
            this.clusterVectors = null;
        }
    }

    // DocumentUserProfilePo.java 核心修改部分

    @JsonIgnore
    public List<InterestCentroid> getReshapedCentroids() {
        // 8 行 * 512 维 = 4096
        int expectedLen = 8 * 512;

        if (clusterVectors == null || clusterVectors.length != expectedLen) {
            log.warn("用户 {} 长期矩阵长度异常: {}, 期待 {}", userId,
                    clusterVectors == null ? 0 : clusterVectors.length, expectedLen);
            return createZeroCentroids();
        }

        List<InterestCentroid> centroids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int start = i * 512;
            float[] emb = new float[512];
            System.arraycopy(clusterVectors, start, emb, 0, 512);

            // 注意：这里只构建带 Embedding 的 Builder，BizLabels 留给 Service 层动态填充
            centroids.add(InterestCentroid.newBuilder()
                    .addAllEmbedding(com.google.common.primitives.Floats.asList(emb))
                    .build());
        }
        return centroids;
    }
    /**
     * 兜底逻辑：创建 8 个 512 维的全零质心，防止模型计算崩溃
     */
    private List<InterestCentroid> createZeroCentroids() {
        List<InterestCentroid> zeroList = new ArrayList<>();
        Float[] zeroArray = new Float[512];
        Arrays.fill(zeroArray, 0.0f);
        List<Float> zeroListFloats = Arrays.asList(zeroArray);

        for (int i = 0; i < 8; i++) {
            zeroList.add(InterestCentroid.newBuilder()
                    .addAllEmbedding(zeroListFloats)
                    .build());
        }
        return zeroList;
    }
}
