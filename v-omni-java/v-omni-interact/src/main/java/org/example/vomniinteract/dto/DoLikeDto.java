package org.example.vomniinteract.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor  // 👈 必须有这个！
@ToString
public class DoLikeDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String action;
    private String mediaId;
    private String userId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;
}
