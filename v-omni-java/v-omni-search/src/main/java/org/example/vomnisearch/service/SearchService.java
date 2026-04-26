package org.example.vomnisearch.service;

import jakarta.servlet.http.HttpServletRequest;
import org.example.vomnisearch.dto.SearchMediaRequestDto;
import org.example.vomnisearch.dto.UserContent;
import org.example.vomnisearch.vo.RecommendMediaVo;
import org.example.vomnisearch.vo.SearchMediaVo;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface SearchService {
    List<SearchMediaVo> searchVideo(SearchMediaRequestDto searchMediaRequestDto, HttpServletRequest request) throws Exception;
    /**
     * 获取用户最近的搜索词
     * @param userId 用户ID
     * @return 搜索词列表
     */
    List<String> getUserHistory(Long userId);
    void removeHistory(String userId, String keyword);
    void clearAllHistory(String userId);
    List<RecommendMediaVo> getRecommendMedia(HttpServletRequest request);
}
