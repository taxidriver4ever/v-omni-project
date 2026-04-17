package org.example.vomnimedia.service;

import org.example.vomnimedia.po.DocumentVectorMediaPo;

import java.io.IOException;
import java.util.Map;

public interface VectorMediaService {
    void upsert(DocumentVectorMediaPo doc) throws IOException;
    void updateFields(String id, Map<String, Object> fields) throws IOException;
    void update(DocumentVectorMediaPo doc) throws IOException;
}
