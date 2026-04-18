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

    @Resource
    private MinioClient minioClient;

    @Value("${minio.bucket.final-video}")
    private String finalVideoBucket;

    // 默认临时链接有效期（分钟）
    private static final int DEFAULT_EXPIRY_MINUTES = 30;

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
