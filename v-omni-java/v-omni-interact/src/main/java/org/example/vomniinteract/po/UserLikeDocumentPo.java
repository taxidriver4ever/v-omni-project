package org.example.vomniinteract.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Builder
@Data
public class UserLikeDocumentPo {
    private String id;

    @JsonProperty("user_id") // 映射到 ES 的 user_id
    private String userId;

    @JsonProperty("media_id") // 映射到 ES 的 media_id
    private String mediaId;

    private String title;

    private Boolean deleted;

    @JsonProperty("create_time") // 映射到 ES 的 create_time
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
