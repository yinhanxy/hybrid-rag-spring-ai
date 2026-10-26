package com.example.hybridrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工程可调参数，全部来自 application.yml，不写死在代码里。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Embedding embedding,
        Chunk chunk,
        Retrieval retrieval
) {
    public record Embedding(int dimension) {
    }

    public record Chunk(int maxChars, int overlapChars) {
    }

    public record Retrieval(
            int rrfK,
            double vectorWeight,
            double textWeight,
            int candidateMultiplier,
            boolean cacheEnabled,
            long cacheTtlSeconds,
            int threadPoolSize
    ) {
    }
}
