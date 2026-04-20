package org.example.vomniinteract.service;

import org.example.vomniinteract.dto.CommentDto;

public interface InteractService {
    Long doLike(String mediaId);
    Long cancelLike(String mediaId);
    Long doCollection(String mediaId);
    Long cancelCollection(String mediaId);
    Long sendComment(CommentDto commentDto);
    Long deleteComment(CommentDto commentDto);
    Long doCommentLike(String mediaId);
    Long cancelCommentLike(String mediaId);
}
