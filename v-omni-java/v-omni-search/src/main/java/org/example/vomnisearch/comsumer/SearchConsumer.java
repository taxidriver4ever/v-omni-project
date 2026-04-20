package org.example.vomnisearch.comsumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.dto.SearchHistoryDTO;
import org.example.vomnisearch.mapper.UserSearchHistoryMapper;
import org.example.vomnisearch.po.UserSearchHistoryPo;
import org.example.vomnisearch.service.StopWordService;
import org.example.vomnisearch.service.DocumentVectorMediaService;
import org.example.vomnisearch.util.SnowflakeIdWorker;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class SearchConsumer {

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Boolean>upsertScript;

    @Resource
    private StopWordService stopWordService;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private UserSearchHistoryMapper userSearchHistoryMapper;

    private final static String HOT_WORDS_KEY = "hot_words:global";

    private final static String SEARCH_SCORE = "4";

    @PostConstruct
    public void uselessWordsAdd() {
        try {
            stopWordService.importFromDirectory();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "hot-word-topic", groupId = "v-omni-media-group")
    public void hotWordConsume(@NotNull String message) {
        // 1. 基础清洗（正则去杂质）
        String regex = "[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]";
        String cleanInput = message.replaceAll(regex, " ").trim().replaceAll("\\s+", " ");

        // 2. 长度安全截断
        if (cleanInput.length() < 2 || cleanInput.length() > 50) return;

        // 3. 处理原始搜索词（给 4 分）
        // 只有当原词不是垃圾词时才写入
        if (cleanInput.length() <= 20 && !stopWordService.isStopWord(cleanInput)) {
            executeUpsert(cleanInput, "4");
        }

        // 4. 处理切词逻辑（仅针对中长句）
        if (cleanInput.length() > 6 && cleanInput.length() <= 20) {
            try {
                List<String> tokens = documentVectorMediaService.analyzeText(cleanInput);
                for (String token : tokens) {
                    // 过滤：单字、垃圾词、以及与原词重复的情况
                    if (token.length() > 1
                            && !token.equalsIgnoreCase(cleanInput)
                            && !stopWordService.isStopWord(token)) {
                        executeUpsert(token, "2");
                    }
                }
            } catch (Exception e) {
                log.error("分词处理异常: {}", e.getMessage());
                // 注意：分词失败不要影响主流程，捕获异常即可
            }
        }
    }

    @KafkaListener(topics = "user-history-topic", groupId = "v-omni-media-group")
    public void userHistoryConsume(@NotNull SearchHistoryDTO searchHistoryDTO) {
        Long userId = searchHistoryDTO.getUserId();
        String keyword = searchHistoryDTO.getKeyword();
        String redisKey = "search:keyword:user_id:" + userId;

        long now = System.currentTimeMillis();
        long sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000L);

        // 1. 插入新词（使用当前时间戳作为 score）
        // 如果词已存在，add 操作会更新其 score 为最新时间，实现“位置置顶”
        stringRedisTemplate.opsForZSet().add(redisKey, keyword, now);

        // 2. 核心滑动窗口逻辑：
        // 第一步：先保证数量。如果超过 10 条，我们才考虑删旧的。
        // 我们先计算第 11 条及更早的词的范围（ZSet 从小到大排，旧词在前面）
        Long totalSize = stringRedisTemplate.opsForZSet().zCard(redisKey);

        if (totalSize != null)
            stringRedisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, sevenDaysAgo);

        // 3. MySQL 正常记录，不参与窗口逻辑
        Long id = snowflakeIdWorker.nextId();
        UserSearchHistoryPo po = new UserSearchHistoryPo(id, userId, keyword);
        po.setCreateTime(new Date());
        po.setUpdateTime(new Date());
        userSearchHistoryMapper.addUserSearchHistoryIfAbsentUpdateTime(po);

        // 4. 防止冷用户占用内存，设置 Key 整体过期时间（如 30 天）
        stringRedisTemplate.expire(redisKey, 30, TimeUnit.DAYS);
    }


    private void executeUpsert(String word, String score) {
        stringRedisTemplate.execute(
                upsertScript,
                Collections.singletonList(HOT_WORDS_KEY),
                word,
                score
        );
    }



}
