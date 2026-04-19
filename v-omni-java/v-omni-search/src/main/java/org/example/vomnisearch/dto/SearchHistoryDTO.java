package org.example.vomnisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SearchHistoryDTO {
    private Long userId;
    private String keyword;
}
