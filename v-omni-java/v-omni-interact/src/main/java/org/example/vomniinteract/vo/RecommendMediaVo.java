package org.example.vomniinteract.vo;

import lombok.Data;

@Data
public class RecommendMediaVo {
    private String title;
    private String userId;
    private String mediaId;
    private Integer likeCount;
    private Integer commentCount;
    private Integer collectionCount;
    private String coverUrl;
    private String mediaUrl;
    private String author;
    private String avatarUrl;
}
