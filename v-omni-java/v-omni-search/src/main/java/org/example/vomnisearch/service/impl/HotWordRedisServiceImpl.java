package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.service.HotWordRedisService;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public HotWordRedisServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Resource
    private DefaultRedisScript<Long> sugAddScript;

    @Override
    public void incrementHotWord(String query, double score) {
        if (query == null || query.isBlank()) return;
        String term = query.trim().toLowerCase();

        try {
            // 1. ZSet 更新（无隐患）
            stringRedisTemplate.opsForZSet().incrementScore(HOT_WORDS_ZSET_KEY, term, score);

            // 2. 使用 Lua 脚本绕过驱动解析 FT 指令响应的坑
            stringRedisTemplate.execute(sugAddScript,
                    Collections.singletonList(SUGGEST_DICTIONARY),
                    term, String.valueOf(score));

        } catch (Exception e) {
            log.warn("RediSearch 建议库通过 Lua 写入失败（通常是驱动解析问题），已跳过: {}", e.getMessage());
        }
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