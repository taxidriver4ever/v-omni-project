package org.example.vomnimedia.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.*;
import org.example.vomnimedia.service.FfmpegService;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.VectorService;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class FfmpegServiceImpl implements FfmpegService {

    @Resource
    private MinioService minioService;

    @Resource
    private VectorService vectorService;

    @Override
    public void compressAndConvertToHLSAndUploadToMinio(String id, String inputVideoUrl, String bucketName, int crf, String maxBitrate) throws Exception {
        Path tempDir = Files.createTempDirectory("hls_temp_" + id);
        String tempDirPath = tempDir.toString();

        try {
            String localM3u8Path = compressAndConvertToHLS(inputVideoUrl, tempDirPath, crf, maxBitrate);
            String remotePrefix = "hls/" + id + "/";
            minioService.uploadDirectory(new File(tempDirPath), bucketName, remotePrefix);
            log.info("✅ HLS 转码并上传完成，ID: {}", id);
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    /**
     * 极速抽帧：使用 setTimestamp 进行秒级跳跃，配合 GPU 解码
     */
    @Override
    public float[] extractVideoVector(String id, String inputVideoUrl) throws Exception {
        List<byte[]> imageBytesList = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            // 开启 GPU 解码加速读取
            grabber.setVideoOption("hwaccel", "cuda");
            grabber.start();

            long totalDurationUs = grabber.getLengthInTime();
            double durationSeconds = totalDurationUs / 1_000_000.0;

            // 抽帧策略：短视频每4秒1帧，长视频固定30帧
            int targetCount = (durationSeconds < 120) ? (int) Math.max(1, durationSeconds / 4) : 30;
            long interval = totalDurationUs / targetCount;

            log.info("🎬 开始抽帧: {}，时长 {}s，预取 {} 张", id, String.format("%.2f", durationSeconds), targetCount);

            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                for (int i = 0; i < targetCount; i++) {
                    // 核心加速点：跳跃定位
                    grabber.setTimestamp(i * interval);
                    Frame frame = grabber.grabImage();
                    if (frame != null) {
                        BufferedImage image = converter.getBufferedImage(frame);
                        if (image != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "jpg", baos);
                            imageBytesList.add(baos.toByteArray());
                        }
                    }
                }
            }

            if (imageBytesList.isEmpty()) throw new RuntimeException("未能提取到有效视频帧");

            log.info("🚀 抽帧完成，进入向量化阶段...");
            return vectorService.getVector(imageBytesList);
        }
    }

    /**
     * 硬件加速转码：修复了 profile 导致的 avcodec_open2 错误
     */
    private String compressAndConvertToHLS(String inputVideoUrl, String outputDir, int crf, String maxBitrate) throws Exception {
        File dir = new File(outputDir);
        if (!dir.exists()) Files.createDirectories(dir.toPath());
        String m3u8Path = new File(dir, "master.m3u8").getAbsolutePath();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            // GPU 解码加速
            grabber.setVideoOption("hwaccel", "cuda");
            grabber.start();

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(m3u8Path, grabber.getImageWidth(), grabber.getImageHeight(), 2)) {
                recorder.setFormat("hls");
                recorder.setFrameRate(grabber.getFrameRate());

                // 编码加速配置
                recorder.setVideoCodecName("h264_nvenc");
                recorder.setAudioCodecName("aac");

                // --- 修复点：移除报错的 profile 设置，改用 preset 和 cq 控制质量 ---
                recorder.setVideoOption("preset", "p4"); // p1-p7，p4为性能质量平衡点
                recorder.setVideoOption("rc", "vbr");    // 动态码率
                recorder.setVideoOption("cq", String.valueOf(crf));
                recorder.setVideoBitrate(3_000_000);
                recorder.setVideoOption("maxrate", maxBitrate);
                recorder.setVideoOption("bufsize", "6000k");

                // HLS 特定配置
                recorder.setOption("hls_time", "10");
                recorder.setOption("hls_list_size", "0");
                recorder.setOption("hls_flags", "split_by_time");
                recorder.setOption("hls_segment_filename", dir.getAbsolutePath() + "/segment_%05d.ts");

                recorder.start();
                log.info("🔥 4060 GPU 加速转码中...");

                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    recorder.record(frame);
                }
                recorder.stop();
                return m3u8Path;
            }
        }
    }

    @Override
    public String extractFinalCover(String customId, String inputVideoUrl) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            grabber.setVideoOption("hwaccel", "cuda");
            grabber.start();

            // 跳过开头，取第1秒的画面
            grabber.setTimestamp(1_000_000L);

            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    grabber.setTimestamp(0);
                    frame = grabber.grabImage();
                }

                if (frame != null) {
                    BufferedImage image = converter.getBufferedImage(frame);
                    String objectName = customId + ".jpg";
                    minioService.uploadImage(image, "final-cover", objectName);
                    return objectName;
                }
            }
        }
        throw new RuntimeException("封面提取失败");
    }

    private void deleteDirectory(@NotNull File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) deleteDirectory(child);
        }
        dir.delete();
    }
}