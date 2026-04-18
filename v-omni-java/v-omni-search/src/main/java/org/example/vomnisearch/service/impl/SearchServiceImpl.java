package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import org.example.vomnisearch.common.MyResult;
import org.example.vomnisearch.dto.UserContent;
import org.example.vomnisearch.service.EmbeddingService;
import org.example.vomnisearch.service.MinioService;
import org.example.vomnisearch.service.SearchService;
import org.example.vomnisearch.service.VectorMediaService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SearchServiceImpl implements SearchService {

    @Resource
    private VectorMediaService vectorMediaService;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private MinioService minioService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<String> searchVideo(UserContent userContent) throws Exception {
        String content = userContent.getContent();
        float[][] vector = embeddingService.getVector(content);
        if (vector == null || vector.length == 0) {
            return Collections.emptyList();
        }
        List<String> strings = vectorMediaService.hybridSearchIds(content, vector[0], content, 10);
        if(strings == null || strings.isEmpty()) return List.of();
        List<String> results = new ArrayList<>();
        for (String string : strings) {
            String urlKey = "search:url:media-id:" + string;
            String redisUrl = stringRedisTemplate.opsForValue().get(urlKey);
            if(redisUrl != null) {
                results.add(redisUrl);
                continue;
            }
            String s = minioService.generateHlsPlaybackUrl(string);
            stringRedisTemplate.opsForValue().setIfAbsent(urlKey, s, 29, TimeUnit.MINUTES);
            results.add(s);
        }
        return results;
    }
}
