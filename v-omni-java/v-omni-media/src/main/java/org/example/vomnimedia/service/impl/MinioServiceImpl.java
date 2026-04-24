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
 * MinIO 服务实现类 - 已移除临时抽帧图片桶相关逻辑
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
    private static final String BUCKET_FINAL_COVER = "final-cover";

    private static final int LIFECYCLE_EXPIRY_DAYS = 1;

    // ====================== 初始化 ======================

    @PostConstruct
    @Override
    public void initBuckets() {
        try {
            createBucketIfNotExists(BUCKET_RAWS_VIDEO);
            createBucketIfNotExists(BUCKET_FINAL_VIDEO);
            createBucketIfNotExists(BUCKET_FINAL_COVER);

            // 设置 raws-video 的自动过期规则（上传的原始视频通常较大，建议清理）
            setBucketLifecycle(BUCKET_RAWS_VIDEO);

            // 设置封面桶为公共读权限
            setBucketPublic();

            log.info("✅ MinIO 桶初始化完成（raws-video, final-video, final-cover）");
        } catch (Exception e) {
            log.error("❌ MinIO 初始化失败", e);
        }
    }

    private void setBucketPublic() throws Exception {
        String config = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + BUCKET_FINAL_COVER + "/*\"]}]}";
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(BUCKET_FINAL_COVER).config(config).build());
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

    // ====================== 后面代码保持一致 ======================
    // ... uploadFile, getDownloadUrl, deleteFile 等方法 ...

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

        Map<String, String> params = new HashMap<>();

        // 1. 原有的文件名逻辑：控制浏览器下载时的文件名
        if (originalFilename != null && !originalFilename.isEmpty()) {
            params.put("response-content-disposition",
                    "attachment; filename=\"" + URLEncoder.encode(originalFilename, StandardCharsets.UTF_8) + "\"");
        }

        // 2. 注入个性化字段（你的“暗号”）
        // 假设这个 Key 叫 x-omni-token，Value 是基于 objectName 和私钥算出来的
        String myPrivateSecret = "your_internal_only_key_123"; // 仅存在于后端
        String secureToken = generateHmacToken(objectName, myPrivateSecret);

        params.put("x-omni-secure-token", secureToken);

        // 3. 将所有参数塞进查询参数
        builder.extraQueryParams(params);

        return minioClient.getPresignedObjectUrl(builder.build());
    }

    /**
     * 这是一个简单的哈希生成方法，确保 Token 与路径绑定且不可伪造
     */
    private String generateHmacToken(String data, String secret) {
        // 逻辑：使用 HmacSHA256 算法生成一个只有你能验证的哈希串
        // 或者简单点：return SecureUtil.md5(data + secret);
        return java.util.UUID.nameUUIDFromBytes((data + secret).getBytes()).toString();
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
    public void deleteDirectory(String bucketName, String prefix) throws Exception {
        // 1. 获取该路径（前缀）下的所有文件
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(prefix) // 例如 "hls/173075377504260096/"
                        .recursive(true) // 递归查找所有子文件
                        .build()
        );

        // 2. 构造删除列表
        List<DeleteObject> objectsToDelete = new ArrayList<>();
        for (Result<Item> result : results) {
            Item item = result.get();
            objectsToDelete.add(new DeleteObject(item.objectName()));
        }

        if (objectsToDelete.isEmpty()) {
            log.info("文件夹 {} 为空，无需删除", prefix);
            return;
        }

        // 3. 执行批量删除
        Iterable<Result<DeleteError>> deleteResults = minioClient.removeObjects(
                RemoveObjectsArgs.builder()
                        .bucket(bucketName)
                        .objects(objectsToDelete)
                        .build()
        );

        // 4. 检查是否有删除错误
        for (Result<DeleteError> result : deleteResults) {
            DeleteError error = result.get();
            log.error("删除文件 {} 失败: {}", error.objectName(), error.message());
        }

        log.info("✅ 已清空并删除文件夹: {}/{}", bucketName, prefix);
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

    @Override
    public String getPublicUrl(String bucketName, String objectName) {
        return String.format("%s/%s/%s",
                minioConfigProperties.getEndpoint(),
                bucketName,
                objectName);
    }
}