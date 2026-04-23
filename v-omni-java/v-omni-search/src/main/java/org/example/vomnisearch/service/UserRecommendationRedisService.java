package org.example.vomnisearch.service;

import java.util.List;

public interface UserRecommendationRedisService {
    List<String> getRecentSeenIds(String userId, int limit);
    void addSeenId(Long userId, String mediaId);
    void saveInterestVector(Long userId, float[] vector);
    float[] getInterestVector(Long userId);
    void addSeenIdToZSet(String userId, String mediaId);
}
