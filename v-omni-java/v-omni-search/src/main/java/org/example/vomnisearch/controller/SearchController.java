package org.example.vomnisearch.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.checkerframework.checker.units.qual.K;
import org.example.vomnisearch.common.MyResult;
import org.example.vomnisearch.dto.SearchHistoryDTO;
import org.example.vomnisearch.dto.UserContent;
import org.example.vomnisearch.po.PrefixSearchPo;
import org.example.vomnisearch.service.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/search")
public class SearchController {

    private final static String USER_HISTORY_TOPIC = "user-history-topic";

    @Resource
    private SearchService searchService;

    @Resource
    private PrefixSearchService prefixSearchService;

    @Resource
    private KafkaTemplate<String, SearchHistoryDTO> kafkaTemplate;

    @PostMapping("/hybrid/video")
    public MyResult<List<String>> searchVideo(@RequestBody UserContent userContent, HttpServletRequest request) throws Exception {
        String content = userContent.getContent();
        if(content == null || content.isEmpty()) return MyResult.error(403,"没法查");
        if(content.length() > 50) content = content.substring(0, 50);
        Long userId = (Long) request.getAttribute("current_user_id");
        if (userId != null) {
            SearchHistoryDTO dto = new SearchHistoryDTO(userId, content);
            kafkaTemplate.send(USER_HISTORY_TOPIC, dto);
        }
        List<String> strings = searchService.searchVideo(content);
        if (strings.isEmpty()) return MyResult.error(404,"找不到视频");
        return MyResult.success(strings);
    }

    @PostMapping("/prefix/hot-word")
    public MyResult<List<PrefixSearchPo>> searchHotWord(@RequestBody UserContent userContent) throws Exception {
        String content = userContent.getContent();
        if(content == null || content.isEmpty()) return MyResult.error(403,"没法查");
        List<PrefixSearchPo> suggestions = prefixSearchService.getSuggestions(content);
        if(suggestions == null || suggestions.isEmpty()) return MyResult.error(404,"没有搜索到");
        return MyResult.success(suggestions);
    }

    @GetMapping("/history")
    public MyResult<List<String>> getHistory(HttpServletRequest request) {
        // 从拦截器解析出的 request 域中获取 userId
        Long userId = (Long) request.getAttribute("current_user_id");

        if (userId == null) {
            return MyResult.success(Collections.emptyList());
        }

        List<String> history = searchService.getUserHistory(userId);
        return MyResult.success(history);
    }


}
