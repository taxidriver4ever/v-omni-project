package org.example.vomniinteract.service;


import org.example.vomniinteract.grpc.InterestCentroid;
import org.example.vomniinteract.po.DocumentUserProfilePo;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface DocumentUserProfileService {
    Optional<DocumentUserProfilePo> getUserProfile(String userId) throws IOException;

    float[] getUserInterestVector(String userId) throws IOException;

    float[] getUserQueryVector(String userId) throws IOException;

    void updateUserProfile(String userId, float[] newInterestVector, Date date) throws IOException;

    List<InterestCentroid> getInterestCentroids(String userId) throws IOException;

}