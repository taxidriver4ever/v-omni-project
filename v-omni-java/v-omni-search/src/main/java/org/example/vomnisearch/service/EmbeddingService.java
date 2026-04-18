package org.example.vomnisearch.service;

import java.util.List;

public interface EmbeddingService {
    float[][] getVector(String text);
    float[][] getVectors(List<String> texts);
}
