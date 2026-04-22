package org.example.vomnimedia.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.*;
import org.example.vomnimedia.service.FfmpegService;
import org.example.vomnimedia.service.MediaService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.VectorService;
import org.example.vomnimedia.util.SnowflakeIdWorker;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FfmpegServiceImpl implements FfmpegService {

    @Resource
    private MinioService minioService;

    @Resource
    private VectorService vectorService;

    @Override
    public void compressAndConvertToHLSAndUploadToMinio(String id,String inputVideoUrl, String bucketName, int crf, String maxBitrate) throws Exception {
        // 1. 创建临时目录
        Path tempDir = Files.createTempDirectory("hls_temp_");
        String tempDirPath = tempDir.toString();

        // 2. 调用原有方法转码到临时目录
        String localM3u8Path = compressAndConvertToHLS(inputVideoUrl, tempDirPath, crf, maxBitrate);
        File localM3u8File = new File(localM3u8Path);
        if (!localM3u8File.exists()) {
            throw new RuntimeException("HLS 转码失败，未生成 m3u8 文件");
        }

        // 3. 生成远程对象名前缀（如 "hls/uuid/"），确保唯一性
        String remotePrefix = "hls/" + id + "/";
        minioService.uploadDirectory(tempDir.toFile(), bucketName, remotePrefix);

        // 5. 清理本地临时目录
        deleteDirectory(tempDir.toFile());

        log.info("HLS 已上传至 MinIO，桶: {}", bucketName);
    }

    /**
     * 抽帧并返回视频的特征向量
     * @return 512维的 float 数组
     */
    @Override
    public float[] extractVideoVector(String id, String inputVideoUrl) throws Exception {
        // 1. 准备一个内存列表，用来存放抽出来的图片字节流
        List<byte[]> imageBytesList = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            grabber.start();

            double frameRate = grabber.getFrameRate();
            double durationSeconds = grabber.getLengthInTime() / 1_000_000.0;
            int totalFrames = grabber.getLengthInFrames(); // 直接获取总帧数

            int frameCount = 0;
            int savedCount = 0;
            int step;

            // 抽帧策略逻辑保持不变
            if (durationSeconds < 120) {
                step = (int) Math.round(frameRate * 4);
            } else {
                step = Math.max(1, totalFrames / 30);
            }

            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                Frame frame;
                while ((frame = grabber.grabImage()) != null) {
                    // 如果已经抽够了 30 帧且是大视频，提前结束
                    if (durationSeconds >= 120 && savedCount >= 30) {
                        break;
                    }

                    if (frameCount % step == 0) {
                        BufferedImage image = converter.getBufferedImage(frame);
                        if (image != null) {
                            // 将图片转为 byte[] 存入内存，不写磁盘，不传 MinIO
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "jpg", baos);
                            imageBytesList.add(baos.toByteArray());
                            savedCount++;
                        }
                    }
                    frameCount++;
                }
            }

            if (imageBytesList.isEmpty()) {
                throw new RuntimeException("未能从视频中抽取到任何有效帧");
            }

            log.info("🎬 视频 {} 抽帧完成，共 {} 帧，开始进入 ONNX 模型计算向量...", id, savedCount);

            // 2. 调用 ONNX 服务，将多张图片转化为一个 512 维向量
            float[] videoVector = vectorService.getVector(imageBytesList);

            log.info("✅ 向量计算成功，维度: {}", videoVector.length);
            return videoVector;
        }
    }

    private void deleteDirectory(@NotNull File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        boolean deleted = dir.delete();
        if (!deleted) {
            log.warn("删除文件/目录失败: {}", dir.getAbsolutePath());
        }
    }

    private String compressAndConvertToHLS(String inputVideoUrl, String outputDir, int crf, String maxBitrate)
            throws Exception {

        File dir = new File(outputDir);
        if (!dir.exists()) Files.createDirectories(dir.toPath());

        String m3u8Path = new File(dir, "master.m3u8").getAbsolutePath();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            grabber.start();

            // 判断是否可以使用流拷贝（不需要重新编码）
            boolean isCodecCompatible = false;
            String videoCodec = grabber.getVideoCodecName();   // 可能返回 "h264"
            String audioCodec = grabber.getAudioCodecName();   // 可能返回 "aac"
            if ("h264".equalsIgnoreCase(videoCodec) && "aac".equalsIgnoreCase(audioCodec)) {
                isCodecCompatible = true;
            }
            // 如果使用默认压缩参数（未要求降低码率/质量），则优先尝试拷贝
            boolean useCopy = isCodecCompatible && crf == 24 && "3000k".equals(maxBitrate);

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(m3u8Path, 2)) {
                recorder.setFormat("hls");
                recorder.setFrameRate(grabber.getFrameRate());
                recorder.setImageWidth(grabber.getImageWidth());
                recorder.setImageHeight(grabber.getImageHeight());

                if (useCopy) {
                    // 直接拷贝流，不重新编码
                    recorder.setVideoCodecName("copy");
                    recorder.setAudioCodecName("copy");
                    log.info("使用流拷贝模式，速度最快，体积不变");
                } else {
                    // 重新编码：采用 5 秒 GOP（每5秒一个关键帧）和 10 秒切片，降低 I 帧密度
                    recorder.setVideoCodecName("libx264");
                    recorder.setAudioCodecName("aac");
                    recorder.setVideoBitrate(3_000_000);
                    recorder.setVideoOption("crf", String.valueOf(crf));
                    recorder.setVideoOption("maxrate", maxBitrate);
                    recorder.setVideoOption("bufsize", "6000k");

                    double frameRate = grabber.getFrameRate();
                    int gopSize = (int) Math.round(frameRate * 5);   // 5秒一个关键帧
                    recorder.setVideoOption("g", String.valueOf(gopSize));
                    recorder.setVideoOption("keyint_min", String.valueOf(gopSize));
                    // 强制每5秒插入关键帧（如果需要）
                    recorder.setVideoOption("force_key_frames", "expr:gte(t,n_forced*5)");
                    log.info("重新编码模式，GOP={} 帧（5秒），切片时间10秒", gopSize);
                }

                // HLS 通用参数
                recorder.setOption("hls_time", "10");              // 10秒切片（减少切片数量）
                recorder.setOption("hls_list_size", "0");
                recorder.setOption("hls_segment_filename", dir.getAbsolutePath() + "/segment_%05d.ts");
                recorder.setOption("hls_flags", "split_by_time");  // 允许非关键帧切片（对拷贝模式尤其重要）

                recorder.start();

                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    recorder.record(frame);
                }

                log.info("✅ HLS 转换完成: {}", m3u8Path);
                return m3u8Path;
            }
        }
    }


    @Override
    public String extractFinalCover(String customId, String inputVideoUrl) throws Exception {
        log.info("开始抽取视频首帧作为最终封面，ID: {}", customId);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            grabber.start();

            // 1. 定位到第1秒
            long targetTime = 1_000_000L;
            if (grabber.getLengthInTime() < targetTime) {
                targetTime = 0;
            }
            grabber.setTimestamp(targetTime);

            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    grabber.setTimestamp(0);
                    frame = grabber.grabImage();
                }

                if (frame != null) {
                    BufferedImage image = converter.getBufferedImage(frame);
                    // 2. 使用自定义 ID 命名
                    String objectName = customId + ".jpg";
                    String bucketName = "final-cover";

                    // 3. 上传到 MinIO
                    minioService.uploadImage(image, bucketName, objectName);

                    log.info("✅ 最终封面上传完成: {}", objectName);
                    return objectName;
                }
            }
        }
        throw new RuntimeException("视频帧抓取失败");
    }


}