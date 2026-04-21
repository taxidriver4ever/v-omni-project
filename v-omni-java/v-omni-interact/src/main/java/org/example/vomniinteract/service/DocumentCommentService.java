package org.example.vomniinteract.service;

import org.example.vomniinteract.po.DocumentCommentPo;

import java.util.List;
import java.util.Map;

public interface DocumentCommentService {
    // 发表评论
    void saveComment(DocumentCommentPo po);

    // 删除评论
    void deleteComment(Long commentId);

    // 点赞评论（原子加减）
    void updateCommentLikeCount(Long commentId, boolean isAdd);

    // 分页查询视频的一级评论（按点赞数或时间排序）
    List<DocumentCommentPo> findTopLevelComments(Long mediaId, int page, int size);

    // 查询某个主评下的所有回复
    List<DocumentCommentPo> findRepliesByRootId(Long rootId);

    // 获取视频的总评论个数
    long getTotalCommentCount(Long mediaId);

    void bulkProcessComments(List<DocumentCommentPo> saveList, List<Long> deleteList);

    void bulkUpdateCommentLikeCount(Map<Long, Integer> updateMap);
}
