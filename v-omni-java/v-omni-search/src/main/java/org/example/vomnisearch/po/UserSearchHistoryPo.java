package org.example.vomnisearch.po;

import lombok.Data;

import java.io.Serial;
import java.time.LocalDateTime;

@Data
public class UserSearchHistoryPo implements java.io.Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String keyword;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public UserSearchHistoryPo(Long id, Long userId, String keyword) {
        this.id = id;
        this.userId = userId;
        this.keyword = keyword;
    }

}
