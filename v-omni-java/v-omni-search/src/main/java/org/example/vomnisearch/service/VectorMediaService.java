package org.example.vomnisearch.service;

import org.example.vomnisearch.po.DocumentVectorMediaPo;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface VectorMediaService {
    void upsert(DocumentVectorMediaPo doc) throws IOException;
    void updateFields(String id, Map<String, Object> fields) throws IOException;
    List<String> hybridSearchIds(java.lang.String queryText, float[] queryVector, java.lang.String author, int size) throws IOException;
    List<DocumentVectorMediaPo> hybridSearch(String queryText,
                                             float[] queryVector,
                                             String author,
                                             int size) throws IOException;
    List<String> analyzeText(String text) throws IOException;
    List<DocumentVectorMediaPo> textSearch(String queryText, String author, int size) throws IOException;
}
