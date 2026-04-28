package org.example.vomnisearch.service;


import java.util.List;

public interface VectorService {

    /**
     * 文本向量化：将搜索词或标题转为 512 维的特征向量
     */
    float[] getTextVector(String text) throws Exception;
}
