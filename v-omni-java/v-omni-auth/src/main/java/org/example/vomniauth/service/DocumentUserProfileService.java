package org.example.vomniauth.service;


import java.util.Date;

public interface DocumentUserProfileService {
    void createProfileOnRegistration(String userId, Date registrationDate);
    void updateUserDemographics(String userId, int sex, int birthYear, String country, String province, String city);
}
