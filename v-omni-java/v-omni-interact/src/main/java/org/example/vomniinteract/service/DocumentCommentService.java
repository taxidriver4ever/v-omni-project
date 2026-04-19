package org.example.vomniinteract.service;

import org.example.vomniinteract.po.DocumentCollectionPo;
import org.example.vomniinteract.po.DocumentCommentPo;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface DocumentCommentService {
    /**
     * 保存或更新评论 (供 Kafka 消费者调用)
     */
    void upsert(DocumentCommentPo commentPo) throws IOException;

    /**
     * 滑动分页查询评论列表 (首页或回复列表)
     * @param mediaId 视频ID
     * @param rootId 根评论ID (查询一级评论传 "0")
     * @param lastSortValues 上一次查询最后一条的排序值 [create_time, id]
     * @return 包含数据和下一次游标的 Map
     */
    Map<String, Object> findCommentsByPage(String mediaId, String rootId, List<Object> lastSortValues) throws IOException;
}
