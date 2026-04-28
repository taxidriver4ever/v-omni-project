package org.example.vomnisearch.service;

import org.example.vomnisearch.po.DocumentUserProfilePo;
import java.io.IOException;
import java.util.Date;
import java.util.Optional;

public interface DocumentUserProfileService {

    // 获取 Query (512维实时画像)
    float[] getUserQueryVector(String userId) throws IOException;

    // 获取长期头 K/V (64x512 矩阵)
    float[][] getLongTermKV(String userId) throws IOException;

    float[] getUserInterestVector(String userId) throws IOException;
    /**
     * 更新用户画像
     * @param userId 用户ID
     * @param newInterestVector MLP计算后的新兴趣向量
     */
    void updateUserProfile(String userId, float[] newInterestVector, Date date) throws IOException;

    void updateWeeklyMatrix(String userId, float[] flattenedMatrix) throws IOException;
}