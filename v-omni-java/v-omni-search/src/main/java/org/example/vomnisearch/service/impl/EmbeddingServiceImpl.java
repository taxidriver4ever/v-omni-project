package org.example.vomnisearch.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.example.vomnisearch.dto.TeiBatchRequest;
import org.example.vomnisearch.dto.TeiRequest;
import org.example.vomnisearch.service.EmbeddingService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final RestClient restClient;

    // 依然保留缓存，毕竟 1ms 的缓存响应永远比 20ms 的显卡推理快
    private final Cache<String, float[][]> vectorCache = Caffeine.newBuilder()
            .maximumSize(2000) // 既然内存够，可以稍微调大
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    // 【修改】调大并发数：4060 配合 TEI 建议设置为 32-64
    // 这样能充分利用 GPU 的并行计算能力（Tensor Cores）
    private final Semaphore semaphore = new Semaphore(32);

    private static final String TEI_BASE_URL = "http://localhost:8080";

    public EmbeddingServiceImpl(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(TEI_BASE_URL).build();
    }

    @Override
    public float[][] getVector(String text) {
        if (text == null || text.isBlank()) {
            return new float[1][1024];
        }

        return vectorCache.get(text, t -> {
            try {
                // 【修改】因为 GPU 推理极快，等待时间可以缩短，提高响应灵敏度
                if (semaphore.tryAcquire(2, TimeUnit.SECONDS)) {
                    try {
                        return restClient.post()
                                .uri("/embed")
                                .body(new TeiRequest(t))
                                .retrieve()
                                .body(float[][].class);
                    } finally {
                        semaphore.release();
                    }
                } else {
                    throw new RuntimeException("GPU 推理队列已满，请稍后再试");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("线程中断", e);
            }
        });
    }

    @Override
    public float[][] getVectors(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][0];
        }

        // 【修改】批量处理也要受信号量保护，防止瞬间超大规模批处理冲垮显存
        try {
            if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                try {
                    return restClient.post()
                            .uri("/embed")
                            .body(new TeiBatchRequest(texts))
                            .retrieve()
                            .body(float[][].class);
                } finally {
                    semaphore.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new RuntimeException("批量推理任务繁忙");
    }
}
