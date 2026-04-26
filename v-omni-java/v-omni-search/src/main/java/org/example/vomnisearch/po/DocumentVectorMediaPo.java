package org.example.vomnisearch.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVectorMediaPo {

    private String id;

    private String title;

    private String author;

    private String userId;

    private Boolean deleted;

    private List<Float> videoEmbedding; // 存放视频画面向量

    private List<Float> textEmbedding;  // 存放标题文字向量

    private String avatarPath;

    private String coverPath;

    private Integer likeCount;

    private Integer commentCount;

    private Integer collectionCount;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date updateTime;
}
