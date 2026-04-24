package org.example.vomniinteract.filter;


import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.util.JwtUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 从请求头获取 Token
        String token = extractToken(request);

        // 2. 如果没有 Token，直接放行（让后续 Security 配置决定是否拒绝）
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 解析并校验 Token
        try {
            Claims claims = jwtUtils.parseToken(token);

            // 4. 业务校验：检查 Token 类型
            String tokenType = claims.get("type", String.class);
            if (!"access_token".equals(tokenType)) {
                sendUnauthorizedError(response, "无效的令牌类型");
                return;
            }

            String id = claims.getId();
            if (stringRedisTemplate.opsForValue().get("blacklist:access_token:"+id) != null)
            {
                // 不要直接 throw 裸类，手动抛出异常或直接调用错误处理函数
                log.warn("检测到黑名单 Token: {}", id);
                sendUnauthorizedError(response, "该令牌已失效，请重新登录");
                return; // 记得 return，阻止过滤器链继续向下走
            }

            // 5. 提取用户信息并构建认证对象
            String userId = claims.getSubject();
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,  // 凭证设为 null，JWT 已验证
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                    );

            // 6. 存入 SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 7. 继续执行过滤器链
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            sendUnauthorizedError(response, "令牌已过期");
        } catch (SecurityException e) {
            sendUnauthorizedError(response, "令牌签名无效");
        } catch (MalformedJwtException e) {
            sendUnauthorizedError(response, "令牌格式错误");
        }
    }

    /**
     * 从 Authorization 头提取 Bearer Token
     */
    private @Nullable String extractToken(@NotNull HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * 返回统一的 401 错误响应
     */
    private void sendUnauthorizedError(@NotNull HttpServletResponse response, String message) throws IOException {
        log.error(message);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
    }
}