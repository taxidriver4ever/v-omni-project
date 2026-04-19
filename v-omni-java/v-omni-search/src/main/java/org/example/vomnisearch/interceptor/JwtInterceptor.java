package org.example.vomnisearch.interceptor;

import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.util.JwtUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
public class JwtInterceptor implements HandlerInterceptor {

    @Resource
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");

        // 如果有 Token 且不为空
        if (token != null && !token.isEmpty()) {
            try {
                // 仅做解析，如果过期会抛出异常，这里可以捕获
                Claims claims = jwtUtils.parseToken(token);
                Long userId = claims.get("id", Long.class);
                // 关键：存入 request 域
                request.setAttribute("current_user_id", userId);
            } catch (Exception e) {
                // Token 无效或过期，这里不报错，直接放行（因为搜索接口是开放的）
                log.debug("Token 解析失败，视为游客搜索");
            }
        }
        return true; // 始终放行
    }
}
