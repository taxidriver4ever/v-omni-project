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
import java.io.*;
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
        try {
            // 只保留转码开始日志
            log.info("🎞️ 开始 HLS 转码: ID={}", id);
            compressAndConvertToHLS(inputVideoUrl, tempDir.toString(), crf, maxBitrate);

            // 上传成功后打个点
            minioService.uploadDirectory(tempDir.toFile(), bucketName, "hls/" + id + "/");
            log.info("✅ 转码并上传完成: ID={}", id);
        } catch (Exception e) {
            log.error("❌ 转码任务失败 [ID={}]: {}", id, e.getMessage());
            throw e;
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private void compressAndConvertToHLS(String inputVideoUrl, String outputDir, int crf, String maxBitrate) throws Exception {
        File dir = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();

        String m3u8Path = new File(dir, "master.m3u8").getAbsolutePath();
        String segmentPattern = new File(dir, "segment_%05d.ts").getAbsolutePath();

        List<String> command = List.of(
                "ffmpeg", "-y", "-hwaccel", "cuda", "-i", inputVideoUrl,
                "-c:v", "h264_nvenc", "-preset", "p2", "-cq", String.valueOf(crf),
                "-maxrate", maxBitrate, "-bufsize", "6000k", "-c:a", "aac",
                "-f", "hls", "-hls_time", "10", "-hls_list_size", "0",
                "-hls_segment_filename", segmentPattern, m3u8Path
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder errorLog = new StringBuilder();
        // 使用线程异步读取，避免阻塞主循环
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errorLog.append(line).append("\n");
                }
            } catch (Exception ignored) {}
        });
        outputReader.start();

        // 🛡️ 防御性等待：给转码设置一个物理上限（例如：视频长度的 3 倍，或硬性 10 分钟）
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);

        if (!finished) {
            process.destroyForcibly(); // 强制杀死卡死的 FFmpeg 进程
            log.error("🚨 转码任务超时强制终止: ID={}, URL={}", outputDir, inputVideoUrl);
            throw new RuntimeException("FFmpeg 转码超时");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            // 如果是 CUDA 报错，可以考虑在这里增加一个“回退到 CPU”的重试逻辑
            if (errorLog.toString().contains("cuda") || errorLog.toString().contains("nvenc")) {
                log.error("🔥 GPU 编码报错，请检查驱动或显存: \n{}", errorLog);
            }
            log.error("❌ FFmpeg 退出异常 [Code={}]: \n{}", exitCode, errorLog);
            throw new RuntimeException("FFmpeg 执行失败");
        }
    }

    @Override
    public String extractFinalCover(String customId, String inputVideoUrl) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputVideoUrl)) {
            grabber.setOption("timeout", "5000000"); // 5秒超时
            grabber.setVideoOption("hwaccel", "cuda");

            try {
                grabber.start();
            } catch (Exception e) {
                log.error("❌ 封面提取启动失败 [ID={}]: {}", customId, e.getMessage());
                throw new RuntimeException("无法读取视频流进行封面提取");
            }

            long duration = grabber.getLengthInTime();
            // 尝试提取的时间点：1秒处、1/4处、1/2处
            long[] testPoints = {1_000_000L, duration / 4, duration / 2, 0L};

            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                for (long point : testPoints) {
                    if (point > duration && point != 0) continue;

                    try {
                        grabber.setTimestamp(point);
                        Frame frame = grabber.grabImage();

                        if (frame != null) {
                            BufferedImage image = converter.getBufferedImage(frame);
                            if (image != null) {
                                // 简单的黑屏检测（可选）：如果不想封面是纯黑，可以在这里加个像素均值判断
                                String objectName = customId + ".jpg";
                                minioService.uploadImage(image, "final-cover", objectName);
                                log.info("🖼️ 封面提取成功 [ID={}]: 使用时间点 {}ms", customId, point / 1000);
                                return objectName;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ 时间点 {}ms 提取封面失败，尝试下一个点...", point / 1000);
                        // 继续循环，尝试下一个点
                    }
                }
            }
        }
        throw new RuntimeException("视频所有关键位置均无法提取有效封面");
    }



    private void deleteDirectory(@NotNull File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) deleteDirectory(child);
        }
        dir.delete();
    }
}