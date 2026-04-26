package org.example.vomniinteract.service.impl;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniinteract.service.VectorService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ClipVectorServiceImpl implements VectorService, AutoCloseable {

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private static final int MAX_TEXT_LENGTH = 77;

    @PostConstruct
    public void init() {
        try {
            this.env = OrtEnvironment.getEnvironment();
            // 加载你导出的新版本 clip_mean_pool.onnx
            ClassPathResource resource = new ClassPathResource("model/clip_mean_pool.onnx");
            File tempFile = File.createTempFile("model", ".onnx");
            Files.copy(resource.getInputStream(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.addCUDA(0); // 16GB 4060 启动！
            this.session = env.createSession(tempFile.getAbsolutePath(), options);
            this.tokenizer = HuggingFaceTokenizer.newInstance("openai/clip-vit-base-patch32");
            log.info("ONNX 用户兴趣塔模型加载成功 (CUDA 加速已开启)");
        } catch (Exception e) {
            log.error("ONNX 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 更换后的真正的 Tokenize 方法
     */
    private long[] tokenize(String text) {
        // 使用 DJL 的分词器，它会自动处理截断、填充和特殊符号
        var encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();

        long[] tokens = new long[MAX_TEXT_LENGTH];
        // 复制 ID，如果过长则截断，如果不足 77 则后面补 0 (符合 CLIP 标准)
        System.arraycopy(ids, 0, tokens, 0, Math.min(ids.length, MAX_TEXT_LENGTH));
        return tokens;
    }

    /**
     * 核心方法：演化用户兴趣向量
     * @param oldInterest 长期兴趣向量 (Redis)
     * @param behaviorVectors 行为序列向量列表 (从 Redis 获取 mediaId 后查出的 Vector)
     * @param actions 行为类型列表 (对应 behaviorVectors 的 actionTag)
     */
    @Override
    public float[] fuseUserInterest(float[] oldInterest, List<float[]> behaviorVectors, List<String> actions) {
        int seqLen = behaviorVectors.size();
        if (seqLen == 0) return oldInterest;

        try {
            Map<String, OnnxTensor> inputs = new HashMap<>();

            // 1. long_term_vector [1, 512]
            inputs.put("long_term_vector", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(oldInterest), new long[]{1, 512}));

            // 2. behavior_sequence [1, seqLen, 512]
            float[] flatSequence = new float[seqLen * 512];
            for (int i = 0; i < seqLen; i++) {
                System.arraycopy(behaviorVectors.get(i), 0, flatSequence, i * 512, 512);
            }
            inputs.put("behavior_sequence", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(flatSequence), new long[]{1, seqLen, 512}));

            // 3. behavior_weights [1, seqLen] - 关键：将互动映射为权重
            float[] weights = new float[seqLen];
            for (int i = 0; i < seqLen; i++) {
                weights[i] = mapActionToWeight(actions.get(i));
            }
            inputs.put("behavior_weights", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(weights), new long[]{1, seqLen}));

            // 4. 占位输入：因为是组合模型，即便不推理 CLIP 也需要传空张量（对应 Python 的 dummy input）
            // 维度需与导出的 ONNX dynamic_axes 匹配
            inputs.put("pixel_values", OnnxTensor.createTensor(env, new float[1][3][224][224]));
            inputs.put("input_ids", OnnxTensor.createTensor(env, new long[1][77]));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[1][77]));

            // 5. 执行推理
            try (OrtSession.Result results = session.run(inputs)) {
                // 根据 Python 导出的顺序，第 3 个输出通常是 user_interest_embedding
                // 或者通过名称获取更好
                OnnxValue output = results.get("user_interest_embedding").orElse(results.get(2));
                return ((float[][]) output.getValue())[0];
            }
        } catch (OrtException e) {
            log.error("兴趣演化推理异常: {}", e.getMessage());
            return oldInterest;
        }
    }

    /**
     * 权重映射逻辑：配合 Python 端的 torch.pow(x, 2)
     */
    private float mapActionToWeight(String action) {
        return switch (action.toLowerCase()) {
            case "collect" -> 3.0f; // 最终影响力: 9.0
            case "comment" -> 2.5f; // 最终影响力: 6.25
            case "like"    -> 2.0f; // 最终影响力: 4.0
            case "view"    -> 1.0f; // 最终影响力: 1.0
            case "cancel_like" -> 0.2f; // 负面反馈，几乎不给注意力
            default -> 1.0f;
        };
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (Exception e) {
            log.error("资源释放失败");
        }
    }
}