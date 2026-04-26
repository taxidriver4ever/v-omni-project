package org.example.vomnimedia.service;

import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件存储服务接口
 */
public interface MinioService {

    // ====================== 初始化 ======================
    void initBuckets();
    // ====================== 预签名URL ======================
    String getUploadPreSignedUrl(String bucketName, String objectName, int expirySeconds) throws Exception;

    String getRawsVideoUploadUrl(String objectName) throws Exception;

    String getDownloadUrl(String bucketName, String objectName, int days, String originalFilename) throws Exception;

    String getDownloadUrl(String bucketName, String objectName) throws Exception;

    // ====================== 文件删除 & 检查 ======================
    void deleteFile(String bucketName, String objectName) throws Exception;

    boolean fileExists(String bucketName, String objectName);

    void uploadImage(BufferedImage image, String bucketName, String objectName) throws Exception;

    // 上传单个本地文件
    String uploadFile(File file, String bucketName, String objectName) throws Exception;

    // 上传整个目录（递归），保留相对路径
    void uploadDirectory(File dir, String bucketName, String remotePrefix) throws Exception;

    void deleteDirectory(String bucketName, String prefix) throws Exception;
}