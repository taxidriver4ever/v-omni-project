package org.example.vomniinteract.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InteractionVo {
    private Long mediaId;
    private String coverUrl;
    private Integer likeCount;
    private Boolean liked;
}
