package org.example.vomnimedia.service.impl;

import jakarta.annotation.Resource;
import org.example.vomnimedia.domain.statemachine.MediaEvent;
import org.example.vomnimedia.domain.statemachine.MediaEventContext;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.domain.statemachine.MediaTransitionService;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.po.MediaPo;
import org.example.vomnimedia.service.IdentityService;
import org.example.vomnimedia.service.MediaService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.util.SecurityUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class MediaServiceImpl implements MediaService {

    @Resource
    private MinioService minioService;

    @Resource
    private MediaMapper mediaMapper;

    @Resource
    private IdentityService identityService;

    @Resource
    private MediaTransitionService mediaTransitionService;

    @Resource
    private KafkaTemplate<String,String> kafkaTemplate;

    @Override
    public Map<String,String> generatePreSignature(String title) {
        Map<String, String> map = new HashMap<>();

        Long id = identityService.getOrCreateUserIdByEmail();

        Long userId = SecurityUtils.getCurrentUserId();

        MediaEventContext mediaEventContext = new MediaEventContext(id).with("userId", String.valueOf(userId)).with("title", title);

        MediaState currentState = mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.GET_PRE_SIGNATURE);

        String preSign = mediaEventContext.getString("preSign");

        map.put("state", currentState.toString());
        map.put("preSign", preSign);

        return map;
    }

    @Override
    public void deleteMedia(String mediaId) {
        String userId = String.valueOf(SecurityUtils.getCurrentUserId());
        kafkaTemplate.send("delete-media-topic", mediaId + ":" + userId);
    }

}
