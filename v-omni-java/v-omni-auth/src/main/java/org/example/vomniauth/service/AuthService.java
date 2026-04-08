package org.example.vomniauth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.vomniauth.dto.AuthCodeRequestDTO;

import java.util.Map;

public interface AuthService {
    Long processAuthCode(AuthCodeRequestDTO authCodeRequestDTO);
    Long verifyAuthCode(AuthCodeRequestDTO authCodeRequestDTO);
    Long processLoginCode(AuthCodeRequestDTO authCodeRequestDTO);
    Map<String,String> verifyLoginCode(HttpServletResponse response, AuthCodeRequestDTO authCodeRequestDTO);
    void logout(HttpServletResponse response, HttpServletRequest request);
}
