package org.example.vomnimedia.controller;

import io.minio.MinioClient;
import jakarta.annotation.Resource;
import org.example.vomnimedia.common.MyResult;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.dto.PublishRequestDto;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.service.MediaService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.util.SecurityUtils;
import org.example.vomnimedia.vo.PreSignResponseVo;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/media")
public class MediaController {

    @Resource
    private MediaService mediaService;

    @Resource
    private KafkaTemplate<String, PublishRequestDto> publishTemplate;

    @Resource
    private MinioService minioService;

    @PostMapping("/pre-sign")
    public MyResult<PreSignResponseVo> generatePreSignature() throws Exception {
        PreSignResponseVo preSignResponseVo = mediaService.generatePreSignature();
        MediaState state = MediaState.ERROR;
        if(preSignResponseVo != null) {
            state = preSignResponseVo.getMediaState();
        }
        switch(state) {
            case EXCEED_LIMIT -> {
                return MyResult.error(429,"请求过于频繁");
            }
            case PREPARE_PUBLISH_MEDIA -> {
                return MyResult.success(preSignResponseVo);
            }
            default -> {
                return MyResult.error(500,"获取预签名失败");
            }
        }
    }

    @PostMapping("/publish")
    public MyResult<String> publish(@RequestBody PublishRequestDto req) {
        // 1. 核心动作：先去 MinIO 检查文件到底在不在
        boolean exists = minioService.fileExists("raws-video", req.getMediaId());

        if (!exists) return MyResult.error(404,"视频文件尚未上传完成，请稍后再试");

        String userId = String.valueOf(SecurityUtils.getCurrentUserId());
        req.setUserId(userId);
        publishTemplate.send("video-process-topic", req);
        return MyResult.success();
    }

    @DeleteMapping("/media")
    public MyResult<String> deleteMedia(String mediaId) throws Exception {
        mediaService.deleteMedia(mediaId);
        return MyResult.success();
    }

}
