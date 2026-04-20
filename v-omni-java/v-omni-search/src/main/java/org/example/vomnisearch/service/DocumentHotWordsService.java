package org.example.vomnisearch.service;

import org.example.vomnisearch.po.DocumentHotWordsPo;

import java.io.IOException;
import java.util.List;

public interface DocumentHotWordsService {
    /**
     * 前缀联想搜索
     * @param prefix 用户输入的关键词片段
     * @return 匹配的热词列表
     */
    List<DocumentHotWordsPo> getSuggestions(String prefix) throws IOException;
}
