package org.example.vomniinteract.po;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Builder
@Data
public class UserCollectionPo {
    private Long id;
    private Long collectionUserId;
    private Long mediaId;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
