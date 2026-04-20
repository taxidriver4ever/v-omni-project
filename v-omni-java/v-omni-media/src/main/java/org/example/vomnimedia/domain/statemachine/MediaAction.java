package org.example.vomnimedia.domain.statemachine;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.dto.PreparePublishToMediaDto;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.DocumentVectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String,PreparePublishToMediaDto> kafkaTemplate;


    public void initialOnGetPreSignatureToPreparePublishMedia(@NotNull MediaEventContext mediaEventContext) {
        try {
            Long id = mediaEventContext.getId();
            String userId = mediaEventContext.getString("userId");
            String title = mediaEventContext.getString("title");
            String rawsVideoUploadUrl = minioService.getRawsVideoUploadUrl(id.toString());
            PreparePublishToMediaDto preparePublishToMediaDto = new PreparePublishToMediaDto(id.toString(), userId, title);
            kafkaTemplate.send("pre-database-topic", preparePublishToMediaDto);
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
        documentVectorMediaPo.setCreateTime(new Date());
        documentVectorMediaPo.setUpdateTime(new Date());

        try {
            documentVectorMediaService.upsert(documentVectorMediaPo);
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
