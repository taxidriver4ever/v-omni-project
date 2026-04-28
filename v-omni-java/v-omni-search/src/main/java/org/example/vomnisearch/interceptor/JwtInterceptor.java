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
        String authHeader = request.getHeader("Authorization");

        // 1. 检查 Header 是否以 "Bearer " 开头
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // 2. 截取 "Bearer " 之后的字符串 (索引 7 开始)
            String token = authHeader.substring(7);

            if (!token.isEmpty()) {
                try {
                    Claims claims = jwtUtils.parseToken(token);
                    // 从标准字段 sub 中获取 ID
                    String sub = claims.getSubject();

                    if (sub != null) {
                        Long userId = Long.valueOf(sub); // 因为你生成的 sub 是 String 类型的 ID
                        request.setAttribute("current_user_id", userId);
                        log.info("current user id is: {}", userId);
                    }
                } catch (Exception e) {
                    log.warn("Token 解析失败: {}", e.getMessage());
                }
            }
        }

        return true;
    }
}
