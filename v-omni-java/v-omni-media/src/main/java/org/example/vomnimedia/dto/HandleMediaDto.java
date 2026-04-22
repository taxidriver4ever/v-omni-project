package org.example.vomnimedia.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HandleMediaDto {
    private String title;
    private String mediaId;
    private String userId;
    private String downloadUrl;
}
