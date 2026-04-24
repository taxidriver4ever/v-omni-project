package org.example.vomnisearch.service;

import java.util.List;
import java.util.Set;

public interface HotWordRedisService {
    /**
     * 增加搜索词频率（写入/更新）
     */
    void incrementHotWord(String query, double score);

    /**
     * 获取热搜榜 Top N
     */
    Set<String> getTopHotWords(int n);

    /**
     * 根据前缀查询建议词
     */
    List<String> searchByPrefix(String prefix);
}
