package org.example.vomniauth.domain.statemachine;

import io.jsonwebtoken.Jwts;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.protocol.types.Field;
import org.example.vomniauth.util.JwtUtils;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AuthAction {

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    private final static String AUTH_CODE_TOPIC = "auth-code-topic";

    private final static String LOGIN_CODE_TOPIC = "login-code-topic";

    private final static String INPUT_USER_INFORMATION_TOPIC = "input-user-information-topic";

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private RBloomFilter<String> idBloomFilter;

    public void initialOnRegisterSendCodeToPendingAction(@NotNull AuthEventContext authEventContext) {
        registerSendCode(authEventContext);
    }

    public void pendingOnRegisterSendCodeToPendingAction(@NotNull AuthEventContext authEventContext) {
        registerSendCode(authEventContext);
    }

    public void pendingOnRegisterSendCodeToExceedLimitAction(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        log.info("{}发送注册验证码尝试次数过多", id);
    }

    public void pendingOnRegisterVerifyCodeToVerifiedAction(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        String email = authEventContext.getString("email");
        kafkaTemplate.send(INPUT_USER_INFORMATION_TOPIC, id.toString()+":"+email);
    }

    public void pendingOnRegisterVerifyCodeToExceedLimitAction(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        log.info("{}尝试验证注册验证码次数过多", id);
    }

    public void registeredOnLoginSendCodeToPendingLoginAction(@NotNull AuthEventContext authEventContext) {
        loginSendCode(authEventContext);
    }

    public void pendingLoginOnLoginSendCodeToPendingLoginAction(@NotNull AuthEventContext authEventContext) {
        loginSendCode(authEventContext);
    }

    public void pendingLoginOnLoginSendCodeToExceedLimitAction(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        log.info("{}发送登录验证码次数过多", id);
    }

    public void pendingLoginOnLoginVerifyCodeToLoggedInAction(@NotNull AuthEventContext authEventContext) {
        String idString = authEventContext.getId().toString();
        authEventContext.with("access_token", jwtUtils.generateAccessToken(idString));
        String refreshToken =  jwtUtils.generateRefreshToken(idString);
        authEventContext.with("refresh_token",refreshToken);
        ResponseCookie cookie = ResponseCookie.from("v_omni_refresh_token", refreshToken)
                .httpOnly(true)
                .path("/")
                .maxAge(7 * 24 * 3600)
                .sameSite("Lax")
                .build();
        authEventContext.with("cookie", cookie.toString());
        idBloomFilter.add(idString);
    }

    public void pendingLoginOnLoginVerifyCodeToExceedLimitAction(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        log.info("{}尝试验证登录验证码次数过多", id);
    }

    private void loginSendCode(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        String email = authEventContext.getString("email");
        kafkaTemplate.send(LOGIN_CODE_TOPIC, id.toString()+":"+email);
    }

    private void registerSendCode(@NotNull AuthEventContext authEventContext) {
        Long id = authEventContext.getId();
        String email = authEventContext.getString("email");
        kafkaTemplate.send(AUTH_CODE_TOPIC, id.toString()+":"+email);
    }

}
