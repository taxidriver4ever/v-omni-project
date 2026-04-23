package org.example.vomnisearch.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.example.vomnisearch.common.MyResult;
import org.example.vomnisearch.dto.SearchHistoryDTO;
import org.example.vomnisearch.dto.SearchMediaRequestDto;
import org.example.vomnisearch.dto.UserContent;
import org.example.vomnisearch.dto.UserIdAndMediaIdDto;
import org.example.vomnisearch.po.DocumentHotWordsPo;
import org.example.vomnisearch.service.*;
import org.example.vomnisearch.util.SecurityUtils;
import org.example.vomnisearch.vo.RecommendMediaVo;
import org.example.vomnisearch.vo.SearchMediaVo;
import org.redisson.api.RBloomFilter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private SearchService searchService;

    @Resource
    private DocumentHotWordsService documentHotWordsService;

    @Resource
    private UserRecommendationRedisService redisService;

    @Resource
    private UserBloomFilterService userBloomFilterService;

    @Resource
    private KafkaTemplate<String, UserIdAndMediaIdDto> userIdAndMediaIdDtoKafkaTemplate;

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @PostMapping("/hybrid/video")
    public MyResult<List<SearchMediaVo>> searchVideo(@RequestBody SearchMediaRequestDto searchMediaRequestDto,
                                              HttpServletRequest httpServletRequest) throws Exception {
        List<SearchMediaVo> searchMediaVos = searchService.searchVideo(searchMediaRequestDto, httpServletRequest);
        if (searchMediaVos.isEmpty()) return MyResult.error(404,"找不到视频");
        return MyResult.success(searchMediaVos);
    }

    @PostMapping("/prefix/hot-word")
    public MyResult<List<DocumentHotWordsPo>> searchHotWord(@RequestBody UserContent userContent) throws Exception {
        String content = userContent.getContent();
        if(content == null || content.isEmpty()) return MyResult.error(403,"没法查");
        List<DocumentHotWordsPo> suggestions = documentHotWordsService.getSuggestions(content);
        if(suggestions == null || suggestions.isEmpty()) return MyResult.error(404,"没有搜索到");
        return MyResult.success(suggestions);
    }

    @GetMapping("/history")
    public MyResult<List<String>> getHistory() {
        // 从拦截器解析出的 request 域中获取 userId
        Long userId = SecurityUtils.getCurrentUserId();
        List<String> history = searchService.getUserHistory(userId);
        return MyResult.success(history);
    }

    /**
     * 删除单条历史记录
     */
    @DeleteMapping("/remove")
    public MyResult<String> removeOne(@RequestParam String keyword) {
        String userId = String.valueOf(SecurityUtils.getCurrentUserId());
        searchService.removeHistory(userId, keyword);
        return MyResult.success();
    }

    /**
     * 清空当前用户所有历史记录
     */
    @DeleteMapping("/clear")
    public MyResult<String> clearAll() {
        String userId = String.valueOf(SecurityUtils.getCurrentUserId());
        searchService.clearAllHistory(userId);
        return MyResult.success();
    }

    @GetMapping("/recommend/media")
    public MyResult<List<RecommendMediaVo>> getRecommendMedia(HttpServletRequest httpServletRequest) {
        List<RecommendMediaVo> recommendMedia = searchService.getRecommendMedia(httpServletRequest);
        if(recommendMedia == null || recommendMedia.isEmpty()) return MyResult.success();
        return MyResult.success(recommendMedia);
    }

    @PostMapping("/leave/video")
    public MyResult<Void> collectLeaveVideo(String mediaId) throws IOException {
        if(mediaId == null || mediaId.isEmpty()) return MyResult.error(404,"没有该视频");
        boolean available = documentVectorMediaService.availableMedia(mediaId);
        if(!available) return MyResult.error(404,"没有该视频");
        String userId = String.valueOf(SecurityUtils.getCurrentUserId());
        redisService.addSeenIdToZSet(userId, mediaId);
        RBloomFilter<String> bloomFilter = userBloomFilterService.getFilter(Long.valueOf(userId));
        bloomFilter.add(mediaId);
        UserIdAndMediaIdDto userIdAndMediaIdDto = new UserIdAndMediaIdDto();
        userIdAndMediaIdDto.setUserId(userId);
        userIdAndMediaIdDto.setMediaId(mediaId);
        userIdAndMediaIdDtoKafkaTemplate.send("handle-viewed-topic", userIdAndMediaIdDto);
        return MyResult.success();
    }

}
