package com.example.hybridrag.retrieval;

import com.example.hybridrag.model.EmbeddingClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 向量路召回：查询嵌入与片段嵌入算余弦相似度，取前 candidateK。
 * 教学版全表扫描 + 内存排序；生产替换为 pgvector HNSW 索引（见 README 第 7 节）。
 */
@Service
public class VectorSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embedding;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TypeReference<List<Double>> vectorType = new TypeReference<>() {};

    public VectorSearchService(JdbcTemplate jdbc, EmbeddingClient embedding) {
        this.jdbc = jdbc;
        this.embedding = embedding;
    }

    public List<String> search(String query, int candidateK) {
        List<Double> queryVector = embedding.embed(query);
        List<ChunkRow> rows = loadAll();
        return rows.stream()
                .map(row -> new Scored(row.content(), cosine(queryVector, row.embedding())))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(candidateK)
                .map(Scored::content)
                .toList();
    }

    private List<ChunkRow> loadAll() {
        return jdbc.query(
                "SELECT c.id, c.content, d.title, c.chunk_index, c.embedding, c.metadata FROM chunks c JOIN documents d ON c.document_id = d.id",
                (rs, i) -> new ChunkRow(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getString("title"),
                        rs.getInt("chunk_index"),
                        readVector(rs.getString("embedding")),
                        null));
    }

    private List<Double> readVector(String json) {
        try {
            return mapper.readValue(json, vectorType);
        } catch (Exception e) {
            return List.of();
        }
    }

    private double cosine(List<Double> a, List<Double> b) {
        if (a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
        }
        return dot; // 两侧向量已归一化，余弦即点积
    }

    private record Scored(String content, double score) {
    }
}
