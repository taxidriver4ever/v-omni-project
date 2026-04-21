package org.example.vomniinteract.service;

import org.example.vomniinteract.dto.InteractionTaskDto;
import org.example.vomniinteract.po.DocumentMediaInteractionPo;
import org.example.vomniinteract.vo.InteractionVo;
import java.util.List;

public interface DocumentMediaInteractionService {
    // --- 批量维护文档 (视频发布/下架) ---
    void bulkInsertMediaDocs(List<DocumentMediaInteractionPo> pos);
    void bulkDeleteMediaDocs(List<Long> mediaIds);

    // --- 批量互动操作 (供 Kafka 消费者调用) ---
    // 包含点赞、取消点赞、收藏、取消收藏
    void bulkProcessInteractions(List<InteractionTaskDto> tasks);

    // --- 分页查询 ---
    List<InteractionVo> findUserInteractionList(Long userId, String actionType, int page, int size);
}
