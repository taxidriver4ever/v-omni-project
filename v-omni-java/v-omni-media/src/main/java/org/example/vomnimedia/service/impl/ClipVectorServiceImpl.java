package org.example.vomnimedia.service.impl;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.service.VectorService;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.List;

@Slf4j
@Service
public class ClipVectorServiceImpl implements VectorService, AutoCloseable {

    @Value("${v-omni.model-path}")
    private String modelPath;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    private static final int IMAGE_SIZE = 224;
    private static final int MAX_TEXT_LENGTH = 77;
    private static final int VECTOR_DIM = 512;

    @PostConstruct
    public void init() {
        try {
            log.info("🔍 初始化 CLIP 向量服务...");
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) throw new RuntimeException("模型文件缺失: " + modelPath);

            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();

            // 调试建议：先注释掉 addCUDA 以确认能用 CPU 启动
//             options.addCUDA(0);

            options.setIntraOpNumThreads(4);

            this.tokenizer = HuggingFaceTokenizer.newInstance("openai/clip-vit-base-patch32");
            this.session = env.createSession(modelPath, options);
            log.info("✅ 模型加载成功");

        } catch (Exception e) {
            log.error("❌ 初始化失败", e);
            throw new RuntimeException(e);
        }
    }

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

        Map<String, OnnxTensor> container = new HashMap<>();
        try {
            // 修复：1.16.x 版本明确使用 FloatBuffer.wrap 结合具体的 shape
            container.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), new long[]{batchSize, 3, IMAGE_SIZE, IMAGE_SIZE}));

            // 占位符确保 BatchSize 匹配
            container.put("input_ids", createLongTensor(batchSize, MAX_TEXT_LENGTH));
            container.put("attention_mask", createLongTensor(batchSize, MAX_TEXT_LENGTH));
            container.put("long_term_vector", createFloatTensor(batchSize, VECTOR_DIM));
            container.put("behavior_sequence", createFloatTensor(batchSize, 1, VECTOR_DIM));
            container.put("behavior_weights", createFloatTensor(batchSize, 1));

            try (OrtSession.Result results = session.run(container)) {
                Object outValue = results.get("image_embedding").get().getValue();

                // 增加健壮性判断
                if (outValue instanceof float[][]) {
                    float[][] out = (float[][]) outValue;
                    return out[0].clone();
                } else if (outValue instanceof float[]) {
                    return ((float[]) outValue).clone();
                } else {
                    throw new RuntimeException("意外的模型输出类型: " + outValue.getClass().getName());
                }
            }
        } finally {
            container.values().forEach(OnnxTensor::close);
        }
    }

    @Override
    public float[] getTextVector(String text) throws Exception {
        if (text == null || text.isBlank()) return new float[VECTOR_DIM];

        long[] tokenIds = tokenize(text);
        long[] mask = new long[MAX_TEXT_LENGTH];
        for (int i = 0; i < MAX_TEXT_LENGTH; i++) mask[i] = (tokenIds[i] != 0) ? 1L : 0L;

        Map<String, OnnxTensor> container = new HashMap<>();
        try {
            container.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), new long[]{1, MAX_TEXT_LENGTH}));
            container.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_TEXT_LENGTH}));

            container.put("pixel_values", createFloatTensor(1, 3, IMAGE_SIZE, IMAGE_SIZE));
            container.put("long_term_vector", createFloatTensor(1, VECTOR_DIM));
            container.put("behavior_sequence", createFloatTensor(1, 1, VECTOR_DIM));
            container.put("behavior_weights", createFloatTensor(1, 1));

            try (OrtSession.Result results = session.run(container)) {
                Object outValue = results.get("text_embedding").get().getValue();

                if (outValue instanceof float[][]) {
                    float[][] out = (float[][]) outValue;
                    return out[0].clone();
                } else if (outValue instanceof float[]) {
                    return ((float[]) outValue).clone();
                } else {
                    throw new RuntimeException("意外的模型输出类型: " + outValue.getClass().getName());
                }
            }
        } finally {
            container.values().forEach(OnnxTensor::close);
        }
    }

    // --- 修复后的辅助方法 ---

    private OnnxTensor createLongTensor(long... shape) throws OrtException {
        int size = 1;
        for (long s : shape) size *= s;
        return OnnxTensor.createTensor(env, LongBuffer.wrap(new long[size]), shape);
    }

    private OnnxTensor createFloatTensor(long... shape) throws OrtException {
        int size = 1;
        for (long s : shape) size *= s;
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[size]), shape);
    }

    private long[] tokenize(String text) {
        var encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[] tokens = new long[MAX_TEXT_LENGTH];
        System.arraycopy(ids, 0, tokens, 0, Math.min(ids.length, MAX_TEXT_LENGTH));
        return tokens;
    }

    @NotNull
    private float[] preprocess(BufferedImage img) {
        BufferedImage resized = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
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