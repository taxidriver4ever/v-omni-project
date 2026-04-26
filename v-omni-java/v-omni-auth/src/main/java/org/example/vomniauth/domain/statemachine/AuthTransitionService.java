package org.example.vomniauth.domain.statemachine;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;


@Slf4j
@Component
public class AuthTransitionService {

    @Resource
    private AuthAction authAction;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final Map<String, Map<AuthState,AuthRule>> rules = new HashMap<>();

    @PostConstruct
    public void initRules() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("lua/send_event.lua")) {
            String lua = new String(Objects.requireNonNull(is).readAllBytes(), StandardCharsets.UTF_8);

            // 使用 RedisTemplate 执行 FUNCTION LOAD REPLACE
            String result = stringRedisTemplate.execute((RedisCallback<String>) connection -> {
                byte[] scriptBytes = lua.getBytes(StandardCharsets.UTF_8);
                byte[] response = (byte[]) connection.execute(
                        "FUNCTION",
                        "LOAD".getBytes(StandardCharsets.UTF_8),
                        "REPLACE".getBytes(StandardCharsets.UTF_8),
                        scriptBytes
                );
                if (response != null) {
                    log.info("lua脚本加载成功");
                    return new String(response, StandardCharsets.UTF_8);
                }
                return null;
            });
            log.info("Lua 函数库加载完成，SHA: {}", result);
        } catch (IOException e) {
            throw new RuntimeException("加载 Lua 脚本失败", e);
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
        List<String> args = List.of(id.toString(), event.toString(), code);

        String res = executeFcallToString(args);

        int lastColonIndex = res.lastIndexOf(':');
        String eventKey = res.substring(0, lastColonIndex);
        String newState = res.substring(lastColonIndex + 1);

        if (eventKey.equals("ERROR:INVALID_TRANSITION")) return AuthState.valueOf(newState);
        AuthRule authRule = rules.get(eventKey).get(AuthState.valueOf(newState));
        if(authRule == null) return AuthState.valueOf(newState);
        authRule.getAction().accept(authEventContext);
        return AuthState.valueOf(newState);
    }

    /**
     * 执行 Redis FCALL 命令，并将返回值强制转换为字符串。
     * 支持 Redis 返回 byte[]、List<byte[]>（取第一个元素）等情况。
     *
     * @param args 传递给函数的参数列表（不包含 numkeys）
     * @return Redis 返回的字符串（例如 "INITIAL:REGISTER_SEND_CODE:PENDING"）
     */
    private String executeFcallToString(List<String> args) {
        return stringRedisTemplate.execute((RedisCallback<String>) connection -> {
            // 构建参数：FCALL function_name numkeys [arg1 arg2 ...]
            List<byte[]> paramList = new ArrayList<>();
            paramList.add("process_event".getBytes(StandardCharsets.UTF_8));
            paramList.add("0".getBytes(StandardCharsets.UTF_8)); // numkeys = 0
            for (String arg : args) {
                paramList.add(arg.getBytes(StandardCharsets.UTF_8));
            }
            byte[][] paramArray = paramList.toArray(new byte[0][]);

            Object response = connection.execute("FCALL", paramArray);

            switch (response) {
                case null -> throw new RuntimeException("FCALL 返回 null");
                // 根据实际类型提取字符串
                case byte[] bytes -> {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                default -> throw new RuntimeException("FCALL 返回不支持的类型: " + response.getClass());
            }

        });
    }
}