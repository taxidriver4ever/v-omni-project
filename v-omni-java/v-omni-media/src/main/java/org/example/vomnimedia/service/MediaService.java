package org.example.vomnimedia.service;

import org.example.vomnimedia.domain.statemachine.MediaState;
import org.example.vomnimedia.vo.PreSignResponseVo;

import java.util.Map;

public interface MediaService {
    PreSignResponseVo generatePreSignature() throws Exception;
    void deleteMedia(String mediaId) throws Exception;
}
