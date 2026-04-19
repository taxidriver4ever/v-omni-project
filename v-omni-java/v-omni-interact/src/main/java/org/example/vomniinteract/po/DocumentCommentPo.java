package org.example.vomniinteract.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 评论索引 PO 类
 * 对应 ES 索引: v_omni_comment
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentCommentPo {

    /**
     * 评论唯一 ID (建议对应数据库雪花 ID)
     */
    private String id;

    /**
     * 发布者 ID
     */
    @JsonProperty("user_id")
    private String userId;

    /**
     * 所属视频 ID
     */
    @JsonProperty("media_id")
    private String mediaId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 父评论 ID (一级评论为 "0")
     */
    @JsonProperty("parent_id")
    private String parentId;

    /**
     * 根评论 ID (方便聚合整个讨论串)
     */
    @JsonProperty("root_id")
    private String rootId;

    /**
     * 点赞数 (用于 ES search_after 热度排序)
     */
    @JsonProperty("like_count")
    private Integer likeCount;

    /**
     * 回复总数
     */
    @JsonProperty("reply_count")
    private Integer replyCount;

    /**
     * 逻辑删除标识
     */
    private Boolean deleted;

    /**
     * 评论创建时间 (用于 ES search_after 时间排序)
     */
    @JsonProperty("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;
}

