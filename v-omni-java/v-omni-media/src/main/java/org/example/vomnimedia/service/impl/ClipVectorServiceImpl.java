package org.example.vomnimedia.service.impl;

import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.service.VectorService;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;

@Slf4j
@Service
public class ClipVectorServiceImpl implements VectorService, AutoCloseable {

    private OrtEnvironment env;
    private OrtSession session;

    // CLIP 标准常量
    private static final int IMAGE_SIZE = 224;
    private static final int MAX_TEXT_LENGTH = 77;
    private static final int VECTOR_DIM = 512;

    @PostConstruct
    public void init() {
        try {
            this.env = OrtEnvironment.getEnvironment();
            // 确保模型文件在 resources/models 下
            ClassPathResource resource = new ClassPathResource("models/clip_mean_pool.onnx");
            File tempFile = File.createTempFile("clip_combined_model", ".onnx");
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // GPU 配置 (可选)
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            // options.addCUDA(0); // 如果有显卡可以开启

            this.session = env.createSession(tempFile.getAbsolutePath(), options);
            log.info("✅ 集成化 CLIP ONNX 模型加载成功 (Vision + Text)");
            tempFile.deleteOnExit();
        } catch (Exception e) {
            log.error("❌ CLIP 模型加载失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 图片向量化：处理视频帧序列，返回 Mean-Pooled 向量
     */
    @Override
    public float[] getVector(List<byte[]> imageBytesList) throws Exception {
        if (imageBytesList == null || imageBytesList.isEmpty()) return new float[VECTOR_DIM];

        int batchSize = imageBytesList.size();
        float[] inputData = new float[batchSize * 3 * IMAGE_SIZE * IMAGE_SIZE];

        for (int i = 0; i < batchSize; i++) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytesList.get(i))) {
                BufferedImage img = ImageIO.read(bais);
                if (img != null) {
                    float[] processed = preprocess(img);
                    System.arraycopy(processed, 0, inputData, i * 3 * IMAGE_SIZE * IMAGE_SIZE, processed.length);
                }
            }
        }

        // 占位文本输入 (ONNX 模型导出时定义了多输入，必须提供占位)
        long[] placeholderIds = new long[MAX_TEXT_LENGTH];
        long[] placeholderMask = new long[MAX_TEXT_LENGTH];
        Arrays.fill(placeholderMask, 0);

        Map<String, OnnxTensor> container = new HashMap<>();
        container.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), new long[]{batchSize, 3, IMAGE_SIZE, IMAGE_SIZE}));
        container.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(placeholderIds), new long[]{1, MAX_TEXT_LENGTH}));
        container.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(placeholderMask), new long[]{1, MAX_TEXT_LENGTH}));

        try (OrtSession.Result results = session.run(container)) {
            // 获取 image_embedding 输出
            OnnxValue out = results.get("image_embedding").get();
            return (float[]) out.getValue();
        } finally {
            container.values().forEach(OnnxTensor::close);
        }
    }

    /**
     * 文本向量化：将标题转为 512 维向量
     */
    @Override
    public float[] getTextVector(String text) throws Exception {
        if (text == null || text.isEmpty()) return new float[VECTOR_DIM];

        // 1. 获取 Token (建议在生产环境实现真正的 CLIP BPE Tokenizer)
        long[] tokenIds = tokenize(text);
        long[] mask = new long[MAX_TEXT_LENGTH];
        for (int i = 0; i < MAX_TEXT_LENGTH; i++) {
            mask[i] = (tokenIds[i] != 0) ? 1 : 0;
        }

        // 2. 占位图片输入
        float[] placeholderImg = new float[1 * 3 * IMAGE_SIZE * IMAGE_SIZE];

        Map<String, OnnxTensor> container = new HashMap<>();
        container.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(placeholderImg), new long[]{1, 3, IMAGE_SIZE, IMAGE_SIZE}));
        container.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), new long[]{1, MAX_TEXT_LENGTH}));
        container.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_TEXT_LENGTH}));

        try (OrtSession.Result results = session.run(container)) {
            // 获取 text_embedding 输出
            // 注意：Python 导出的是 [Batch, Dim]，所以这里强转 float[][]
            float[][] out = (float[][]) results.get("text_embedding").get().getValue();
            return out[0]; // 返回第一条文本的向量
        } finally {
            container.values().forEach(OnnxTensor::close);
        }
    }

    /**
     * 简单的 Tokenizer 占位。
     * 实际项目中请使用 DJL 的 CLIPTokenizer 或通过 JNI 调用 Python 分词。
     */
    private long[] tokenize(String text) {
        long[] tokens = new long[MAX_TEXT_LENGTH];
        // 简化版：仅用于跑通链路。CLIP 的开始符通常是 49406，结束符 49407
        tokens[0] = 49406;
        char[] chars = text.toCharArray();
        for (int i = 0; i < Math.min(chars.length, MAX_TEXT_LENGTH - 2); i++) {
            tokens[i + 1] = chars[i]; // 仅作示意
        }
        tokens[Math.min(chars.length + 1, MAX_TEXT_LENGTH - 1)] = 49407;
        return tokens;
    }

    @NotNull
    private float[] preprocess(BufferedImage img) {
        // ... (保持你之前的 preprocess 代码，它是正确的)
        BufferedImage resized = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(img, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
        g2d.dispose();

        float[] result = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
        float[] mean = {0.48145466f, 0.4578275f, 0.40821073f};
        float[] std = {0.26862954f, 0.26130258f, 0.27577711f};

        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float g = ((rgb >> 8) & 0xFF) / 255.0f;
                float b = (rgb & 0xFF) / 255.0f;
                result[0 * IMAGE_SIZE * IMAGE_SIZE + y * IMAGE_SIZE + x] = (r - mean[0]) / std[0];
                result[1 * IMAGE_SIZE * IMAGE_SIZE + y * IMAGE_SIZE + x] = (g - mean[1]) / std[1];
                result[2 * IMAGE_SIZE * IMAGE_SIZE + y * IMAGE_SIZE + x] = (b - mean[2]) / std[2];
            }
        }
        return result;
    }

    @PreDestroy
    @Override
    public void close() throws OrtException {
        if (session != null) session.close();
        if (env != null) env.close();
    }
}