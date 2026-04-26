package org.example.vomniauth.service.impl;


import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.config.MinioConfigProperties;
import org.example.vomniauth.service.MinioService;
import org.springframework.stereotype.Service;

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
    private static final String BUCKET_AVATARS = "v-omni-avatars";

    // ====================== 初始化 ======================

    @PostConstruct
    @Override
    public void initBuckets() {
        try {
            createBucketIfNotExists();
            setBucketPublic();

            log.info("✅ MinIO 桶初始化完成，生命周期规则已设置");
        } catch (Exception e) {
            log.error("❌ MinIO 初始化失败", e);
        }
    }

    private void setBucketPublic() throws Exception {
        // 构造 JSON 策略：允许所有人执行 s3:GetObject 操作
        String policy = "{" +
                "  \"Version\":\"2012-10-17\"," +
                "  \"Statement\":[{" +
                "    \"Effect\":\"Allow\"," +
                "    \"Principal\":\"*\"," +
                "    \"Action\":[\"s3:GetObject\"]," +
                "    \"Resource\":[\"arn:aws:s3:::" + BUCKET_AVATARS + "/*\"]" +
                "  }]" +
                "}";

        minioClient.setBucketPolicy(
                SetBucketPolicyArgs.builder()
                        .bucket(BUCKET_AVATARS)
                        .config(policy)
                        .build()
        );
        log.info("桶 {} 已设置为公共读 (Public Read)", BUCKET_AVATARS);
    }


    private void createBucketIfNotExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(MinioServiceImpl.BUCKET_AVATARS).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(MinioServiceImpl.BUCKET_AVATARS).build());
            log.info("桶创建成功: {}", MinioServiceImpl.BUCKET_AVATARS);
        }
    }

}
