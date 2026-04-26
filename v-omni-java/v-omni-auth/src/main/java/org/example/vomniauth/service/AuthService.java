package org.example.vomniauth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.dto.AuthCodeRequestDTO;

import java.util.Map;

public interface AuthService {
    AuthState processAuthCode(AuthCodeRequestDTO authCodeRequestDTO);
    AuthState verifyAuthCode(AuthCodeRequestDTO authCodeRequestDTO);
    AuthState processLoginCode(AuthCodeRequestDTO authCodeRequestDTO);
    Map<String,String> verifyLoginCode(AuthCodeRequestDTO authCodeRequestDTO);
    void logout(HttpServletResponse response, String accessToken);
}
