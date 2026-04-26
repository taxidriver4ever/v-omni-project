package org.example.vomnimedia.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@AllArgsConstructor
@Data
public class PreparePublishToMediaDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String userId;
    private String title;
}
