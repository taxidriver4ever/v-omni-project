package org.example.vomniinteract.service;

import org.example.vomniinteract.dto.DoCommentDto;
import org.example.vomniinteract.po.DocumentCommentPo;
import org.example.vomniinteract.vo.CommentVo;

import java.util.List;
import java.util.Map;

public interface DocumentCommentService {

    // 分页查询视频的一级评论（按点赞数或时间排序）
    List<CommentVo> findTopLevelComments(Long mediaId, int page, int size);

    // 查询某个主评下的所有回复
    List<CommentVo> findRepliesByRootComments(Long rootId, int page, int size);

    // 获取视频的总评论个数
    long getTotalCommentCount(Long mediaId);

    void bulkProcessComments(List<DocumentCommentPo> saveList, List<DoCommentDto> deleteList);

    void bulkUpdateCommentLikeCount(Map<Long, Integer> updateMap);
}
