package org.example.vomnimedia.domain.statemachine;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.po.MediaPo;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.VectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MediaAction {

    @Resource
    private MediaMapper mediaMapper;

    @Resource
    private MinioService minioService;

    @Resource
    private RedisTemplate<String,byte[]> byteRedisTemplate;

    @Resource
    private VectorMediaService vectorMediaService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void initialOnGetPreSignatureToPreparePublishMedia(@NotNull MediaEventContext mediaEventContext) {
        try {
            Long id = mediaEventContext.getId();
            String userId = mediaEventContext.getString("userId");
            String title = mediaEventContext.getString("title");
            String rawsVideoUploadUrl = minioService.getRawsVideoUploadUrl(id.toString());
            MediaPo mediaPo = new MediaPo(id,Long.parseLong(userId),MediaState.PREPARE_PUBLISH_MEDIA, title);
            mediaMapper.insertUser(mediaPo);
            mediaEventContext.with("preSign", rawsVideoUploadUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void initialOnGetPreSignatureToExceedLimit(@NotNull MediaEventContext mediaEventContext) {
        String userId = mediaEventContext.getString("userId");
        log.info("{}发送注册验证码尝试次数过多", userId);
    }

    public void preparePublishMediaOnStartProcessingToProcessing(@NotNull MediaEventContext mediaEventContext) {
        Long id = mediaEventContext.getId();
        log.info("{}开始加工",id);
    }

    public void processingOnFinishDecodingToDecodeFinish(@NotNull MediaEventContext mediaEventContext) {
    }

    public void processingOnFinishExtractToExtractFinish(@NotNull MediaEventContext mediaEventContext) {
    }

    public void decodeFinishOnFinishExtractToFinished(@NotNull MediaEventContext mediaEventContext) {
        synToDatabase(mediaEventContext);
    }

    public void extractFinishOnFinishDecodeToFinished(@NotNull MediaEventContext mediaEventContext) {
        synToDatabase(mediaEventContext);
    }

    private void synToDatabase(@NotNull MediaEventContext mediaEventContext) {
        Long id = mediaEventContext.getId();
        float[] vectorFromRedis = getVectorFromRedis(mediaEventContext);

        if (vectorFromRedis == null) return;
        List<Float> vectorFormat = new ArrayList<>(vectorFromRedis.length);
        for (float v : vectorFromRedis) {
            vectorFormat.add(v);
        }

        DocumentVectorMediaPo documentVectorMediaPo =
                mediaMapper.selectMediaWithAuthor(mediaEventContext.getId());

        documentVectorMediaPo.setEmbedding(vectorFormat);

        try {
            vectorMediaService.upsert(documentVectorMediaPo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mediaMapper.updateStateAndUrl(id, MediaState.FINISHED.toString());
    }

    private float[] getVectorFromRedis(@NotNull MediaEventContext mediaEventContext) {
        String id = mediaEventContext.getId().toString();
        byte[] data = byteRedisTemplate.opsForValue().get("media:vector:id:" + id);
        float[] vector = null;
        if (data != null) {
            vector = new float[data.length / 4];
            ByteBuffer.wrap(data).asFloatBuffer().get(vector);
        }
        return vector;
    }
}
