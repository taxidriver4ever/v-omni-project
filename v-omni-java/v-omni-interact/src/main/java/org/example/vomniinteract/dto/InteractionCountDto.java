package org.example.vomniinteract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InteractionCountDto {
    private String mediaId;
    private String type;   // LIKE, COMMENT, COLLECT
    private Integer change; // +1 或 -1
}