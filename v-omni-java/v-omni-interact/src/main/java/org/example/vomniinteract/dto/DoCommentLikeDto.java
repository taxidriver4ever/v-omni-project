package org.example.vomniinteract.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@AllArgsConstructor
@Data
@Builder
public class DoCommentLikeDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private String commentId;
    private String action;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;
}
