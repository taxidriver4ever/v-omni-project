package org.example.vomnimedia.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class PreparePublishToMediaDto {
    private String id;
    private String userId;
    private String title;
}
