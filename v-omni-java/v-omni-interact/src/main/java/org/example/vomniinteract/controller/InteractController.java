package org.example.vomniinteract.controller;

import jakarta.annotation.Resource;
import org.example.vomniinteract.common.MyResult;
import org.example.vomniinteract.dto.CommentDto;
import org.example.vomniinteract.dto.DeleteCommentDto;
import org.example.vomniinteract.dto.MediaCommentDto;
import org.example.vomniinteract.service.DocumentCommentService;
import org.example.vomniinteract.service.DocumentMediaInteractionService;
import org.example.vomniinteract.service.InteractService;
import org.example.vomniinteract.util.SecurityUtils;
import org.example.vomniinteract.vo.CommentVo;
import org.example.vomniinteract.vo.InteractionVo;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/interact")
public class InteractController {

    @Resource
    private InteractService interactService;

    @Resource
    private DocumentMediaInteractionService documentMediaInteractionService;

    @Resource
    private DocumentCommentService documentCommentService;

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
        if(l == null || l == 0) return MyResult.error(400,"评论失败");
        return MyResult.success();
    }

    @DeleteMapping("/comment")
    public MyResult<String> deleteComment(@RequestBody CommentDto commentDto) {
        Long l = interactService.deleteComment(commentDto);
        if(l == null || l == 0) return MyResult.error(400,"删除失败");
        return MyResult.success();
    }

    @PostMapping("/comment/like")
    public MyResult<String> doCommentLike(String commentId) {
        Long l = interactService.doCommentLike(commentId);
        if(l == null || l == 0) return MyResult.error(409,"重复点赞");
        return MyResult.success();
    }

    @DeleteMapping("/comment/like")
    public MyResult<String> cancelCommentLike(String commentId) {
        Long l = interactService.cancelCommentLike(commentId);
        if(l == null || l == 0) return MyResult.error(409,"重复点赞");
        return MyResult.success();
    }

    @GetMapping("/user/like")
    public MyResult<List<InteractionVo>> personLike(@RequestParam(defaultValue = "1") Integer page) {
        List<InteractionVo> likeUserList = interactService.selectUserLike(page);
        if (likeUserList == null || likeUserList.isEmpty()) return MyResult.error(404, "查询失败");
        return MyResult.success(likeUserList);
    }

    @GetMapping("/user/collection")
    public MyResult<List<InteractionVo>> personCollection(Integer page) {
        List<InteractionVo> collectUserList = interactService.selectUserCollection(page);
        if (collectUserList == null || collectUserList.isEmpty()) return MyResult.error(404, "查询失败");
        return MyResult.success(collectUserList);
    }

    @PostMapping("/root/comment")
    public MyResult<List<CommentVo>> rootComment(@RequestBody MediaCommentDto mediaCommentDto) {
        String rootId = mediaCommentDto.getRootId();
        String mediaId = mediaCommentDto.getMediaId();
        Integer page = mediaCommentDto.getPage();

        if("0".equals(rootId)) {
            List<CommentVo> topLevelComments = documentCommentService.findTopLevelComments(Long.parseLong(mediaId), page, 10);
            return MyResult.success(topLevelComments != null ? topLevelComments : Collections.emptyList());
        }
        return MyResult.error(400, "非法根评论ID");
    }

    @PostMapping("/rely/comment")
    public MyResult<List<CommentVo>> relyComment(@RequestBody MediaCommentDto mediaCommentDto) {
        String rootId = mediaCommentDto.getRootId();
        Integer page = mediaCommentDto.getPage();

        if(rootId != null && !"0".equals(rootId)) {
            List<CommentVo> replies = documentCommentService.findRepliesByRootComments(Long.parseLong(rootId), page, 10);
            return MyResult.success(replies != null ? replies : Collections.emptyList());
        }
        return MyResult.error(400, "参数错误");
    }


}
