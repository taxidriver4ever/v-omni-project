package org.example.vomnisearch.service.impl;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.service.VectorService;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ClipVectorServiceImpl implements VectorService, AutoCloseable {

    private OrtEnvironment env;
    private OrtSession session;

    // CLIP 标准常量
    private static final int IMAGE_SIZE = 224;
    private static final int MAX_TEXT_LENGTH = 77;
    private static final int VECTOR_DIM = 512;
    private HuggingFaceTokenizer tokenizer;

    @PostConstruct
    public void init() {
        try {
            this.env = OrtEnvironment.getEnvironment();
            // 确保模型文件在 src/main/resources/models/ 目录下
            ClassPathResource resource = new ClassPathResource("models/clip_mean_pool.onnx");
            File tempFile = File.createTempFile("clip_combined_model", ".onnx");
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            // options.addCUDA(0); // 如果服务器有 GPU 且装了相应的 onnxruntime-gpu 依赖，可以开启加速

            this.session = env.createSession(tempFile.getAbsolutePath(), options);
            this.tokenizer = HuggingFaceTokenizer.newInstance("openai/clip-vit-base-patch32");
            log.info("✅ Search 服务：集成化 CLIP ONNX 模型加载成功 (Vision + Text + UserTower)");
            tempFile.deleteOnExit();
        } catch (Exception e) {
            log.error("❌ Search 服务：CLIP 模型加载失败", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public float[] getTextVector(String text) throws Exception {
        if (text == null || text.isEmpty()) return new float[VECTOR_DIM];

        long[] tokenIds = tokenize(text);
        long[] mask = new long[MAX_TEXT_LENGTH];
        for (int i = 0; i < MAX_TEXT_LENGTH; i++) {
            mask[i] = (tokenIds[i] != 0) ? 1 : 0;
        }

        // --- 准备占位输入 ---
        float[] placeholderImg = new float[1 * 3 * IMAGE_SIZE * IMAGE_SIZE];
        float[] dummyLongTerm = new float[VECTOR_DIM];
        float[] dummyBehavior = new float[VECTOR_DIM];
        float[] dummyWeights = new float[]{0.0f};

        Map<String, OnnxTensor> container = new HashMap<>();
        try {
            // 原有输入
            container.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(placeholderImg), new long[]{1, 3, IMAGE_SIZE, IMAGE_SIZE}));
            container.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), new long[]{1, MAX_TEXT_LENGTH}));
            container.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, MAX_TEXT_LENGTH}));

            // 新增的必填占位输入
            container.put("long_term_vector", OnnxTensor.createTensor(env, FloatBuffer.wrap(dummyLongTerm), new long[]{1, VECTOR_DIM}));
            container.put("behavior_sequence", OnnxTensor.createTensor(env, FloatBuffer.wrap(dummyBehavior), new long[]{1, 1, VECTOR_DIM}));
            container.put("behavior_weights", OnnxTensor.createTensor(env, FloatBuffer.wrap(dummyWeights), new long[]{1, 1}));

            try (OrtSession.Result results = session.run(container)) {
                // 输出格式为 [Batch, Dim]
                float[][] out = (float[][]) results.get("text_embedding").get().getValue();
                return out[0];
            }
        } finally {
            container.values().forEach(OnnxTensor::close);
        }
    }

    @Override
    public float[] fuseUserInterest(float[] currentVector, float[] lastVector, float[] interestVector) throws Exception {
        // 1. 准备 behavior_sequence (将前一次和当前一次拼接)
        // 维度要求: [batch, sequence_length, dim] -> [1, 2, 512]
        float[] combinedBehavior = new float[2 * VECTOR_DIM];
        System.arraycopy(lastVector, 0, combinedBehavior, 0, VECTOR_DIM);
        System.arraycopy(currentVector, 0, combinedBehavior, VECTOR_DIM, VECTOR_DIM);

        // 2. 准备行为权重 (假设两次搜索权重一致，都设为 1.0)
        float[] weights = new float[]{1.0f, 1.0f};

        // 3. 准备 CLIP 占位输入 (模型 forward 必填，但此处逻辑不使用)
        float[] placeholderImg = new float[1 * 3 * IMAGE_SIZE * IMAGE_SIZE];
        long[] placeholderTokens = new long[MAX_TEXT_LENGTH];

        Map<String, OnnxTensor> container = new HashMap<>();
        try {
            // CLIP 占位部分
            container.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(placeholderImg), new long[]{1, 3, IMAGE_SIZE, IMAGE_SIZE}));
            container.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(placeholderTokens), new long[]{1, MAX_TEXT_LENGTH}));
            container.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(placeholderTokens), new long[]{1, MAX_TEXT_LENGTH}));

            // --- 核心业务输入 ---
            // 长期兴趣作为 Query
            container.put("long_term_vector", OnnxTensor.createTensor(env, FloatBuffer.wrap(interestVector), new long[]{1, VECTOR_DIM}));
            // 前后两次搜索作为 Key/Value 序列 [1, 2, 512]
            container.put("behavior_sequence", OnnxTensor.createTensor(env, FloatBuffer.wrap(combinedBehavior), new long[]{1, 2, VECTOR_DIM}));
            // 权重序列 [1, 2]
            container.put("behavior_weights", OnnxTensor.createTensor(env, FloatBuffer.wrap(weights), new long[]{1, 2}));

            try (OrtSession.Result results = session.run(container)) {
                // 获取 Python 模型中定义的 output_names=["user_interest_embedding"]
                float[][] out = (float[][]) results.get("user_interest_embedding").get().getValue();
                return out[0]; // 返回融合后的 512 维向量
            }
        } finally {
            container.values().forEach(OnnxTensor::close);
        }
    }

    private long[] tokenize(String text) {
        // 使用真正的 BPE 分词
        var encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();

        long[] tokens = new long[MAX_TEXT_LENGTH];
        // 填充到 77 的长度
        System.arraycopy(ids, 0, tokens, 0, Math.min(ids.length, MAX_TEXT_LENGTH));
        return tokens;
    }

    @PreDestroy
    @Override
    public void close() throws OrtException {
        if (session != null) {
            session.close();
        }
        if (env != null) {
            env.close();
        }
        log.info("🧹 Search 服务：ONNX 资源已释放");
    }
}