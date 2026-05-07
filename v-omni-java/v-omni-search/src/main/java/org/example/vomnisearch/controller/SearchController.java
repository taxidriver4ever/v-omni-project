package org.example.vomnisearch.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.example.vomnisearch.common.MyResult;
import org.example.vomnisearch.dto.SearchMediaRequestDto;
import org.example.vomnisearch.dto.HotWordContentDto;
import org.example.vomnisearch.service.*;
import org.example.vomnisearch.util.SecurityUtils;
import org.example.vomnisearch.vo.RecommendMediaVo;
import org.example.vomnisearch.vo.SearchMediaVo;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private SearchService searchService;

    @Resource
    private HotWordRedisService hotWordRedisService;

    @PostMapping("/hybrid/video")
    public MyResult<List<SearchMediaVo>> searchVideo(@RequestBody SearchMediaRequestDto searchMediaRequestDto,
                                              HttpServletRequest httpServletRequest) throws Exception {
        List<SearchMediaVo> searchMediaVos = searchService.searchVideo(searchMediaRequestDto, httpServletRequest);
        if (searchMediaVos.isEmpty()) return MyResult.error(404,"找不到视频");
        return MyResult.success(searchMediaVos);
    }

    @PostMapping("/prefix/hot-word")
    public MyResult<List<String>> searchHotWord(@RequestBody HotWordContentDto hotWordContentDto) throws Exception {
        String content = hotWordContentDto.getContent();
        if(content == null || content.isEmpty()) return MyResult.error(403,"没法查");
        List<String> strings = hotWordRedisService.searchByPrefix(content);
        if(strings == null || strings.isEmpty()) return MyResult.error(404,"没有搜索到");
        return MyResult.success(strings);
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

}
