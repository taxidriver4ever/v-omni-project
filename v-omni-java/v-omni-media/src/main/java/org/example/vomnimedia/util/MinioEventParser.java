package org.example.vomnimedia.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import jakarta.annotation.Resource;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MinioEventParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    @Builder
    public static class MinioFileInfo {
        private String bucketName;
        private String objectKey;
        private String etag;
        private Long size;
        private Long mediaId;
    }

    /**
     * 将 Kafka 原始消息解析为简洁的实体对象
     */
    @Nullable
    public static MinioFileInfo parse(String rawJson, String hostOrigin) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode record = root.path("Records").get(0);
            if (record.isMissingNode()) return null;

            // 1. 提取桶名
            String bucket = record.path("s3").path("bucket").path("name").asText();

            // 2. 提取并解码 ObjectKey (从 s3.object.key)
            String rawKey = record.path("s3").path("object").path("key").asText();
            String decodedKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);

            // 3. 提取顶层 Key（格式：桶名/对象名），并解析出 mediaId
            String topLevelKey = root.path("Key").asText();
            Long mediaId = null;
            if (topLevelKey != null && !topLevelKey.isEmpty()) {
                // 去掉桶名前缀，例如 "raws-video/170846311464177664" -> "170846311464177664"
                String[] parts = topLevelKey.split("/", 2);
                if (parts.length == 2) {
                    try {
                        mediaId = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        log.warn("无法从 Key 解析 mediaId: {}", parts[1]);
                    }
                }
            }

            // 4. 提取其他元数据
            Long size = record.path("s3").path("object").path("size").asLong();
            String etag = record.path("s3").path("object").path("eTag").asText();

            // 5. 组装结果
            return MinioFileInfo.builder()
                    .bucketName(bucket)
                    .objectKey(decodedKey)
                    .etag(etag)
                    .size(size)
                    .mediaId(mediaId)
                    .build();

        } catch (Exception e) {
            log.error("解析 MinIO 消息异常: {}", e.getMessage());
            return null;
        }
    }
}

