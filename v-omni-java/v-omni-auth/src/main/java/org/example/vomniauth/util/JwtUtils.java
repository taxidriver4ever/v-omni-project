package org.example.vomniauth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.example.vomniauth.po.UserPo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtils {

    @Value("${v-omni.auth.jwt-secret}")
    private String secretKeyString; // 变量名建议小驼峰

    private SecretKey KEY; // 去掉 final，因为要在初始化方法里赋值

    // 关键：在依赖注入完成后执行
    @PostConstruct
    public void init() {
        this.KEY = Keys.hmacShaKeyFor(secretKeyString.getBytes(StandardCharsets.UTF_8));
    }

    private static final long ACCESS_EXP = 1000 * 60 * 60 * 2; // 2小时
    private static final long REFRESH_EXP = 1000 * 60 * 60 * 24 * 7; // 7天

    /**
     * AccessToken: 存入丰富的用户数据
     */
    public String generateAccessToken(String email,String username) {
        return Jwts.builder()
                .subject(email)
                .claim("un", username) // 用户名
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXP))
                .signWith(KEY)
                .compact();
    }

    /**
     * RefreshToken: 只存 Email，保持轻量和安全
     */
    public String generateRefreshToken(String email) {
        return Jwts.builder()
                .subject(email)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_EXP))
                .signWith(KEY)
                .compact();
    }

    /**
     * 解析 Token 拿到 Claims (载荷数据)
     * * @param token 传入的 JWT 字符串
     * @return Claims 对象，如果解析失败则抛出异常
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(KEY) // 使用签名时的密钥进行验证
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}