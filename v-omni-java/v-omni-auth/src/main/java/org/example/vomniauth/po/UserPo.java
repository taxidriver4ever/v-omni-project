package org.example.vomniauth.po;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.example.vomniauth.domain.statemachine.AuthState;

import java.time.LocalDateTime;

/**
 * User 持久化对象
 * 对应数据库 users 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 雪花算法生成的 ID
     * 注意：前端 JS 处理 Long 会丢失精度，建议在 ResultVO 中转为 String
     */
    private Long id;
    private String username;
    private String email;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public UserPo(Long id, String username, String email, AuthState authState) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.state = authState.toString();
    }
}

