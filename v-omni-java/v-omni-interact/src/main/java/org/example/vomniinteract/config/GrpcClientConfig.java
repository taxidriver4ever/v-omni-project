package org.example.vomniinteract.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.example.vomniinteract.grpc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @Value("${grpc.client.v-omni-ai.address:localhost:50051}")
    private String aiServiceAddress;

    @Bean
    public ManagedChannel managedChannel() {
        // 定义 64MB 的消息上限，确保大数据量的向量列表能正常传输
        int maxMessageSize = 64 * 1024 * 1024;

        return ManagedChannelBuilder.forTarget(aiServiceAddress)
                .usePlaintext()
                .maxInboundMessageSize(maxMessageSize)
                .build();
    }

    /**
     * 1. 搜索引擎专用：处理文本 Query 转向量
     */
    @Bean
    public TextEmbedServiceGrpc.TextEmbedServiceBlockingStub textEmbedStub(ManagedChannel channel) {
        return TextEmbedServiceGrpc.newBlockingStub(channel);
    }

    /**
     * 2. 视频入库专用：处理视频 URL 下载和特征提取
     */
    @Bean
    public VideoEmbedServiceGrpc.VideoEmbedServiceBlockingStub videoEmbedStub(ManagedChannel channel) {
        return VideoEmbedServiceGrpc.newBlockingStub(channel);
    }

    /**
     * 3. 推荐系统专用：处理长短期兴趣融合
     */
    @Bean
    public UserModelServiceGrpc.UserModelServiceBlockingStub userModelStub(ManagedChannel channel) {
        return UserModelServiceGrpc.newBlockingStub(channel);
    }
}