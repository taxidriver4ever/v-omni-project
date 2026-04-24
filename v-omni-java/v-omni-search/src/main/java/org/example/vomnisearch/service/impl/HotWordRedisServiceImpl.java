package org.example.vomnisearch.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.service.HotWordRedisService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class HotWordRedisServiceImpl implements HotWordRedisService {

    private final StringRedisTemplate redisTemplate;

    // RediSearch 建议库的名字
    private static final String SUGGEST_DICTIONARY = "v-omni:search:suggest";
    // 兼容原有的排行榜
    private static final String HOT_WORDS_ZSET_KEY = "hot_words:global";

    public HotWordRedisServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 写入热词并增加分值
     */
    @Override
    public void incrementHotWord(String query, double score) {
        if (query == null || query.isBlank()) return;
        String term = query.trim().toLowerCase();

        // 1. 更新排行榜 (ZSet)，用于查询没有前缀时的“热门搜索”
        redisTemplate.opsForZSet().incrementScore(HOT_WORDS_ZSET_KEY, term, score);

        // 2. 更新 RediSearch 建议库 (FT.SUGADD)
        // 参数说明: 库名, 词项, 分值, [INCR] 代表在原有分值上累加
        redisTemplate.execute(connection -> {
            connection.execute("FT.SUGADD",
                    SUGGEST_DICTIONARY.getBytes(),
                    term.getBytes(),
                    String.valueOf(score).getBytes(),
                    "INCR".getBytes());
            return null;
        }, true);
    }


    /**
     * 前缀联想搜索（带分数排序）
     */
    @Override
    public List<String> searchByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return Collections.emptyList();

        // 核心修改：添加 "WITHSCORES" 参数
        Object result = redisTemplate.execute(connection ->
                        connection.execute("FT.SUGGET",
                                SUGGEST_DICTIONARY.getBytes(),
                                prefix.trim().toLowerCase().getBytes(),
                                "MAX".getBytes(), "10".getBytes(),
                                "WITHSCORES".getBytes()) // 👈 返回分数
                , true);

        if (result instanceof List<?> rawList) {
            List<String> suggestions = new ArrayList<>();
            // 注意：开启 WITHSCORES 后，结果是 [string, score, string, score...]
            // 我们只需要其中的词（偶数下标）
            for (int i = 0; i < rawList.size(); i += 2) {
                Object obj = rawList.get(i);
                if (obj instanceof byte[]) {
                    suggestions.add(new String((byte[]) obj));
                }
            }
            return suggestions;
        }
        return Collections.emptyList();
    }

    @Override
    public Set<String> getTopHotWords(int n) {
        return redisTemplate.opsForZSet().reverseRange(HOT_WORDS_ZSET_KEY, 0, n - 1);
    }
}