package org.example.vomnisearch.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVectorMediaPo {

    private String id;

    private String title;

    private String author;
    /**
     * 向量字段
     */
    private List<Float> embedding;
}
