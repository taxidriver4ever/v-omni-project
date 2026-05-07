package org.example.vomnimedia.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AvatarAndAuthorDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String avatarPath;
    private String author;

}
