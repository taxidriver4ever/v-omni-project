package org.example.vomniinteract.po;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class VideoCommentPo {
    private Long id;
    private Long mediaId;
    private Long parentUserId;
    private Long rootUserId;
    private Long replyUserId;
    private String content;
    private Integer deleted;
    private LocalDateTime createTime;
}
