package org.example.vomniinteract.service;

import org.example.vomniinteract.dto.CommentDto;
import org.example.vomniinteract.vo.InteractionVo;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface InteractService {
    Long doLike(String mediaId);
    Long cancelLike(String mediaId);
    Long doCollection(String mediaId);
    Long cancelCollection(String mediaId);
    Long sendComment(CommentDto commentDto);
    Long deleteComment(CommentDto commentDto);
    Long doCommentLike(String commentId);
    Long cancelCommentLike(String commentId);
    List<InteractionVo> selectUserLike(Integer page);
    List<InteractionVo> selectUserCollection(Integer page);
}
