package org.example.vomniauth.service;

import org.example.vomniauth.po.DocumentUserProfilePo;

import java.io.IOException;
import java.util.Date;

public interface DocumentUserProfileService {
    void createProfileOnRegistration(String userId, Date registrationDate);
}
