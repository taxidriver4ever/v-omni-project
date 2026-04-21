package org.example.vomnimedia.comsumer;

import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.domain.statemachine.MediaEvent;
import org.example.vomnimedia.domain.statemachine.MediaEventContext;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.domain.statemachine.MediaTransitionService;
import org.example.vomnimedia.dto.AvatarAndAuthorDto;
import org.example.vomnimedia.dto.PreparePublishToMediaDto;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.po.MediaPo;
import org.example.vomnimedia.service.FfmpegService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.DocumentVectorMediaService;
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
    private KafkaTemplate<String, String> kafkaTemplate;

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

    private static final int VIDEO_INFO_TTL = 60 * 60 * 24;

    private static final String MEDIA_INFO_PREFIX = "media:info:";

    @KafkaListener(topics = "minio-url-topic", groupId = "v-omni-media-group")
    public void getUrlTopicConsume(@NotNull String message) {
        MinioEventParser.MinioFileInfo fileInfo = MinioEventParser.parse(message, MINIO_HOST);
        if (fileInfo == null) {
            log.warn("无法解析 MinIO 事件消息");
            return;
        }

        String bucketName = fileInfo.getBucketName();
        Long id = fileInfo.getMediaId();

        if(!bucketName.equals("raws-video")) return;

        MediaEventContext mediaEventContext = new MediaEventContext(id);
        MediaState mediaState = mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.START_PROCESSING);

        if(!mediaState.equals(MediaState.PROCESSING)) return;

        try {
            String downloadUrl = minioService.getDownloadUrl(bucketName, id.toString());

            String messageToSend = id + "|" + downloadUrl;
            kafkaTemplate.send("frame-extraction-topic", messageToSend);
            kafkaTemplate.send("decode-media-topic", messageToSend);

        } catch (Exception e) {
            log.error("生成下载链接失败: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "frame-extraction-topic", groupId = "v-omni-media-group")
    public void frameExtractionTopicConsume(@NotNull String message) throws Exception {
        String[] parts = message.split("\\|");
        String id = parts[0];
        String downloadUrl = parts[1];
        String coverPath = ffmpegService.extractFinalCover(id, downloadUrl);
        List<String> frameObjects = ffmpegService.extractFramesEveryNSeconds
                (id, downloadUrl, 5, "tmp-extraction-image");
        float[] floats = callPythonEmbeddingService(id, frameObjects);
        byte[] bytes = new byte[floats.length * 4];
        ByteBuffer.wrap(bytes).asFloatBuffer().put(floats);
        byteRedisTemplate.opsForValue().set("media:vector:id:" + id, bytes, Duration.ofMinutes(15));
        stringRedisTemplate.opsForValue().set("media:cover_url:id:" + id, coverPath, Duration.ofMinutes(15));

        MediaEventContext mediaEventContext = new MediaEventContext(Long.parseLong(id));
        mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.FINISH_EXTRACTION);
    }

    @KafkaListener(topics = "decode-media-topic", groupId = "v-omni-media-group")
    public void decodingTopicConsume(@NotNull String message) throws Exception {
        String[] parts = message.split("\\|");
        String id = parts[0];
        String downloadUrl = parts[1];
        ffmpegService.compressAndConvertToHLSAndUploadToMinio(id, downloadUrl, "final-video");
        MediaEventContext mediaEventContext = new MediaEventContext(Long.parseLong(id));
        mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.FINISH_DECODING);
    }

    @KafkaListener(topics = "delete-media-topic", groupId = "v-omni-media-group")
    public void deleteMediaTopicConsume(@NotNull String message) throws Exception {
        String[] parts = message.split(":");
        String id = parts[0];
        String userId = parts[1];
        Long l = mediaMapper.selectUserIdById(Long.parseLong(id));
        if(!l.toString().equals(userId)) return;
        documentVectorMediaService.deleteById(id);
        mediaMapper.updateIsDeletedById(Long.parseLong(id), new Date());
    }

    @KafkaListener(topics = "pre-database-topic", groupId = "v-omni-media-group")
    public void preDatabaseTopicConsume(@NotNull PreparePublishToMediaDto message) {
        String id = message.getId();
        String userId = message.getUserId();
        String title = message.getTitle();
        Date date = new Date();
        MediaPo mediaPo = new MediaPo(
                Long.parseLong(id),
                title,
                Long.parseLong(userId),
                MediaState.PREPARE_PUBLISH_MEDIA.toString(),
                false,
                "",
                date,
                date
        );
        mediaMapper.insertUser(mediaPo);
    }


    @NotNull
    private float[] callPythonEmbeddingService(String videoId, List<String> frameObjects) {
        String pythonUrl = "http://localhost:18001/embedding/mean_pool";

        // 构建请求体
        Map<String, Object> request = Map.of(
                "video_id", videoId,
                "frame_objects", frameObjects
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        RestTemplate restTemplate = new RestTemplate();
        // 期望返回类型是 Map<String, Object>
        Map<String, Object> response = restTemplate.postForObject(pythonUrl, entity, Map.class);

        if (response == null || !response.containsKey("vector")) {
            throw new RuntimeException("Python 服务未返回向量");
        }

        // 提取向量列表并转为 float[]
        List<Double> vectorList = (List<Double>) response.get("vector");
        float[] vector = new float[vectorList.size()];
        for (int i = 0; i < vectorList.size(); i++) {
            vector[i] = vectorList.get(i).floatValue();
        }
        return vector;
    }

}
