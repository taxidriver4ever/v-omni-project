package org.example.vomniinteract.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Date;

@Data
@Builder
public class CommentPo {
    private Long id;
    private Long mediaId;
    private Long parentId;
    private Long rootId;
    private Long userId;
    private String content;
    private Integer deleted;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;
}
