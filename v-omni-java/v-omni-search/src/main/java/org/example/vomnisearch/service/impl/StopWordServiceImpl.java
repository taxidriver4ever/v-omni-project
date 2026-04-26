package org.example.vomnisearch.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.vomnisearch.service.StopWordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class StopWordServiceImpl implements StopWordService {

    @jakarta.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOP_WORDS_KEY = "hot_words:stop_words";

    /**
     * 扫描 resources/stop_words 目录下所有文件并导入 Redis
     */
    public void importFromDirectory() throws IOException {
        // 1. 定义资源解析器
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            // 2. 扫描 stop_words 文件夹下所有的 txt 文件
            // classpath*: 表示在所有 jar 包和本地类路径下搜索
            Resource[] resources = resolver.getResources("classpath:stop_words/*.txt");

            if (resources.length == 0) {
                log.warn("在 stop_words 目录下未找到任何 txt 文件");
                return;
            }

            for (Resource resource : resources) {
                log.info("正在加载垃圾词文件: {}", resource.getFilename());
                processFile(resource);
            }

            log.info("所有词库文件导入完成！");

        } catch (IOException e) {
            log.error("扫描 stop_words 目录失败", e);
        }
    }

    @Override
    public boolean isStopWord(String word) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(STOP_WORDS_KEY, word));
    }


    private void processFile(Resource resource) {
        // 使用 BufferedReader 逐行读取，防止大文件撑爆内存
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            List<String> batch = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                // 简单的初步清洗
                if (!word.isEmpty() && word.length() > 1) {
                    batch.add(word);
                }

                // 每 1000 条刷一次 Redis
                if (batch.size() >= 1000) {
                    flush(batch);
                    batch.clear(); // 强制清除引用，让 GC 尽快回收这些字符串
                }
            }

            // 处理不足 1000 条的尾部数据
            if (!batch.isEmpty()) {
                flush(batch);
                batch.clear();
            }

        } catch (IOException e) {
            log.error("读取文件 {} 失败", resource.getFilename(), e);
        }
    }

    private void flush(List<String> batch) {
        // SADD 命令在 Redis 中是去重的，非常适合存放黑名单
        stringRedisTemplate.opsForSet().add(STOP_WORDS_KEY, batch.toArray(new String[0]));
    }
}

