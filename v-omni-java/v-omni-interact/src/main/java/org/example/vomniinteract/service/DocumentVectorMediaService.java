package org.example.vomniinteract.service;

import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.vo.RecommendMediaVo;
import org.redisson.api.RBloomFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface DocumentVectorMediaService {
    void updateFields(String id, Map<String, Object> fields) throws IOException;
    /**
     * 批量更新视频维度的计数快照 (like_count, comment_count, collect_count)
     * @param bulkUpdates Map<mediaId, Map<fieldName, changeAmount>>
     */
    void bulkUpdateCounts(Map<String, Map<String, Integer>> bulkUpdates);
    DocumentVectorMediaPo getById(String id);
    RecommendMediaVo getRecommendVoById(String id);

}
