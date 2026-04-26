package org.example.vomnimedia.service;

import org.example.vomnimedia.po.DocumentVectorMediaPo;

import java.io.IOException;
import java.util.Map;

public interface DocumentVectorMediaService {
    void upsert(DocumentVectorMediaPo doc) throws IOException;
    void updateFields(String id, Map<String, Object> fields) throws IOException;
    void deleteById(String id) throws IOException;
}
