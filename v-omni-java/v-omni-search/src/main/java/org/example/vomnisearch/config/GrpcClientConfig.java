package org.example.vomnisearch.config;

import io.grpc.ManagedChannel;
import org.example.vomnisearch.grpc.*;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @Value("${grpc.client.v-omni-ai.address:localhost:50051}")
    private String aiServiceAddress;

    @Bean
    public RecommenderGrpc.RecommenderBlockingStub recommenderStub() {
        // 手动构建 Channel，不受 Starter 自动装配限制
        int maxMessageSize = 64 * 1024 * 1024; // 64MB
        ManagedChannel channel = ManagedChannelBuilder.forTarget(aiServiceAddress)
                .usePlaintext() // 对应你的 negotiation-type: plaintext
                .maxInboundMessageSize(maxMessageSize)
                .build();

        return RecommenderGrpc.newBlockingStub(channel);
    }
}