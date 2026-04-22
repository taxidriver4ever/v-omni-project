package org.example.vomnimedia.service;

import java.util.List;

public interface VectorService {
    /**
     * 将一组图片转换为一个聚合后的视频特征向量
     * @param imageBytesList 内存中的图片列表
     * @return 512维特征向量
     */
    float[] getVector(List<byte[]> imageBytesList) throws Exception;

    /**
     * 标题文本向量化
     */
    float[] getTextVector(String text) throws Exception;
}
