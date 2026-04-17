package org.example.vomnimedia.controller;

import jakarta.annotation.Resource;
import org.example.vomnimedia.common.MyResult;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.service.MediaService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.util.SnowflakeIdWorker;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/media")
public class MediaController {

    @Resource
    private MediaService mediaService;

    @PostMapping("/pre-sign")
    public MyResult<String> generatePreSignature(String title) throws Exception {
        Map<String,String>res = mediaService.generatePreSignature(title);
        MediaState state = MediaState.ERROR;
        if(res != null) state = MediaState.valueOf(res.get("state"));
        switch(state) {
            case EXCEED_LIMIT -> {
                return MyResult.error(429,"请求过于频繁");
            }
            case PREPARE_PUBLISH_MEDIA -> {
                String preSign = res.get("preSign");
                return MyResult.success(preSign);
            }
            default -> {
                return MyResult.error(500,"获取预签名失败");
            }
        }
    }

}
