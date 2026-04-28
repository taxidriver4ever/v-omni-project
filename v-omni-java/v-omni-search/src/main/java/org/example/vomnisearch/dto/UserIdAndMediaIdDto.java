package org.example.vomnisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserIdAndMediaIdDto {
    private String userId;
    private String mediaId;
}
