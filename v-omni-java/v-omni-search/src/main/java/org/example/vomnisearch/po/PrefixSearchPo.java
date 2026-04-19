package org.example.vomnisearch.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrefixSearchPo {
    /**
     * 热词内容
     */
    private String word;

    /**
     * 热度分数（来自 Redis 算分）
     */
    private Double score;

    private boolean deleted;
}
