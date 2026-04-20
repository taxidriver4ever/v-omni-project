package org.example.vomniinteract.controller;

import jakarta.annotation.Resource;
import org.example.vomniinteract.common.MyResult;
import org.example.vomniinteract.dto.CommentDto;
import org.example.vomniinteract.service.InteractService;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/interact")
public class InteractController {

    @Resource
    private InteractService interactService;

    @PostMapping("/like")
    public MyResult<String> doLike(String mediaId) {
        Long l = interactService.doLike(mediaId);
        if(l == null || l == 0) return MyResult.error(409,"重复点赞");
        return MyResult.success();
    }

    @DeleteMapping("/like")
    public MyResult<String> cancelLike(String mediaId) {
        Long l = interactService.cancelLike(mediaId);
        if(l == null || l == 0) return MyResult.error(409,"重复点赞");
        return MyResult.success();
    }

    @PostMapping("/collection")
    public MyResult<String> doCollection(String mediaId) {
        Long l = interactService.doCollection(mediaId);
        if(l == null || l == 0) return MyResult.error(409,"重复收藏");
        return MyResult.success();
    }

    @DeleteMapping("/collection")
    public MyResult<String> cancelCollection(String mediaId) {
        Long l = interactService.cancelCollection(mediaId);
        if(l == null || l == 0) return MyResult.error(409,"重复收藏");
        return MyResult.success();
    }

    @PostMapping("/comment")
    public MyResult<String> sendComment(@RequestBody CommentDto commentDto) {
        Long l = interactService.sendComment(commentDto);
        if(l == null || l == 0) return MyResult.error(429,"写入过于频繁");
        return MyResult.success();
    }

    @DeleteMapping("/comment")
    public MyResult<String> deleteComment(@RequestBody CommentDto commentDto) {
        Long l = interactService.deleteComment(commentDto);
        if(l == null || l == 0) return MyResult.error(400,"删除失败");
        return MyResult.success();
    }

    @PostMapping("/comment/like")
    public MyResult<String> doCommentLike(String mediaId) {
        Long l = interactService.doLike(mediaId);
        if(l == null || l == 0) return MyResult.error(409,"重复点赞");
        return MyResult.success();
    }

    @DeleteMapping("/comment/like")
    public MyResult<String> cancelCommentLike(String mediaId) {
        Long l = interactService.cancelLike(mediaId);
        if(l == null || l == 0) return MyResult.error(409,"重复点赞");
        return MyResult.success();
    }
}
