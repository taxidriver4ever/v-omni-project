package org.example.vomniinteract.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentCommentPo {
    @JsonProperty("comment_id")
    private Long commentId;

    @JsonProperty("media_id")
    private Long mediaId;

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("user_avatar")
    private String userAvatar;

    @JsonProperty("content")
    private String content;

    @JsonProperty("like_count")
    private Integer likeCount;

    @JsonProperty("root_id")
    private Long rootId;       // 0代表一级评论

    @JsonProperty("parent_id")
    private Long parentId;     // 被回复的评论ID

    @JsonProperty("create_time")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;
}

