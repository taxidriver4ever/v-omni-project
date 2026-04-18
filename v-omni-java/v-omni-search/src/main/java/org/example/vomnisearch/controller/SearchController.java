package org.example.vomnisearch.controller;

import jakarta.annotation.Resource;
import org.example.vomnisearch.common.MyResult;
import org.example.vomnisearch.dto.UserContent;
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.service.EmbeddingService;
import org.example.vomnisearch.service.MinioService;
import org.example.vomnisearch.service.SearchService;
import org.example.vomnisearch.service.VectorMediaService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private SearchService searchService;

    @PostMapping("/hybrid/video")
    public MyResult<List<String>> searchVideo(@RequestBody UserContent userContent) throws Exception {
        List<String> strings = searchService.searchVideo(userContent);
        if (strings.isEmpty()) return MyResult.error(404,"找不到视频");
        return MyResult.success(strings);
    }


}
