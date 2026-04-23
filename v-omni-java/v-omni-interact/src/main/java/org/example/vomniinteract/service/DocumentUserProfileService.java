package org.example.vomniinteract.service;


import org.example.vomniinteract.po.DocumentUserProfilePo;

import java.io.IOException;
import java.util.Date;
import java.util.Optional;

public interface DocumentUserProfileService {
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