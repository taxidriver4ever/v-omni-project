package org.example.vomniauth.strategy;

@FunctionalInterface
public interface IdentityResolutionStrategy {
    /**
     * 根据邮箱解析用户ID（可能创建新ID）
     * @param email 邮箱
     * @return 用户ID，若已存在注册用户则返回0
     */
    Long resolve(String email);
}
