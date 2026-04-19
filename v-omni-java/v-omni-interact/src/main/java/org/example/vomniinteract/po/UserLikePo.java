package org.example.vomniinteract.po;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Builder
@Data
public class UserLikePo {
    private Long id;
    private Long likeUserId;
    private Long mediaId;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
