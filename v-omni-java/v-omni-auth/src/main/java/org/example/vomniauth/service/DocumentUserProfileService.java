package org.example.vomniauth.service;


import java.util.Date;

public interface DocumentUserProfileService {
    void createProfileOnRegistration(String userId, Date registrationDate);
}
