package org.example.vomnimedia.service.impl;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.config.MinioConfigProperties;
import org.example.vomnimedia.service.MinioService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 服务实现类 - V-Omni 全功能优化版
 * 1. 移除了不稳定的自定义 Token 校验
 * 2. 补全了所有业务接口方法
 * 3. 增强了 HLS 批量清理性能
 */
@Slf4j
@Service
public class MinioServiceImpl implements MinioService {

    @Resource
    private MinioClient minioClient;

    @Resource
    private MinioConfigProperties minioConfigProperties;

    // ====================== 配置常量 ======================
    private static final int UPLOAD_SIGNATURE_TTL = 7200;           // 上传 2小时
    private static final int DOWNLOAD_SIGNATURE_TTL_DAYS = 7;       // 下载 7天

    private static final String BUCKET_RAWS_VIDEO = "raws-video";
    private static final String BUCKET_FINAL_VIDEO = "final-video";
    private static final String BUCKET_FINAL_COVER = "final-cover";

    private static final int LIFECYCLE_EXPIRY_DAYS = 1;

    // ====================== 1. 基础设施初始化 ======================

    @PostConstruct
    @Override
    public void initBuckets() {
        try {
            ensureBucket(BUCKET_RAWS_VIDEO, true);
            ensureBucket(BUCKET_FINAL_VIDEO, false);
            ensureBucket(BUCKET_FINAL_COVER, false);
            setBucketPublic(BUCKET_FINAL_COVER);
            log.info("✅ MinIO 基础设施已完全就绪");
        } catch (Exception e) {
            log.error("❌ MinIO 初始化异常: {}", e.getMessage());
        }
    }

    private void ensureBucket(String bucketName, boolean autoExpire) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
        if (autoExpire) setBucketLifecycle(bucketName);
    }

    private void setBucketPublic(String bucketName) throws Exception {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucketName).config(policy).build());
    }

    private void setBucketLifecycle(String bucketName) throws Exception {
        LifecycleRule rule = new LifecycleRule(Status.ENABLED, null, new Expiration((ZonedDateTime) null, LIFECYCLE_EXPIRY_DAYS, null),
                new RuleFilter(""), "expire-after-" + LIFECYCLE_EXPIRY_DAYS + "d", null, null, null);
        minioClient.setBucketLifecycle(SetBucketLifecycleArgs.builder().bucket(bucketName).config(new LifecycleConfiguration(List.of(rule))).build());
    }

    // ====================== 2. 核心 URL 生成 (已移除暗号) ======================

    @Override
    public String getDownloadUrl(String bucketName, String objectName, int days, String originalFilename) throws Exception {
        GetPresignedObjectUrlArgs.Builder builder = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(bucketName).object(objectName).expiry(Math.min(days, 7), TimeUnit.DAYS);

        if (originalFilename != null && !originalFilename.isBlank()) {
            String encodedName = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8).replace("+", "%20");
            builder.extraQueryParams(Map.of("response-content-disposition", "attachment; filename=\"" + encodedName + "\""));
        }
        return minioClient.getPresignedObjectUrl(builder.build());
    }

    @Override
    public String getDownloadUrl(String bucketName, String objectName) throws Exception {
        return getDownloadUrl(bucketName, objectName, DOWNLOAD_SIGNATURE_TTL_DAYS, null);
    }

    @Override
    public String getUploadPreSignedUrl(String bucketName, String objectName, int expirySeconds) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT).bucket(bucketName).object(objectName).expiry(expirySeconds, TimeUnit.SECONDS).build());
    }

    @Override
    public String getRawsVideoUploadUrl(String objectName) throws Exception {
        return getUploadPreSignedUrl(BUCKET_RAWS_VIDEO, objectName, UPLOAD_SIGNATURE_TTL);
    }

    @Override
    public String uploadFile(File file, String bucketName, String objectName) throws Exception {
        try (InputStream is = new FileInputStream(file)) {
            String contentType = Files.probeContentType(file.toPath());
            minioClient.putObject(PutObjectArgs.builder().bucket(bucketName).object(objectName)
                    .stream(is, file.length(), -1).contentType(contentType != null ? contentType : "application/octet-stream").build());
        }
        return objectName;
    }

    @Override
    public void uploadDirectory(File dir, String bucketName, String remotePrefix) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String objectName = remotePrefix + file.getName();
            if (file.isDirectory()) {
                uploadDirectory(file, bucketName, objectName + "/");
            } else {
                uploadFile(file, bucketName, objectName);
            }
        }
    }

    @Override
    public void uploadImage(BufferedImage image, String bucketName, String objectName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        byte[] bytes = baos.toByteArray();
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(is, bytes.length, -1).contentType("image/jpeg").build());
        }
    }

    // ====================== 4. 文件/流下载与检查重重写方法 ======================

    @Override
    public boolean fileExists(String bucketName, String objectName) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucketName).object(objectName).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ====================== 5. 删除与工具重写方法 ======================

    @Override
    public void deleteFile(String bucketName, String objectName) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());
    }

    @Override
    public void deleteDirectory(String bucketName, String prefix) throws Exception {
        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder().bucket(bucketName).prefix(prefix).recursive(true).build());
        List<DeleteObject> objects = new ArrayList<>();
        for (Result<Item> result : results) {
            objects.add(new DeleteObject(result.get().objectName()));
        }
        if (!objects.isEmpty()) {
            Iterable<Result<DeleteError>> errors = minioClient.removeObjects(RemoveObjectsArgs.builder().bucket(bucketName).objects(objects).build());
            for (Result<DeleteError> error : errors) { log.error("删除失败: {}", error.get().objectName()); }
        }
    }
}