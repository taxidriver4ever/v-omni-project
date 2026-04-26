package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.example.vomnisearch.dto.SearchHistoryDTO;
import org.example.vomnisearch.dto.SearchMediaRequestDto;
import org.example.vomnisearch.dto.UserSearchVectorDto;
import org.example.vomnisearch.service.*;
import org.example.vomnisearch.util.VectorUtil;
import org.example.vomnisearch.vo.RecommendMediaVo;
import org.example.vomnisearch.vo.SearchMediaVo;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBloomFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SearchServiceImpl implements SearchService {

    private final static String HOT_WORD_TOPIC = "hot-word-topic";

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private VectorService vectorService;

    @Resource
    private MinioService minioService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private KafkaTemplate<String, SearchHistoryDTO> searchHistoryDTOKafkaTemplate;

    @Resource
    private DocumentUserProfileService documentUserProfileService;

    @Resource
    private KafkaTemplate<String, UserSearchVectorDto> userSearchVectorKafkaTemplate;

    @Resource
    private UserBloomFilterService userBloomFilterService;

    private static final String HISTORY_PREFIX = "search:keyword:user_id:";

    private final static String USER_HISTORY_TOPIC = "user-history-topic";

    private final static String USER_FEATURE_UPDATE_TOPIC = "user-feature-update-topic";

    @Override
    public List<SearchMediaVo> searchVideo(@NotNull SearchMediaRequestDto searchMediaRequestDto,
                                    HttpServletRequest request) throws Exception {

        String content = searchMediaRequestDto.getQueryText();
        Integer page = searchMediaRequestDto.getPage();
        Long userId = (Long) request.getAttribute("current_user_id");

        if(content == null || content.isEmpty() || page < 1) return Collections.emptyList();
        if(content.length() > 50) content = content.substring(0, 50);
        if (userId != null) {
            SearchHistoryDTO dto = new SearchHistoryDTO(userId, content);
            searchHistoryDTOKafkaTemplate.send(USER_HISTORY_TOPIC, dto);
        }

        kafkaTemplate.send(HOT_WORD_TOPIC,content);

        float[] vector = vectorService.getTextVector(content);

        if(vector == null) return Collections.emptyList();

        List<SearchMediaVo> searchMediaVos = documentVectorMediaService.hybridSearch(content, vector, page, 10);
        if(searchMediaVos == null || searchMediaVos.isEmpty()) return Collections.emptyList();
        for (SearchMediaVo searchMediaVo : searchMediaVos) {
            String string = searchMediaVo.getMediaId();
            String urlKey = "search:url:media-id:" + string;
            String redisUrl = stringRedisTemplate.opsForValue().get(urlKey);
            if(redisUrl != null) {
                searchMediaVo.setMediaUrl(redisUrl);
                continue;
            }
            String s = minioService.generateHlsPlaybackUrl(string);
            stringRedisTemplate.opsForValue().setIfAbsent(urlKey, s, 29, TimeUnit.MINUTES);
            searchMediaVo.setMediaUrl(s);
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float f : vector) buffer.putFloat(f);
        byte[] array = buffer.array();

        UserSearchVectorDto userSearchVectorDto = new UserSearchVectorDto();
        userSearchVectorDto.setVector(array);
        userSearchVectorDto.setUserId(String.valueOf(userId));
        userSearchVectorDto.setUpdateTime(new Date());

        userSearchVectorKafkaTemplate.send(USER_FEATURE_UPDATE_TOPIC, userSearchVectorDto);
        return searchMediaVos;
    }

    @Override
    public List<String> getUserHistory(Long userId) {
        String redisKey = HISTORY_PREFIX + userId;

        // reverseRange(key, start, end) 是闭区间
        // 0 到 9 代表取按分数从大到小排的前 10 个元素
        Set<String> historySet = stringRedisTemplate.opsForZSet().reverseRange(redisKey, 0, 9);

        if (historySet == null || historySet.isEmpty()) {
            return Collections.emptyList();
        }

        // 转为 List 返回给前端，保持顺序
        return new ArrayList<>(historySet);
    }

    @Override
    public void removeHistory(String userId, String keyword) {
        // 仅删除 Redis 视图，用户搜不到这行历史，但 MySQL 数据依旧存在用于画像
        stringRedisTemplate.opsForZSet().remove(HISTORY_PREFIX + userId, keyword);
    }

    @Override
    public void clearAllHistory(String userId) {
        // 直接删除整个 ZSet
        stringRedisTemplate.delete(HISTORY_PREFIX + userId);
    }

    @Override
    public List<RecommendMediaVo> getRecommendMedia(HttpServletRequest request) {
        Object currentUserId = request.getAttribute("current_user_id");
        if(currentUserId == null) {
            try {
                return documentVectorMediaService.recommendRandom(14);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        else {
            String userId = (String) currentUserId;
            RBloomFilter<String> userFilter = userBloomFilterService.getFilter(Long.valueOf(userId));
            try {
                float[] userInterestVector = documentUserProfileService.getUserInterestVector(userId);

                return documentVectorMediaService.recommendByInterest(userInterestVector,14, userFilter, userId);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
