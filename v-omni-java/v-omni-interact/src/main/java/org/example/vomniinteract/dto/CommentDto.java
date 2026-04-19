package org.example.vomniinteract.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class CommentDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String content;
    private String rootId;
    private String parentId;
    private String mediaId;
}
