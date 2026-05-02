package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.grpc.TextEmbedRequest;
import org.example.vomnisearch.grpc.TextEmbedResponse;
import org.example.vomnisearch.grpc.TextEmbedServiceGrpc;
import org.example.vomnisearch.service.VectorService;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ClipVectorServiceImpl implements VectorService {

    // 使用更新后的 Stub
    @Resource
    private TextEmbedServiceGrpc.TextEmbedServiceBlockingStub textEmbedStub;

    @Override
    public float[] getTextVector(String text) {
        // 健壮性检查：如果搜索词为空，直接拦截
        if (text == null || text.trim().isEmpty()) {
            log.warn("⚠️ 收到空搜索词，返回空向量");
            return new float[512];
        }

        try {
            // 1. 构建符合新 proto 定义的请求
            TextEmbedRequest request = TextEmbedRequest.newBuilder()
                    .setQueryText(text)
                    .build();

            // 2. 调用新服务接口
            log.info("🚀 发送文本向量化请求: [{}]", text);
            TextEmbedResponse response = textEmbedStub.getTextEmbedding(request);

            // 3. 校验返回状态
            if (!"ok".equalsIgnoreCase(response.getStatus())) {
                log.error("❌ Python AI 服务返回错误状态: {}, 信息: {}",
                        response.getStatus(), response.getMessage());
                throw new RuntimeException("AI 服务内部错误: " + response.getMessage());
            }

            // 4. 向量数据非空校验
            List<Float> vectorList = response.getEmbeddingList();
            if (vectorList == null || vectorList.isEmpty()) {
                throw new RuntimeException("Python AI 返回了空向量列表");
            }

            return convertToFloatArray(vectorList);

        } catch (Exception e) {
            log.error("❌ 文本向量化核心链路崩溃，搜索词: [{}], 错误原因: ", text, e);
            throw e;
        }
    }

    private float[] convertToFloatArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}