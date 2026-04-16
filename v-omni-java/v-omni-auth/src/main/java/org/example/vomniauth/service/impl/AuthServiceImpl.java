package org.example.vomniauth.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.domain.statemachine.AuthEvent;
import org.example.vomniauth.domain.statemachine.AuthEventContext;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.domain.statemachine.AuthTransitionService;
import org.example.vomniauth.dto.AuthCodeRequestDTO;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.service.AuthService;
import org.example.vomniauth.service.IdentityService;
import org.example.vomniauth.util.JwtUtils;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.jetbrains.annotations.NotNull;
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
    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    @Resource
    private RBloomFilter<String> emailBloomFilter;

    @Resource
    private RBloomFilter<String> idBloomFilter;

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private IdentityService identityService;

    @Resource
    private AuthTransitionService authTransitionService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public AuthState processAuthCode(@NotNull AuthCodeRequestDTO authCodeRequestDTO) {
        String email = authCodeRequestDTO.getEmail();
        Long id = identityService.getOrCreateUserIdByEmail(email);
        if(id == 0L) return AuthState.REGISTERED;
        AuthEventContext authEventContext = new AuthEventContext(id).with("email", email);
        return authTransitionService.sendEvent(authEventContext, AuthEvent.REGISTER_SEND_CODE);
    }

    @Override
    public AuthState verifyAuthCode(@NotNull AuthCodeRequestDTO authCodeRequestDTO) {
        String code = authCodeRequestDTO.getCode();
        String email = authCodeRequestDTO.getEmail();

        String emailToIdKey = "auth:id:email:" + email;
        Object o = redisTemplate.opsForValue().get(emailToIdKey);
        if(o == null) return AuthState.INITIAL;
        AuthEventContext authEventContext = new AuthEventContext((Long)o)
                .with("code", code)
                .with("email", email);
        return authTransitionService.sendEvent(authEventContext, AuthEvent.REGISTER_VERIFY_CODE);
    }

    @Override
    public AuthState processLoginCode(@NotNull AuthCodeRequestDTO authCodeRequestDTO) {
        String email = authCodeRequestDTO.getEmail();
        if(!emailBloomFilter.contains(email)) return AuthState.INITIAL;

        Long id = identityService.getIdByEmail(email);
        if(id.equals(0L)) return AuthState.INITIAL;

        AuthEventContext authEventContext = new AuthEventContext(id).with("email", email);
        return authTransitionService.sendEvent(authEventContext, AuthEvent.LOGIN_SEND_CODE);
    }

    @Override
    public Map<String,String> verifyLoginCode(@NotNull AuthCodeRequestDTO authCodeRequestDTO) {
        Map<String,String> res = new HashMap<>();
        String email = authCodeRequestDTO.getEmail();
        String code = authCodeRequestDTO.getCode();
        if(!emailBloomFilter.contains(email)) {
            res.put("state",AuthState.INITIAL.toString());
            return res;
        }

        Long id = identityService.getIdByEmail(email);
        if(id.equals(0L)) {
            res.put("state",AuthState.INITIAL.toString());
            return res;
        }

        AuthEventContext authEventContext = new AuthEventContext(id).with("email", email).with("code",code);
        AuthState authState = authTransitionService.sendEvent(authEventContext, AuthEvent.LOGIN_VERIFY_CODE);

        res.put("state",authState.toString());

        if(authState != AuthState.LOGGED_IN) return res;
        String refreshToken = authEventContext.getString("refresh_token");
        String accessToken = authEventContext.getString("access_token");
        String cookie = authEventContext.getString("cookie");

        res.put("access_token", accessToken);
        res.put("refresh_token", refreshToken);
        res.put("cookie", cookie);
        return res;
    }

    @Override
    public void logout(HttpServletResponse response, String accessToken) {

        Claims claims = jwtUtils.parseToken(accessToken);

        String id = claims.getSubject();

        if(!idBloomFilter.contains(id)) return;

        String jti = claims.getId();
        Date exp = claims.getExpiration();

        long ttl = exp.getTime() - System.currentTimeMillis();

        if (ttl > 0) {
            stringRedisTemplate.opsForValue().set(
                    "blacklist:access_token:" + jti,
                    "1",
                    ttl,
                    TimeUnit.MILLISECONDS
            );
        }
        ResponseCookie deleteCookie = ResponseCookie.from("v_omni_refresh_token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, deleteCookie.toString());
    }


}
