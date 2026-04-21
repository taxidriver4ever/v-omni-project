package org.example.vomnisearch.service;

import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.vo.SearchMediaVo;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface DocumentVectorMediaService {
    void upsert(DocumentVectorMediaPo doc) throws IOException;
    void updateFields(String id, Map<String, Object> fields) throws IOException;
    List<SearchMediaVo> hybridSearch(String queryText, float[] queryVector, int page, int size) throws IOException;
    List<String> analyzeText(String text) throws IOException;
    List<DocumentVectorMediaPo> textSearch(String queryText, String author, int size) throws IOException;
    void deleteById(String id) throws IOException;
}
