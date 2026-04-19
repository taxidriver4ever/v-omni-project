package org.example.vomnisearch.service;

import org.example.vomnisearch.dto.UserContent;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface SearchService {
    List<String> searchVideo(String content) throws Exception;
    /**
     * 获取用户最近的搜索词
     * @param userId 用户ID
     * @return 搜索词列表
     */
    List<String> getUserHistory(Long userId);
    void removeHistory(String userId, String keyword);
    void clearAllHistory(String userId);
}
