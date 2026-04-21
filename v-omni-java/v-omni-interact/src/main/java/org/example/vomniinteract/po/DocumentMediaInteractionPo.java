package org.example.vomniinteract.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentMediaInteractionPo {
    private Long mediaId;
    private String coverPath;
    private Integer likeCount;
    // 幕后功臣：ES 筛选用的 ID 数组
    private List<Long> likeUserIds;
    private List<Long> collectUserIds;
}
