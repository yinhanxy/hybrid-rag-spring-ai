package com.example.hybridrag.retrieval;

import com.example.hybridrag.config.AppProperties;
import com.example.hybridrag.model.RerankClient;
import com.example.hybridrag.retrieval.dto.ScoredChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 混合检索编排：向量路 + 全文路并行召回 → RRF 融合 → 可选重排 → 缓存。
 * 任一路异常降级为单路结果，不整体失败；重排开启时先扩大候选倍数，避免融合阶段裁死候选。
 */
@Service
public class HybridRetrievalService {

    private final VectorSearchService vectorSearch;
    private final FullTextSearchService fullTextSearch;
    private final RankFusionService fusion;
    private final RerankClient rerank;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TypeReference<List<Double>> vectorType = new TypeReference<>() {};
    private final ExecutorService pool;
    private final Cache<String, List<ScoredChunk>> cache;

    public HybridRetrievalService(VectorSearchService vectorSearch,
                                  FullTextSearchService fullTextSearch,
                                  RankFusionService fusion,
                                  RerankClient rerank,
                                  JdbcTemplate jdbc,
                                  AppProperties props) {
        this.vectorSearch = vectorSearch;
        this.fullTextSearch = fullTextSearch;
        this.fusion = fusion;
        this.rerank = rerank;
        this.jdbc = jdbc;
        this.props = props;
        this.pool = Executors.newFixedThreadPool(props.retrieval().threadPoolSize());
        this.cache = props.retrieval().cacheEnabled()
                ? Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.retrieval().cacheTtlSeconds()))
                .maximumSize(500)
                .build()
                : null;
    }

    public List<ScoredChunk> search(String query, int topK) {
        String cacheKey = query.trim() + "|" + topK;
        if (cache != null) {
            List<ScoredChunk> cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        int candidateK = rerank.isEnabled()
                ? topK * props.retrieval().candidateMultiplier()
                : topK;

        CompletableFuture<List<String>> vectorFuture = CompletableFuture.supplyAsync(
                () -> safeCall(() -> vectorSearch.search(query, candidateK)), pool);
        CompletableFuture<List<String>> textFuture = CompletableFuture.supplyAsync(
                () -> safeCall(() -> fullTextSearch.search(query, candidateK)), pool);

        List<String> vectorResults = vectorFuture.join();
        List<String> textResults = textFuture.join();

        int mergeLimit = rerank.isEnabled() ? candidateK : topK;
        List<RankFusionService.Fused> fused = fusion.fuseWithWeights(
                List.of(vectorResults, textResults),
                List.of(props.retrieval().vectorWeight(), props.retrieval().textWeight()),
                mergeLimit);

        Map<String, ChunkRow> rowByContent = loadRowIndex();
        List<ScoredChunk> candidates = new ArrayList<>();
        for (int i = 0; i < fused.size(); i++) {
            RankFusionService.Fused f = fused.get(i);
            ChunkRow row = rowByContent.get(f.content());
            if (row == null) {
                continue;
            }
            candidates.add(new ScoredChunk(
                    i + 1, f.content(), row.source(), row.chunkIndex(), f.score(), null));
        }

        List<ScoredChunk> finalResults;
        if (rerank.isEnabled() && candidates.size() > 1) {
            finalResults = rerank.rerank(query, candidates, topK);
        } else {
            finalResults = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, candidates.size()); i++) {
                finalResults.add(candidates.get(i).withRank(i + 1));
            }
        }

        if (cache != null) {
            cache.put(cacheKey, finalResults);
        }
        return finalResults;
    }

    private List<String> safeCall(java.util.function.Supplier<List<String>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, ChunkRow> loadRowIndex() {
        Map<String, ChunkRow> index = new HashMap<>();
        jdbc.query(
                "SELECT c.id, c.content, d.title, c.chunk_index, c.embedding FROM chunks c JOIN documents d ON c.document_id = d.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    List<Double> embedding = readVector(rs.getString("embedding"));
                    ChunkRow row = new ChunkRow(
                            rs.getString("id"),
                            rs.getString("content"),
                            rs.getString("title"),
                            rs.getInt("chunk_index"),
                            embedding,
                            null);
                    index.put(row.content(), row);
                });
        return index;
    }

    private List<Double> readVector(String json) {
        try {
            return mapper.readValue(json, vectorType);
        } catch (Exception e) {
            return List.of();
        }
    }
}
