package org.example.vomnimedia.service;

import org.example.vomnimedia.domain.statemachine.MediaState;

import java.util.Map;

public interface MediaService {
    Map<String,String> generatePreSignature(String title) throws Exception;
    void deleteMedia(String mediaId) throws Exception;
}
