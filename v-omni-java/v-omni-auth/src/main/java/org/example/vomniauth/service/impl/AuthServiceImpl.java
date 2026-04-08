package org.example.vomniauth.service.impl;

import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.dto.AuthCodeRequestDTO;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.po.UserPo;
import org.example.vomniauth.service.AuthService;
import org.example.vomniauth.util.JwtUtils;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.example.vomniauth.util.UsernameGenerator;
import org.redisson.api.RBloomFilter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final static int STATE_EXPIRE_TIME = 86400;

    private final static int RETRY_TIME = 10;

    private final static String AUTH_CODE_TOPIC = "auth-code-topic";

    private final static String LOGIN_CODE_TOPIC = "login-code-topic";

    private final static String INPUT_USER_INFORMATION_TOPIC = "input-user-information-topic";

    private final static int MAX_ATTEMPTS = 5;

    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    @Resource
    private DefaultRedisScript<Long> authCheckScript;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private DefaultRedisScript<Long> verifyCodeScript;

    @Resource
    private DefaultRedisScript<Long> loginCodeScript;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RBloomFilter<String> emailBloomFilter;

    @Resource
    private JwtUtils jwtUtils;

    @Override
    public Long processAuthCode(AuthCodeRequestDTO authCodeRequestDTO) {
        String email = authCodeRequestDTO.getEmail();
        String stateKey = "user:email:" + email + ":state";
        String retryKey = "register:email:" + email + ":retry";

        // 1. 布隆过滤器拦截 (挡住确定不存在的用户)
        if (emailBloomFilter.contains(email)) {
            // 安全地获取缓存状态，不使用 requireNonNull
            Object cachedState = redisTemplate.opsForValue().get(stateKey);

            // 如果缓存说是“2”（黑名单/已存在）或者数据库里真有
            if ("2".equals(String.valueOf(cachedState)) || userMapper.findIdByEmail(email) != null) {
                // 顺手补个缓存，防止下次再穿透到数据库
                if (cachedState == null)
                    redisTemplate.opsForValue().set(stateKey, "2", 7, TimeUnit.DAYS);
                return 0L;
            }
        }

        List<String> keys = List.of(
                stateKey,
                retryKey
        );
        Long execute = redisTemplate.execute(
                authCheckScript,
                keys,
                STATE_EXPIRE_TIME,
                RETRY_TIME
        );
        if(execute.equals(1L)) kafkaTemplate.send(AUTH_CODE_TOPIC, email);
        return execute;
    }

    @Override
    public Long verifyAuthCode(AuthCodeRequestDTO authCodeRequestDTO) {
        String email = authCodeRequestDTO.getEmail();
        String code = authCodeRequestDTO.getCode();
        List<String> keys = List.of(
                "user:email:" + email + ":state",
                "register:email:" + email + ":code",
                "user:email:" + email + ":attempts"
        );
        Long execute = redisTemplate.execute(
                verifyCodeScript,
                keys,
                code,
                MAX_ATTEMPTS,
                STATE_EXPIRE_TIME
        );
        if(execute.equals(2L))
            kafkaTemplate.send(INPUT_USER_INFORMATION_TOPIC,email);
        return 0L;
    }

    @Override
    public Long processLoginCode(AuthCodeRequestDTO authCodeRequestDTO) {
        String email = authCodeRequestDTO.getEmail();
        String stateKey = "user:email:" + email + ":state";
        String retryKey = "login:email:" + email + ":retry";
        if(emailBloomFilter.contains(authCodeRequestDTO.getEmail())) {

            Long execute = redisTemplate.execute(
                    loginCodeScript,
                    Collections.singletonList(retryKey),
                    RETRY_TIME
            );
            if(execute.equals(1L)) {
                Object o = redisTemplate.opsForValue().get(stateKey);
                if ("2".equals(String.valueOf(o))) {
                    kafkaTemplate.send(LOGIN_CODE_TOPIC, email);
                    return 1L;
                } else {
                    Long idByEmail = userMapper.findIdByEmail(email);
                    if (idByEmail != null) {
                        redisTemplate.opsForValue().set(stateKey, "2", 7, TimeUnit.DAYS);
                        kafkaTemplate.send(LOGIN_CODE_TOPIC, email);
                        return 1L;
                    }
                }
            }
            else if(execute.equals(-1L)) return -1L;
        }
        return 0L;
    }

    @Override
    public Map<String,String> verifyLoginCode(HttpServletResponse response, AuthCodeRequestDTO authCodeRequestDTO) {
        String code = authCodeRequestDTO.getCode();
        String email = authCodeRequestDTO.getEmail();
        String codeKey = "login:email:" + email + ":code";
        String attemptKey = "user:email:" + email + ":attempts";
        List<String> keys = List.of(
                codeKey,
                attemptKey
        );
        Long execute = redisTemplate.execute(
                verifyCodeScript,
                keys,
                code,
                MAX_ATTEMPTS
        );
        if(execute.equals(1L)) {
            Map<String,String> result = new HashMap<>();
            Object usernameRaw = redisTemplate.opsForValue().get("login:email:" + email + ":username");
            if(usernameRaw != null) {
                String username = (String) usernameRaw;
                String accessToken = jwtUtils.generateAccessToken(email, username);
                String refreshToken = jwtUtils.generateRefreshToken(email);
                redisTemplate.opsForValue().set("user:refresh_token:" + email, refreshToken, 7, TimeUnit.DAYS);
                ResponseCookie cookie = ResponseCookie.from("v_omni_refresh_token", refreshToken)
                        .httpOnly(true)
                        .path("/")
                        .maxAge(7 * 24 * 3600)
                        .sameSite("Lax")
                        .build();
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
                result.put("token", accessToken);
                result.put("code", "1");
                return result;
            }
        }
        else if(execute.equals(-1L)) return Map.of("code", "-1");
        else if(execute.equals(-2L)) return Map.of("code","-2");
        return Map.of("code","0");
    }

    @Override
    public void logout(HttpServletResponse response, HttpServletRequest request) {
        // 1. 从 Header 拿到 AccessToken
        String authHeader = request.getHeader("Authorization");
        String email = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                // 2. 解析 Token 获取 email (Subject)
                Claims claims = jwtUtils.parseToken(token);
                email = claims.getSubject();
            } catch (Exception e) {
                // Token 解析失败（可能已过期或非法），但退出登录依然可以继续清理 Cookie
                log.warn("Logout parse token failed, maybe expired.");
            }
        }

        // 3. 如果拿到了 email，清理 Redis 里的长效令牌
        if (email != null) {
            redisTemplate.delete("user:refresh_token:" + email);
        }

        // 4. 无论是否拿到 email，强制浏览器清除 Cookie (防止客户端残留)
        ResponseCookie cookie = ResponseCookie.from("v_omni_refresh_token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }


}
