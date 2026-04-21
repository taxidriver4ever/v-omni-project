package org.example.vomniinteract.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InteractionTaskDto {
    private Long mediaId;
    private Long userId;
    private String actionType; // "like" 或 "collect"
    private Boolean add;       // true 为增加，false 为删除
}
