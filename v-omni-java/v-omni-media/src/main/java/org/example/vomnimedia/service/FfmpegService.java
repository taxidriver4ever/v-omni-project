package org.example.vomnimedia.service;

import org.bytedeco.javacv.FrameGrabber;

import java.io.IOException;
import java.util.List;

/**
 * 视频处理服务接口（基于 JavaCV）
 */
public interface FfmpegService {

    /**
     * 视频压缩 + 转为 HLS（m3u8 + ts 文件）
     */
    void compressAndConvertToHLSAndUploadToMinio(String id,String inputVideoUrl, String bucketName, int crf, String maxBitrate)
            throws Exception;

    default void compressAndConvertToHLSAndUploadToMinio(String id,String inputVideoUrl, String bucketName) throws Exception {
        compressAndConvertToHLSAndUploadToMinio(id,inputVideoUrl, bucketName, 24, "3000k");
    }

    String extractFinalCover(String customId, String inputVideoUrl) throws Exception;

    float[] extractVideoVector(String id, String inputVideoUrl) throws Exception;
}