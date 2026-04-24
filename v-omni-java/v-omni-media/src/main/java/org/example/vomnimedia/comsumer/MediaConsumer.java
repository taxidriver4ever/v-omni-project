package org.example.vomnimedia.comsumer;

import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.domain.statemachine.MediaEvent;
import org.example.vomnimedia.domain.statemachine.MediaEventContext;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.domain.statemachine.MediaTransitionService;
import org.example.vomnimedia.dto.*;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.po.MediaPo;
import org.example.vomnimedia.service.FfmpegService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.DocumentVectorMediaService;
import org.example.vomnimedia.service.VectorService;
import org.example.vomnimedia.util.MinioEventParser;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class MediaConsumer {

    @Value("${minio.endpoint}")
    private String MINIO_HOST;

    @Resource
    private MinioService minioService;

    @Resource
    private KafkaTemplate<String, HandleMediaDto> handleMediaDtoKafkaTemplate;

    @Resource
    private FfmpegService ffmpegService;

    @Resource
    private MediaTransitionService mediaTransitionService;

    @Resource
    private RedisTemplate<String,byte[]> byteRedisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MediaMapper mediaMapper;

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private VectorService vectorService;

    private static final String RAWS_VIDEO = "raws-video";

    @KafkaListener(topics = "video-process-topic", groupId = "v-omni-media-group")
    public void getUrlTopicConsume(@NotNull PublishRequestDto message) {
        String title = message.getTitle();
        String userId = message.getUserId();
        String id = message.getMediaId();

        MediaEventContext mediaEventContext = new MediaEventContext(Long.valueOf(id));
        MediaState mediaState = mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.START_PROCESSING);

        if(!mediaState.equals(MediaState.PROCESSING)) return;

        try {
            String downloadUrl = minioService.getDownloadUrl(RAWS_VIDEO, id);

            HandleMediaDto messageToSend = HandleMediaDto.builder()
                    .downloadUrl(downloadUrl)
                    .mediaId(id)
                    .title(title)
                    .userId(userId)
                    .build();

            handleMediaDtoKafkaTemplate.send("frame-extraction-topic", messageToSend);
            handleMediaDtoKafkaTemplate.send("decode-media-topic", messageToSend);

        } catch (Exception e) {
            log.error("生成下载链接失败: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "frame-extraction-topic", groupId = "v-omni-media-group")
    public void frameExtractionTopicConsume(@NotNull HandleMediaDto message) throws Exception {
        String id = message.getMediaId();
        String downloadUrl = message.getDownloadUrl();
        String coverPath = ffmpegService.extractFinalCover(id, downloadUrl);
        String title = message.getTitle();
        String userId = message.getUserId();
        float[] floats = ffmpegService.extractVideoVector(id,downloadUrl);
        float[] textVector = vectorService.getTextVector(title);

        byte[] bytes = new byte[floats.length * 4];
        ByteBuffer.wrap(bytes).asFloatBuffer().put(floats);

        byte[] titleBytes = new byte[textVector.length * 4];
        ByteBuffer.wrap(titleBytes).asFloatBuffer().put(textVector);

        byteRedisTemplate.opsForValue().set("media:video:vector:id:" + id, bytes, Duration.ofMinutes(15));
        byteRedisTemplate.opsForValue().set("media:title:vector:id:" + id, titleBytes, Duration.ofMinutes(15));
        stringRedisTemplate.opsForValue().set("media:cover_path:id:" + id, coverPath, Duration.ofMinutes(15));

        MediaEventContext mediaEventContext = new MediaEventContext(Long.parseLong(id))
                .with("title", title)
                .with("userId", userId);
        mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.FINISH_EXTRACTION);
    }

    @KafkaListener(topics = "decode-media-topic", groupId = "v-omni-media-group")
    public void decodingTopicConsume(@NotNull HandleMediaDto message) throws Exception {
        String id = message.getMediaId();
        String downloadUrl = message.getDownloadUrl();
        String title = message.getTitle();
        String userId = message.getUserId();
        ffmpegService.compressAndConvertToHLSAndUploadToMinio(id, downloadUrl, "final-video");
        MediaEventContext mediaEventContext = new MediaEventContext(Long.parseLong(id))
                .with("title", title)
                .with("userId", userId);
        mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.FINISH_DECODING);
    }

    @KafkaListener(topics = "delete-media-topic", groupId = "v-omni-media-group")
    public void deleteMediaTopicConsume(@NotNull UserIdAndMediaIdDto message) throws Exception {
        String id = message.getMediaId();
        String userId = message.getUserId();

        minioService.deleteDirectory("final-video", "hls/" + id + "/");
        minioService.deleteFile("final-cover", id + ".jpg");

        documentVectorMediaService.deleteById(id);
        mediaMapper.updateIsDeletedByIdAndUserId(Long.parseLong(id),Long.parseLong(userId) , new Date());
    }

}
