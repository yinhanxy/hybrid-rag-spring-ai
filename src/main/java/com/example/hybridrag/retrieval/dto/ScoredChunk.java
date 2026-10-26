package com.example.hybridrag.retrieval.dto;

/**
 * 融合后的候选片段。rank 从 1 起；rerankScore 仅在重排开启时填充，直通时为 null。
 *
 * @param rank        最终排名（1 起）
 * @param content     片段正文
 * @param source      来源文档标题
 * @param chunkIndex  文档内分块序号
 * @param rrfScore    RRF 融合分数
 * @param rerankScore 重排分数（直通时为 null）
 */
public record ScoredChunk(
        int rank,
        String content,
        String source,
        int chunkIndex,
        double rrfScore,
        Double rerankScore
) {
    public ScoredChunk withRank(int newRank) {
        return new ScoredChunk(newRank, content, source, chunkIndex, rrfScore, rerankScore);
    }

    public ScoredChunk withRerankScore(Double score) {
        return new ScoredChunk(rank, content, source, chunkIndex, rrfScore, score);
    }
}
