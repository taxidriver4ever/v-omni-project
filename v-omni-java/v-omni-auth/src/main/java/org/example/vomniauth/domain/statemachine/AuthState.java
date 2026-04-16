package org.example.vomniauth.domain.statemachine;

public enum AuthState {

    INITIAL,        // 初始化
    PENDING,        // 发送验证码
    VERIFIED,       // 注册验证码已确认
    REGISTERED,     // 注册完成
    PENDING_LOGIN,  // 登录发送验证码
    LOGGED_IN,      // 登录成功
    LOGGED_OUT,     // 登出
    BLOCKED,        // 封锁账号
    EXCEED_LIMIT,   // 尝试次数超出限制
    ERROR           // 通用错误

}
