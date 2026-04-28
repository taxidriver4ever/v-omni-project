package org.example.vomnisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Range;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchMediaRequestDto {

    private String queryText;

    private Integer page;

}
