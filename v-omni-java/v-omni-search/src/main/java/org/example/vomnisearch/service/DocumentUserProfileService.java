package org.example.vomnisearch.service;

import org.example.vomnisearch.po.DocumentUserProfilePo;
import java.io.IOException;
import java.util.Date;
import java.util.Optional;

public interface DocumentUserProfileService {
    /**
     * 根据用户ID获取画像数据（包含两个向量）
     */
    Optional<DocumentUserProfilePo> getUserProfile(String userId) throws IOException;

    float[] getUserInterestVector(String userId) throws IOException;
    /**
     * 更新用户画像
     * @param userId 用户ID
     * @param currentQueryVector 本次搜索向量（将存入 lastSearchVector）
     * @param newInterestVector MLP计算后的新兴趣向量
     */
    void updateUserProfile(String userId, float[] currentQueryVector, float[] newInterestVector, Date date) throws IOException;
}