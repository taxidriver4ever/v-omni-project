package org.example.vomnimedia.service;

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


    /**
     * 每隔 intervalSeconds 秒抽取一帧，直接上传到 MinIO 指定桶
     * @param id 用户id可以建立文件夹
     * @param inputVideoUrl 视频 URL（可以是本地文件路径或 MinIO 预签名 URL）
     * @param intervalSeconds 抽帧间隔（秒）
     * @param bucketName MinIO 桶名（如 tmp-extraction-image）
     * @return 上传的文件对象名列表
     */
    List<String> extractFramesEveryNSeconds(String id, String inputVideoUrl, int intervalSeconds, String bucketName) throws Exception;
}