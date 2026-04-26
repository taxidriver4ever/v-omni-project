package org.example.vomniinteract.dto;

import lombok.Data;

@Data
public class DeleteCommentDto {
    private String commentId;
    private String rootId;
}
