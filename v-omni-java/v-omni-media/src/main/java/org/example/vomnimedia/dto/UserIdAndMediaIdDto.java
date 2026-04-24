package org.example.vomnimedia.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserIdAndMediaIdDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private String mediaId;
}
