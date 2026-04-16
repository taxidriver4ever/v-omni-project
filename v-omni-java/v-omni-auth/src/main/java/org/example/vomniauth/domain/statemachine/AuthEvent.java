package org.example.vomniauth.domain.statemachine;

public enum AuthEvent {

    // ==================== 注册流程 ====================
    REGISTER_SEND_CODE,           // 注册发送验证码
    REGISTER_VERIFY_CODE,         // 注册验证验证码
    REGISTER_EXCEED_ATTEMPTS,     // 注册尝试次数超限

    // ==================== 登录流程 ====================
    LOGIN_SEND_CODE,              // 登录发送验证码
    LOGIN_VERIFY_CODE,            // 登录验证码验证成功
    LOGIN_EXCEED_ATTEMPTS,        // 登录尝试次数超限
}
