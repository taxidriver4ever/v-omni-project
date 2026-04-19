package org.example.vomniinteract.service;

import org.example.vomniinteract.po.DocumentCollectionPo;

import java.io.IOException;

public interface DocumentCollectionService {
    void upsert(DocumentCollectionPo doc) throws IOException;
    void delete(String userId, String mediaId) throws IOException;
}
