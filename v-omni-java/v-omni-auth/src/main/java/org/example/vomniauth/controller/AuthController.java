package org.example.vomniauth.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.vomniauth.common.MyResult;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.dto.AuthCodeRequestDTO;
import org.example.vomniauth.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    @PostMapping("/register/code")
    public MyResult<String> sendRegisterCode(@RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        AuthState authState = authService.processAuthCode(authCodeRequestDTO);
        switch (authState) {
            case EXCEED_LIMIT -> {
                return MyResult.error(429,"发送过于频繁，请5分钟后重试");
            }
            case PENDING -> {
                return MyResult.success();
            }
            case BLOCKED -> {
                return MyResult.error(403,"该用户已被锁定");
            }
            case ERROR -> {
                return MyResult.error(429,"系统繁忙");
            }
            default -> {
                return MyResult.error(409,"该用户已经注册");
            }
        }
    }

    @PostMapping("/register/verify")
    public MyResult<String> verifyRegisterCode(@RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        AuthState authState = authService.verifyAuthCode(authCodeRequestDTO);
        switch (authState) {
            case EXCEED_LIMIT -> {
                return MyResult.error(429,"发送过于频繁，请5分钟后重试");
            }
            case VERIFIED -> {
                return MyResult.success();
            }
            case BLOCKED -> {
                return MyResult.error(403,"该用户已被锁定");
            }
            case PENDING, INITIAL -> {
                return MyResult.error(400,"验证码错误");
            }
            case ERROR -> {
                return MyResult.error(429,"系统繁忙");
            }
            default -> {
                return MyResult.error(409,"该用户已经注册");
            }
        }
    }

    @PostMapping("/login/code")
    public MyResult<String> sendLoginCode(@RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        AuthState authState = authService.processLoginCode(authCodeRequestDTO);
        switch (authState) {
            case EXCEED_LIMIT -> {
                return MyResult.error(429,"发送过于频繁，请5分钟后重试");
            }
            case PENDING_LOGIN -> {
                return MyResult.success();
            }
            case BLOCKED -> {
                return MyResult.error(403,"该用户已被锁定");
            }
            case ERROR -> {
                return MyResult.error(429,"系统繁忙");
            }
            default -> {
                return MyResult.error(404,"请求用户尚无注册");
            }
        }
    }

    @PostMapping("/login/verify")
    public MyResult<String> verifyLoginCode(HttpServletResponse response,
                                            @RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        Map<String, String> stringStringMap = authService.verifyLoginCode(authCodeRequestDTO);
        AuthState authState = AuthState.valueOf(stringStringMap.get("state"));
        switch (authState) {
            case EXCEED_LIMIT -> {
                return MyResult.error(429,"发送过于频繁，请5分钟后重试");
            }
            case LOGGED_IN -> {
                String cookie = stringStringMap.get("cookie");
                String accessToken = stringStringMap.get("access_token");
                response.addHeader(HttpHeaders.SET_COOKIE, cookie);
                return MyResult.success(accessToken);
            }
            case PENDING_LOGIN, REGISTERED -> {
                return MyResult.error(400,"验证码错误");
            }
            case BLOCKED -> {
                return MyResult.error(403,"该用户已被锁定");
            }
            case ERROR -> {
                return MyResult.error(429,"系统繁忙");
            }
            default -> {
                return MyResult.error(404,"请求用户尚无注册");
            }
        }
    }

    @GetMapping("/logout")
    public MyResult<String> logout(HttpServletResponse response,
                                   @RequestHeader("Authorization") String authHeader) {
        String accessToken = authHeader.replace("Bearer ", "");
        authService.logout(response, accessToken);
        return MyResult.success();
    }

}
