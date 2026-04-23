package org.example.vomnisearch.service;

import org.redisson.api.RBloomFilter;

public interface UserBloomFilterService {
    RBloomFilter<String> getFilter(Long userId);
}
