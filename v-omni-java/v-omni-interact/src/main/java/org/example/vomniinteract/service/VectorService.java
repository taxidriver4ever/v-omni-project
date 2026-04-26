package org.example.vomniinteract.service;

import java.util.List;

public interface VectorService {
    float[] fuseUserInterest(float[] oldInterest, List<float[]> behaviorVectors, List<String> actions);
}
