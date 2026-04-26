package org.example.vomnisearch.dto;

import lombok.Data;
import org.jetbrains.annotations.Range;

@Data
public class SearchMediaRequestDto {

    private String queryText;

    private Integer page;

}
