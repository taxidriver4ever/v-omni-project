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
import org.example.vomnimedia.vo.PreSignResponseVo;
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
    public PreSignResponseVo generatePreSignature() {
        Map<String, String> map = new HashMap<>();

        Long id = identityService.getOrCreateUserIdByEmail();

        MediaEventContext mediaEventContext = new MediaEventContext(id);

        MediaState currentState = mediaTransitionService.sendEvent(mediaEventContext, MediaEvent.GET_PRE_SIGNATURE);

        String preSign = mediaEventContext.getString("preSign");

        return PreSignResponseVo.builder()
                .mediaId(id)
                .preSignUrl(preSign)
                .mediaState(currentState)
                .build();
    }

    @Override
    public void deleteMedia(String mediaId) {
        String userId = String.valueOf(SecurityUtils.getCurrentUserId());
        kafkaTemplate.send("delete-media-topic", mediaId + ":" + userId);
    }

}
