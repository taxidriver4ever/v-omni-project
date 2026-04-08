package org.example.vomniauth.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.vomniauth.common.MyResult;
import org.example.vomniauth.dto.AuthCodeRequestDTO;
import org.example.vomniauth.service.AuthService;
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
        Long l = authService.processAuthCode(authCodeRequestDTO);
        if(l != null) {
            if(l.equals(0L))
                return MyResult.success("用户已经注册");
            else if(l.equals(1L))
                return MyResult.success("发送成功");
            else if(l.equals(-1L))
                return MyResult.error(429,"发送过于频繁稍后重试");
        }
        return MyResult.error(500,"未知错误");
    }

    @PostMapping("/register/verify")
    public MyResult<String> verifyRegisterCode(@RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        Long l = authService.verifyAuthCode(authCodeRequestDTO);
        if(l != null) {
            if(l.equals(1L))
                return MyResult.success("用户注册成功");
            else if(l.equals(2L))
                return MyResult.success("验证成功");
            else if(l.equals(-1L))
                return MyResult.error(400,"验证码输入错误");
            else if(l.equals(-2L))
                return MyResult.error(404,"用户信息不存在");
            else if(l.equals(-3L))
                return MyResult.error(400,"验证码已过期");
            else if(l.equals(-4L))
                return MyResult.error(429,"验证码输入错误多次账号已被锁定");
        }
        return MyResult.error(500,"未知错误");
    }

    @PostMapping("/login/code")
    public MyResult<String> sendLoginCode(@RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        Long l = authService.processLoginCode(authCodeRequestDTO);
        if(l != null) {
            if(l.equals(1L))
                return MyResult.success("发送成功");
            else if(l.equals(-1L))
                return MyResult.error(429,"发送过于频繁稍后重试");
        }
        return MyResult.error(500,"未知错误");
    }

    @PostMapping("/login/verify")
    public MyResult<String> verifyLoginCode(HttpServletResponse response, @RequestBody @Valid AuthCodeRequestDTO authCodeRequestDTO) {
        Map<String, String> stringStringMap = authService.verifyLoginCode(response, authCodeRequestDTO);
        if(stringStringMap != null) {
            if("1".equals(stringStringMap.get("code"))) return MyResult.success(stringStringMap.get("token"));
            else if("-1".equals(stringStringMap.get("code"))) return MyResult.error(400,"验证码错误");
            else if("-2".equals(stringStringMap.get("code"))) return MyResult.error(429,"访问过于频繁");
        }
        return MyResult.error(500,"未知错误");
    }

    @GetMapping("/logout")
    public MyResult<String> logout(HttpServletResponse response, HttpServletRequest request) {
        authService.logout(response, request);
        return MyResult.success("注销成功");
    }

}
