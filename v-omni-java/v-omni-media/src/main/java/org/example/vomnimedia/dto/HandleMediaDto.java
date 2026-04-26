package org.example.vomnimedia.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HandleMediaDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String title;
    private String mediaId;
    private String userId;
    private String downloadUrl;
}
