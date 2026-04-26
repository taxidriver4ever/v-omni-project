package org.example.vomnimedia.vo;

import lombok.Builder;
import lombok.Data;
import org.example.vomnimedia.domain.statemachine.MediaState;

@Data
@Builder
public class PreSignResponseVo {
    private Long mediaId;
    private String preSignUrl;
    private MediaState mediaState;
}
