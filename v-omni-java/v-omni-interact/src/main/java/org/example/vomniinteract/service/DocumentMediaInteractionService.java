package org.example.vomniinteract.service;

import org.example.vomniinteract.dto.InteractionTaskDto;
import org.example.vomniinteract.po.DocumentMediaInteractionPo;
import org.example.vomniinteract.po.DocumentVectorMediaPo;
import org.example.vomniinteract.vo.InteractionVo;
import java.util.List;
import java.util.Map;

public interface DocumentMediaInteractionService {

    void bulkSyncInteractions(List<DocumentMediaInteractionPo> addList, List<String> deleteIds);
    List<DocumentMediaInteractionPo> findUserInteractionListFromEs(
            Long userId,
            String behavior,
            Integer page,
            Integer size
    );
    List<DocumentVectorMediaPo> findMediaListFromEs(List<String> mediaIds);
    void asyncRefreshTop30Cache(Long userId, String zsetKey, String behavior);
    void asyncUpdateMediaHash(DocumentVectorMediaPo po);
}
