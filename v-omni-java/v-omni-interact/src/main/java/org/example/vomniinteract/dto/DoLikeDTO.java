package org.example.vomniinteract.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
public class DoLikeDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String action;
    private String mediaId;
    private String userId;
}
