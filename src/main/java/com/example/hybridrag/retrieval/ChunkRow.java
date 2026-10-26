package com.example.hybridrag.retrieval;

import java.util.List;

/** 从 chunks 表读出的候选片段，供两路召回与融合使用。 */
public record ChunkRow(
        String id,
        String content,
        String source,
        int chunkIndex,
        List<Double> embedding,
        String sectionTitle
) {
}
