package org.example.vomnisearch.service;

import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

/**
 * MinIO 文件存储服务接口
 */
public interface MinioService {
    String generateAvatarUrl(String relativePath);
    String generateCoverUrl(String relativePath);
    String generateHlsPlaybackUrl(String videoId) throws Exception;
    String generatePreSignedUrl(String bucketName, String objectName, int expiryMinutes) throws Exception;
}