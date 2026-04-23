package org.example.vomnisearch.service.impl;

import jakarta.annotation.Resource;
import org.example.vomnisearch.service.UserBloomFilterService;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserBloomFilterServiceImpl implements UserBloomFilterService {

    @Resource
    private RedissonClient redissonClient;

    // 统一定义基础参数
    private static final long EXPECTED_INSERTIONS = 10000L; // 假设每个用户看 1w 个视频
    private static final double FALSE_PROBABILITY = 0.03;   // 误判率 3%

    @Override
    public RBloomFilter<String> getFilter(Long userId) {
        // 为每个用户创建唯一的 Key
        String bloomKey = "user:seen:bloom:" + userId;
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomKey);

        // 关键：如果这个用户的过滤器还没初始化，则进行初始化
        // tryInit 会在 Key 不存在时初始化，已存在则跳过
        bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);

        return bloomFilter;
    }
}
