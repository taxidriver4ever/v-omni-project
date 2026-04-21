package org.example.vomnisearch.service.impl;


import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.config.MinioConfigProperties;
import org.example.vomnisearch.service.MinioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 服务实现类
 */
@Slf4j
@Service
public class MinioServiceImpl implements MinioService {

    // 默认临时链接有效期（分钟）
    private static final int DEFAULT_EXPIRY_MINUTES = 30;

    @Value("${minio.bucket.final-video}")
    private String finalVideoBucket;

    @Resource
    private MinioClient minioClient;

    // 基础访问地址，建议配置在 yml 中，方便后期切线上环境
    @Value("${minio.base-url:http://localhost:9000}")
    private String minioBaseUrl;


    // 从配置文件读取，或根据截图直接定义
    @Value("${minio.bucket.avatar:v-omni-avatars}")
    private String avatarBucket;

    @Value("${minio.bucket.final-cover:final-cover}")
    private String coverBucket;
    /**
     * 生成视频 HLS 主播放列表的临时签名 URL
     *
     * @param videoId 视频 ID
     * @return 带签名的临时访问 URL
     */
    public String generateHlsPlaybackUrl(String videoId) throws Exception {
        // 根据你的 HLS 上传逻辑，主播放列表对象名
        String objectName = "hls/" + videoId + "/master.m3u8";  // 文件名请与实际保持一致
        return generatePreSignedUrl(finalVideoBucket, objectName, DEFAULT_EXPIRY_MINUTES);
    }
    /**
     * 获取公开头像链接
     * @param relativePath 数据库存的路径，如 "2026/04/21/avatar_1.png"
     * @return <a href="http://localhost:9000/v-omni-avatars/2026/04/21/avatar_1.png">...</a>
     */
    @Override
    public String generateAvatarUrl(String relativePath) {
        return buildPublicUrl(avatarBucket, relativePath);
    }

    /**
     * 获取公开封面链接
     * @param relativePath 数据库存的路径
     * @return <a href="http://localhost:9000/final-cover/xxx.jpg">...</a>
     */
    @Override
    public String generateCoverUrl(String relativePath) {
        return buildPublicUrl(coverBucket, relativePath);
    }

    /**
     * 通用公开链接拼接
     */
    private String buildPublicUrl(String bucket, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        // 处理路径开头的斜杠，防止拼接出 http://...//path
        String path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        // 格式：Endpoint / Bucket / Path
        StringBuilder sb = new StringBuilder(minioBaseUrl);
        if (!minioBaseUrl.endsWith("/")) {
            sb.append("/");
        }
        return sb.append(bucket).append("/").append(path).toString();
    }


    /**
     * 通用方法：生成指定对象的临时签名 URL
     *
     * @param bucketName 存储桶名称
     * @param objectName 对象路径
     * @param expiryMinutes 有效期（分钟）
     * @return 带签名的 URL
     */
    public String generatePreSignedUrl(String bucketName, String objectName, int expiryMinutes) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(expiryMinutes, TimeUnit.MINUTES)
                        .build()
        );
    }
}
