package org.example.vomnisearch.service;

import org.example.vomnisearch.po.PrefixSearchPo;

import java.io.IOException;
import java.util.List;

public interface PrefixSearchService {
    /**
     * 前缀联想搜索
     * @param prefix 用户输入的关键词片段
     * @return 匹配的热词列表
     */
    List<PrefixSearchPo> getSuggestions(String prefix) throws IOException;
}
