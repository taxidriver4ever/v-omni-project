package org.example.vomniinteract.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentMediaInteractionPo {

    @JsonProperty("media_id")
    private Long mediaId;

    @JsonProperty("cover_path")
    private String coverPath;

    @JsonProperty("like_count")
    private Integer likeCount;
    // 幕后功臣：ES 筛选用的 ID 数组

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("behavior")
    private String behavior;

    @JsonProperty("create_time")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;
}
