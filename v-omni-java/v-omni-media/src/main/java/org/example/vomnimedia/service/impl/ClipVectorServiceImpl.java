package org.example.vomnimedia.service.impl;

import com.google.protobuf.ByteString;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.example.vomnimedia.service.VectorService;
import org.springframework.stereotype.Service;

// 这些就是刚刚生成出来的类
import org.example.vomnimedia.grpc.RecommenderGrpc;
import org.example.vomnimedia.grpc.ImageRequest;
import org.example.vomnimedia.grpc.MultiVectorResponse;
import org.example.vomnimedia.grpc.TextRequest;
import org.example.vomnimedia.grpc.VectorResponse;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ClipVectorServiceImpl implements VectorService {

    @Resource
    private RecommenderGrpc.RecommenderBlockingStub recommenderStub;

    @Override
    public float[] getVector(List<byte[]> imageBytesList) {
        try {
            ImageRequest request = ImageRequest.newBuilder()
                    .addAllImageData(imageBytesList.stream().map(ByteString::copyFrom).collect(Collectors.toList()))
                    .setDoPooling(true)
                    .build();

            MultiVectorResponse response = recommenderStub.getImageEmbeddings(request);
            if (response.getVectorsCount() > 0) {
                return convertToFloatArray(response.getVectors(0).getValuesList());
            }
        } catch (Exception e) {
            log.error("❌ 调用 AI 服务异常: {}", e.getMessage());
        }
        return new float[512];
    }

    @Override
    public float[] getTextVector(String text) {
        try {
            TextRequest request = TextRequest.newBuilder().setText(text).build();
            VectorResponse response = recommenderStub.getTextEmbedding(request);

            // 增加校验：如果拿到的列表是空的，直接抛异常
            if (response.getValuesList().isEmpty()) {
                throw new RuntimeException("Python AI 返回了空向量");
            }

            return convertToFloatArray(response.getValuesList());
        } catch (Exception e) {
            log.error("❌ 文本向量化核心链路崩溃: ", e); // 这里打印完整堆栈，能看到具体报错
            throw e; // 向上抛出，让程序停下来，而不是返回全 0
        }
    }

    private float[] convertToFloatArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i);
        return array;
    }
}