package com.example.hybridrag.web;

import com.example.hybridrag.ingest.DocumentIngestService;
import com.example.hybridrag.retrieval.HybridRetrievalService;
import com.example.hybridrag.retrieval.dto.ScoredChunk;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 检索与入库演示接口。 */
@RestController
@RequestMapping("/api")
public class RetrievalController {

    private final HybridRetrievalService retrieval;
    private final DocumentIngestService ingest;

    public RetrievalController(HybridRetrievalService retrieval, DocumentIngestService ingest) {
        this.retrieval = retrieval;
        this.ingest = ingest;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "up", "chunks", ingest.chunkCount());
    }

    /** 手动触发样例语料入库；启动时已自动执行一次。 */
    @GetMapping("/ingest")
    public Map<String, Object> ingest() throws Exception {
        int docs = ingest.ingestSamples();
        return Map.of("documents", docs, "chunks", ingest.chunkCount());
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        int topK = request.topK() == null || request.topK() < 1 ? 5 : Math.min(request.topK(), 20);
        List<ScoredChunk> results = retrieval.search(request.query(), topK);
        return Map.of("query", request.query(), "count", results.size(), "results", results);
    }

    public record SearchRequest(String query, Integer topK) {
    }
}
