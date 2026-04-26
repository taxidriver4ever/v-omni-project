package org.example.vomnisearch.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@Builder
public class SearchMediaVo {
    private String mediaId;
    private String coverUrl;
    private String title;
    private String author;
    private LocalDateTime publishedDate;
    private String likeCount;
    private String avatarUrl;
    private String mediaUrl;
}
