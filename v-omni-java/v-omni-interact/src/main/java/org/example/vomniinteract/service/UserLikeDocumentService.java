package org.example.vomniinteract.service;

import org.example.vomniinteract.po.UserLikeDocumentPo;

import java.io.IOException;

public interface UserLikeDocumentService {
    /**
     * 点赞：全量写入或覆盖
     */
    void upsert(UserLikeDocumentPo doc) throws IOException;

    /**
     * 取消点赞：逻辑删除
     * @param userId 用户ID
     * @param mediaId 视频ID
     */
    void delete(String userId, String mediaId) throws IOException;
}
