package org.example.vomniauth.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioConfigProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private int uploadUrlExpirySeconds = 7200;
}