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

    @Resource
    private MinioConfigProperties minioConfigProperties;

    // ====================== 配置常量 ======================
    private static final int UPLOAD_SIGNATURE_TTL = 7200;           // 上传URL有效期 2小时
    private static final int DOWNLOAD_SIGNATURE_TTL_DAYS = 7;       // 下载URL默认有效期 7天

    private static final String BUCKET_RAWS_VIDEO = "raws-video";
    private static final String BUCKET_FINAL_VIDEO = "final-video";
    private static final String BUCKET_TMP_EXTRACTION_IMAGE = "tmp-extraction-image";
    private static final String BUCKET_FINAL_COVER = "final-cover";

    private static final int LIFECYCLE_EXPIRY_DAYS = 1;

    // ====================== 初始化 ======================

    @PostConstruct
    @Override
    public void initBuckets() {
        try {
            createBucketIfNotExists(BUCKET_RAWS_VIDEO);
            createBucketIfNotExists(BUCKET_FINAL_VIDEO);
            createBucketIfNotExists(BUCKET_TMP_EXTRACTION_IMAGE);

            createBucketIfNotExists(BUCKET_FINAL_COVER);
            setBucketLifecycle(BUCKET_RAWS_VIDEO);
            setBucketLifecycle(BUCKET_TMP_EXTRACTION_IMAGE);
            setBucketPublic();

            log.info("✅ MinIO 桶初始化完成，生命周期规则已设置");
        } catch (Exception e) {
            log.error("❌ MinIO 初始化失败", e);
        }
    }

    private void setBucketPublic() throws Exception {
        String config = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + MinioServiceImpl.BUCKET_FINAL_COVER + "/*\"]}]}";
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(MinioServiceImpl.BUCKET_FINAL_COVER).config(config).build());
    }

    private void createBucketIfNotExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            log.info("桶创建成功: {}", bucketName);
        }
    }

    private void setBucketLifecycle(String bucketName) throws Exception {
        LifecycleRule rule = new LifecycleRule(
                Status.ENABLED,
                null,
                new Expiration((ZonedDateTime) null, LIFECYCLE_EXPIRY_DAYS, null),
                new RuleFilter(""),
                "expire-after-" + LIFECYCLE_EXPIRY_DAYS + "d",
                null, null, null
        );

        LifecycleConfiguration config = new LifecycleConfiguration(List.of(rule));

        minioClient.setBucketLifecycle(
                SetBucketLifecycleArgs.builder()
                        .bucket(bucketName)
                        .config(config)
                        .build()
        );
        log.info("已为桶 {} 设置 {} 天自动过期", bucketName, LIFECYCLE_EXPIRY_DAYS);
    }

    // ====================== 文件上传 ======================

    @Override
    public String uploadFile(MultipartFile file, String bucketName, String prefix) throws Exception {
        createBucketIfNotExists(bucketName);

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String uuid = UUID.randomUUID().toString().replace("-", "");
        String objectName = (prefix != null && !prefix.isEmpty() ? prefix : "") + uuid + extension;

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }

        log.info("文件上传成功 → 桶: {}, 对象: {}", bucketName, objectName);

        return getDownloadUrl(bucketName, objectName, DOWNLOAD_SIGNATURE_TTL_DAYS, originalFilename);
    }

    @Override
    public String uploadFile(MultipartFile file, String bucketName) throws Exception {
        return uploadFile(file, bucketName, null);
    }

    @Override
    public String uploadToRawsVideo(MultipartFile file, String prefix) throws Exception {
        return uploadFile(file, BUCKET_RAWS_VIDEO, prefix);
    }

    @Override
    public String uploadToFinalVideo(MultipartFile file, String prefix) throws Exception {
        return uploadFile(file, BUCKET_FINAL_VIDEO, prefix);
    }

    // ====================== 预签名URL ======================

    @Override
    public String getUploadPreSignedUrl(String bucketName, String objectName, int expirySeconds) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(expirySeconds, TimeUnit.SECONDS)
                        .build()
        );
    }

    @Override
    public String getRawsVideoUploadUrl(String objectName) throws Exception {
        return getUploadPreSignedUrl(BUCKET_RAWS_VIDEO, objectName, UPLOAD_SIGNATURE_TTL);
    }

    @Override
    public String getDownloadUrl(String bucketName, String objectName, int days, String originalFilename) throws Exception {
        GetPresignedObjectUrlArgs.Builder builder = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucketName)
                .object(objectName)
                .expiry(days, TimeUnit.DAYS);

        if (originalFilename != null && !originalFilename.isEmpty()) {
            Map<String, String> params = new HashMap<>();
            params.put("response-content-disposition",
                    "attachment; filename=\"" + URLEncoder.encode(originalFilename, StandardCharsets.UTF_8) + "\"");
            builder.extraQueryParams(params);
        }

        return minioClient.getPresignedObjectUrl(builder.build());
    }

    @Override
    public String getDownloadUrl(String bucketName, String objectName) throws Exception {
        return getDownloadUrl(bucketName, objectName, DOWNLOAD_SIGNATURE_TTL_DAYS, null);
    }

    @Override
    public String getDownloadUrlFromRaws(String objectName, String originalFilename) throws Exception {
        return getDownloadUrl(BUCKET_RAWS_VIDEO, objectName, DOWNLOAD_SIGNATURE_TTL_DAYS, originalFilename);
    }

    @Override
    public String getDownloadUrlFromFinal(String objectName, String originalFilename) throws Exception {
        return getDownloadUrl(BUCKET_FINAL_VIDEO, objectName, DOWNLOAD_SIGNATURE_TTL_DAYS, originalFilename);
    }

    // ====================== 文件读取 ======================

    @Override
    public InputStream getFileStream(String bucketName, String objectName) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
    }

    @Override
    public byte[] getFileBytes(String bucketName, String objectName) throws Exception {
        try (InputStream is = getFileStream(bucketName, objectName)) {
            return is.readAllBytes();
        }
    }

    @Override
    public InputStream downloadFromRaws(String objectName) throws Exception {
        return getFileStream(BUCKET_RAWS_VIDEO, objectName);
    }

    @Override
    public InputStream downloadFromFinal(String objectName) throws Exception {
        return getFileStream(BUCKET_FINAL_VIDEO, objectName);
    }

    // ====================== 删除 & 检查 ======================

    @Override
    public void deleteFile(String bucketName, String objectName) throws Exception {
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
        log.info("文件已删除 → 桶: {}, 对象: {}", bucketName, objectName);
    }

    @Override
    public boolean fileExists(String bucketName, String objectName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void uploadImage(BufferedImage image, String bucketName, String objectName) throws Exception {
        createBucketIfNotExists(bucketName);

        // 将 BufferedImage 转为 JPEG 字节数组
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        byte[] imageBytes = baos.toByteArray();

        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(is, imageBytes.length, -1)
                            .contentType("image/jpeg")
                            .build()
            );
        }
        log.info("图片上传成功 → 桶: {}, 对象: {}", bucketName, objectName);
    }

    @Override
    public String uploadFile(File file, String bucketName, String objectName) throws Exception {
        createBucketIfNotExists(bucketName);
        try (InputStream is = new FileInputStream(file)) {
            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) contentType = "application/octet-stream";
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(is, file.length(), -1)
                            .contentType(contentType)
                            .build()
            );
        }
        log.info("文件上传成功: {} → {}/{}", file.getAbsolutePath(), bucketName, objectName);
        return objectName;
    }

    @Override
    public void uploadDirectory(File dir, String bucketName, String remotePrefix) throws Exception {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                uploadDirectory(file, bucketName, remotePrefix + file.getName() + "/");
            } else {
                String objectName = remotePrefix + file.getName();
                uploadFile(file, bucketName, objectName);
            }
        }
    }

    // 在 MinioServiceImpl 中实现
    @Override
    public String getPublicUrl(String bucketName, String objectName) {
        // 格式：http://ip:port/bucketName/objectName
        // 生产环境建议从 config 中读取自定义域名 (如 https://img.v-omni.com)
        return String.format("%s/%s/%s",
                minioConfigProperties.getEndpoint(),
                bucketName,
                objectName);
    }

}
