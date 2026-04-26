package org.example.vomnisearch.service;

import java.io.IOException;

public interface StopWordService {
    void importFromDirectory() throws IOException;
    boolean isStopWord(String word);
}
