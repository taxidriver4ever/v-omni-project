package org.example.vomnisearch.dto;

import java.util.List;

/**
 * 如果你需要一次性向量化多个句子，可以使用这个（TEI 支持批量）
 */
public record TeiBatchRequest(List<String> inputs) {}