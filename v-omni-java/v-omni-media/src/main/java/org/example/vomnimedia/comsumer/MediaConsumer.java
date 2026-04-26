package org.example.vomnimedia.comsumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.domain.statemachine.MediaEvent;
import org.example.vomnimedia.domain.statemachine.MediaEventContext;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.domain.statemachine.MediaTransitionService;
import org.example.vomnimedia.dto.*;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.service.FfmpegService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.DocumentVectorMediaService;
import org.example.vomnimedia.service.VectorService;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment; // 👈 确保导入
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;

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
    private RedisTemplate<String, byte[]> byteRedisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MediaMapper mediaMapper;

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private VectorService vectorService;

    private static final String RAWS_VIDEO = "raws-video";
    private static final String MEDIA_STATE_PREFIX = "media:state:id:";

    /**
     * 第一阶段：任务分发
     */
    @KafkaListener(topics = "video-process-topic", groupId = "v-omni-media-group")
    public void getUrlTopicConsume(@NotNull PublishRequestDto message, Acknowledgment ack) {
        String id = message.getMediaId();
        try {
            log.info("📥 接收到视频处理请求, ID: {}", id);

            MediaEventContext mediaEventContext = new MediaEventContext(Long.valueOf(id));
            MediaState mediaState = mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.START_PROCESSING);

            if (!mediaState.equals(MediaState.PROCESSING)) {
                ack.acknowledge();
                return;
            }

            String downloadUrl = minioService.getDownloadUrl(RAWS_VIDEO, id);
            HandleMediaDto messageToSend = HandleMediaDto.builder()
                    .downloadUrl(downloadUrl)
                    .mediaId(id)
                    .title(message.getTitle())
                    .userId(message.getUserId())
                    .build();

            handleMediaDtoKafkaTemplate.send("frame-extraction-topic", messageToSend);
            handleMediaDtoKafkaTemplate.send("decode-media-topic", messageToSend);

            ack.acknowledge(); // 分发成功提交偏移量
        } catch (Exception e) {
            log.error("❌ 任务分发失败: {}", e.getMessage());
            ack.acknowledge(); // 即使失败也建议提交，或进入死信队列，避免无限重试
        }
    }

    /**
     * 第二阶段：抽帧与向量化
     */
    @KafkaListener(
            topics = "frame-extraction-topic",
            groupId = "v-omni-media-group",
            concurrency = "2"
    )
    public void frameExtractionTopicConsume(@NotNull HandleMediaDto message, Acknowledgment ack) {
        String id = message.getMediaId();

        // 🚀 Redis 幂等检查：如果在缓存中已是 FINISHED，直接签收并跳过
        String state = stringRedisTemplate.opsForValue().get(MEDIA_STATE_PREFIX + id);
        if (Objects.equals(state, "FINISHED")) {
            log.info("🛡️ [拦截] 视频 {} 抽帧已完成，跳过逻辑", id);
            ack.acknowledge();
            return;
        }

        try {
            log.info("📸 线程 [{}] 开始抽帧: {}", Thread.currentThread().getName(), id);

            String coverPath = ffmpegService.extractFinalCover(id, message.getDownloadUrl());
            float[] videoFloats = ffmpegService.extractVideoVector(id, message.getDownloadUrl());
            float[] textVector = vectorService.getTextVector(message.getTitle());

            saveVectorsToRedis(id, videoFloats, textVector, coverPath);

            MediaEventContext context = new MediaEventContext(Long.parseLong(id))
                    .with("title", message.getTitle())
                    .with("userId", message.getUserId());
            mediaTransitionService.sendEvent(context, MediaEvent.FINISH_EXTRACTION);

            log.info("✅ 抽帧向量化完成: {}", id);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("❌ 抽帧异常 [ID:{}]: {}", id, e.getMessage());
            ack.acknowledge(); // 捕获异常后提交，防止 Kafka 重试导致 MinIO 404 循环
        }
    }

    /**
     * 第三阶段：HLS转码
     */
    @KafkaListener(
            topics = "decode-media-topic",
            groupId = "v-omni-media-group",
            concurrency = "1"
    )
    public void decodingTopicConsume(@NotNull HandleMediaDto message, Acknowledgment ack) {
        String id = message.getMediaId();

        // 🚀 Redis 幂等检查
        String state = stringRedisTemplate.opsForValue().get(MEDIA_STATE_PREFIX + id);
        if (Objects.equals(state, "FINISHED")) {
            log.info("🛡️ [拦截] 视频 {} 转码已完成，跳过逻辑", id);
            ack.acknowledge();
            return;
        }

        try {
            log.info("🎬 线程 [{}] 开始转码: {}", Thread.currentThread().getName(), id);

            ffmpegService.compressAndConvertToHLSAndUploadToMinio(id, message.getDownloadUrl(), "final-video");

            MediaEventContext context = new MediaEventContext(Long.parseLong(id))
                    .with("title", message.getTitle())
                    .with("userId", message.getUserId());
            mediaTransitionService.sendEvent(context, MediaEvent.FINISH_DECODING);

            ack.acknowledge();
            log.info("✅ HLS转码完成并已提交 Offset: {}", id);
        } catch (Exception e) {
            log.error("❌ 转码异常 [ID:{}]: {}", id, e.getMessage());
            ack.acknowledge(); // 强制签收，解决 MinIO 文件丢失后的重试风暴
        }
    }

    private void saveVectorsToRedis(String id, float[] videoVector, float[] textVector, String coverPath) {
        byte[] vBytes = new byte[videoVector.length * 4];
        ByteBuffer.wrap(vBytes).asFloatBuffer().put(videoVector);

        byte[] tBytes = new byte[textVector.length * 4];
        ByteBuffer.wrap(tBytes).asFloatBuffer().put(textVector);

        byteRedisTemplate.opsForValue().set("media:video:vector:id:" + id, vBytes, Duration.ofHours(2));
        byteRedisTemplate.opsForValue().set("media:title:vector:id:" + id, tBytes, Duration.ofHours(2));
        stringRedisTemplate.opsForValue().set("media:cover_path:id:" + id, coverPath, Duration.ofHours(2));
    }

    @KafkaListener(topics = "delete-media-topic", groupId = "v-omni-media-group")
    public void deleteMediaTopicConsume(@NotNull UserIdAndMediaIdDto message, Acknowledgment ack) throws Exception {
        String id = message.getMediaId();
        String userId = message.getUserId();

        minioService.deleteDirectory("final-video", "hls/" + id + "/");
        minioService.deleteFile("final-cover", id + ".jpg");

        documentVectorMediaService.deleteById(id);
        mediaMapper.updateIsDeletedByIdAndUserId(Long.parseLong(id), Long.parseLong(userId), new Date());

        ack.acknowledge();
    }
}