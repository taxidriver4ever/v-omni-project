package org.example.vomnisearch.service;

import java.io.IOException;

public interface DocumentUserViewedService {
    void saveUserViewHistory(String userId, String mediaId) throws IOException;
}
