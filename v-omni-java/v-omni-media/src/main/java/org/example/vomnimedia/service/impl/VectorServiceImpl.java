package org.example.vomnimedia.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.grpc.VideoEmbedServiceGrpc;
import org.example.vomnimedia.grpc.VideoEmbedProto.VideoEmbedRequest;
import org.example.vomnimedia.grpc.VideoEmbedProto.VideoEmbedResponse;
import org.example.vomnimedia.service.VectorService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VectorServiceImpl implements VectorService {

    @Resource
    private VideoEmbedServiceGrpc.VideoEmbedServiceBlockingStub videoEmbedStub;

    /**
     * 获取多模态融合向量（对齐 Python server.py）
     * 包含：标题 + 视觉(CLIP) + OCR + 语音(Whisper) 的融合结果
     */
    @Override
    public float[] getFusionVector(String videoUrl, String title) {
        try {
            // 1. 构建请求
            VideoEmbedRequest request = VideoEmbedRequest.newBuilder()
                    .setVideoUrl(videoUrl)
                    .setTitle(title)
                    .build();

            // 2. 调用 Python 服务（设置 10 分钟超时，因为视频处理链路长）
            log.info("🚀 正在请求 Python 多模态融合向量服务: {}", videoUrl);
            VideoEmbedResponse response = videoEmbedStub
                    .withDeadlineAfter(10, TimeUnit.MINUTES)
                    .getVideoEmbedding(request);

            // 3. 校验状态
            if (!"ok".equals(response.getStatus())) {
                throw new RuntimeException("Python AI 服务异常: " + response.getMessage());
            }

            // 4. 将提取到的 512 维向量转为 float[]
            return convertToFloatArray(response.getEmbeddingList());

        } catch (Exception e) {
            log.error("❌ 向量化核心链路崩溃: ", e);
            throw new RuntimeException("无法获取视频融合向量", e);
        }
    }

    // 将 Proto 的 List<Float> 转换为 Java 的 float[]
    private float[] convertToFloatArray(List<Float> list) {
        if (list == null || list.isEmpty()) return new float[0];
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i);
        return array;
    }
}