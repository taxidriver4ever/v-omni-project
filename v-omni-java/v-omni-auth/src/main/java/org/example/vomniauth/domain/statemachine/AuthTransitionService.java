package org.example.vomniauth.domain.statemachine;

import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.mapper.UserMapper;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AuthTransitionService {

    @Resource
    private AuthAction authAction;

    @Resource
    private JedisPool jedisPool;

    private final Map<String, Map<AuthState,AuthRule>> rules = new HashMap<>();

    @PostConstruct
    public void initRules() {
        try (Jedis jedis = jedisPool.getResource()) {

            String lua;
            try (InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("lua/send_event.lua")) {

                lua = new String(Objects.requireNonNull(is).readAllBytes());
            }

            jedis.functionLoadReplace(lua);

            log.info("Lua 加载完成");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        rules.put(AuthState.INITIAL + ":" + AuthEvent.REGISTER_SEND_CODE,
                Map.of(
                        AuthState.PENDING,
                        AuthRule.builder()
                                .from(AuthState.INITIAL)
                                .on(AuthEvent.REGISTER_SEND_CODE)
                                .to(AuthState.PENDING)
                                .action(authAction::initialOnRegisterSendCodeToPendingAction)
                                .build()
                )
        );
        rules.put(AuthState.PENDING + ":" + AuthEvent.REGISTER_SEND_CODE,
                Map.of(
                        AuthState.PENDING,
                        AuthRule.builder()
                                .from(AuthState.PENDING)
                                .on(AuthEvent.REGISTER_SEND_CODE)
                                .to(AuthState.PENDING)
                                .action(authAction::pendingOnRegisterSendCodeToPendingAction)
                                .build()
                        ,
                        AuthState.EXCEED_LIMIT,
                        AuthRule.builder()
                                .from(AuthState.PENDING)
                                .on(AuthEvent.REGISTER_SEND_CODE)
                                .to(AuthState.EXCEED_LIMIT)
                                .action(authAction::pendingOnRegisterSendCodeToExceedLimitAction)
                                .build()
                )
        );
        rules.put(AuthState.PENDING + ":" + AuthEvent.REGISTER_VERIFY_CODE,
                Map.of(
                        AuthState.VERIFIED,
                        AuthRule.builder()
                                .from(AuthState.PENDING)
                                .on(AuthEvent.REGISTER_VERIFY_CODE)
                                .to(AuthState.VERIFIED)
                                .action(authAction::pendingOnRegisterVerifyCodeToVerifiedAction)
                                .build()
                        ,
                        AuthState.EXCEED_LIMIT,
                        AuthRule.builder()
                                .from(AuthState.PENDING)
                                .on(AuthEvent.REGISTER_VERIFY_CODE)
                                .to(AuthState.EXCEED_LIMIT)
                                .action(authAction::pendingOnRegisterVerifyCodeToExceedLimitAction)
                                .build()
                )
        );
        rules.put(AuthState.REGISTERED + ":" + AuthEvent.LOGIN_SEND_CODE,
                Map.of(
                        AuthState.PENDING_LOGIN,
                        AuthRule.builder()
                                .from(AuthState.REGISTERED)
                                .on(AuthEvent.LOGIN_SEND_CODE)
                                .to(AuthState.PENDING_LOGIN)
                                .action(authAction::registeredOnLoginSendCodeToPendingLoginAction)
                                .build()
                )
        );
        rules.put(AuthState.PENDING_LOGIN + ":" + AuthEvent.LOGIN_SEND_CODE,
                Map.of(
                        AuthState.PENDING_LOGIN,
                        AuthRule.builder()
                                .from(AuthState.PENDING_LOGIN)
                                .on(AuthEvent.LOGIN_SEND_CODE)
                                .to(AuthState.PENDING_LOGIN)
                                .action(authAction::pendingLoginOnLoginSendCodeToPendingLoginAction)
                                .build()
                        ,
                        AuthState.EXCEED_LIMIT,
                        AuthRule.builder()
                                .from(AuthState.PENDING_LOGIN)
                                .on(AuthEvent.LOGIN_SEND_CODE)
                                .to(AuthState.EXCEED_LIMIT)
                                .action(authAction::pendingLoginOnLoginSendCodeToExceedLimitAction)
                                .build()
                )
        );
        rules.put(AuthState.PENDING_LOGIN + ":" + AuthEvent.LOGIN_VERIFY_CODE,
                Map.of(
                        AuthState.LOGGED_IN,
                        AuthRule.builder()
                                .from(AuthState.PENDING_LOGIN)
                                .on(AuthEvent.REGISTER_VERIFY_CODE)
                                .to(AuthState.LOGGED_IN)
                                .action(authAction::pendingLoginOnLoginVerifyCodeToLoggedInAction)
                                .build()
                        ,
                        AuthState.EXCEED_LIMIT,
                        AuthRule.builder()
                                .from(AuthState.PENDING_LOGIN)
                                .on(AuthEvent.REGISTER_VERIFY_CODE)
                                .to(AuthState.EXCEED_LIMIT)
                                .action(authAction::pendingLoginOnLoginVerifyCodeToExceedLimitAction)
                                .build()
                )
        );
    }

    public AuthState sendEvent(@NotNull AuthEventContext authEventContext, @NotNull AuthEvent event) {
        Long id = authEventContext.getId();
        String code = authEventContext.getString("code") != null ? authEventContext.getString("code") : "";
        List<String>args = List.of(id.toString(),event.toString(),code);
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> res = (List<String>) jedis.fcall(
                    "process_event",
                    List.of(),
                    args
            );

            String eventKey = res.get(0);
            String newState = res.get(1);

            if(eventKey.equals("ERROR:INVALID_TRANSITION")) return AuthState.valueOf(newState);
            AuthRule authRule = rules.get(eventKey).get(AuthState.valueOf(newState));
            authRule.getAction().accept(authEventContext);
            return AuthState.valueOf(newState);
        } catch (Exception e) {
            log.error("状态机处理失败", e);
            throw new RuntimeException("Event processing failed", e);
        }
    }
}