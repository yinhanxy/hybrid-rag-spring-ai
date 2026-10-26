package com.example.hybridrag.ingest;

import com.example.hybridrag.model.EmbeddingClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 文档入库编排：分块 → 嵌入 → 写入 documents / chunks。
 * 删除文档由 schema 级联清理记录、向量与全文索引（教学版同库完成）。
 */
@Service
public class DocumentIngestService {

    private final JdbcTemplate jdbc;
    private final ChunkingService chunking;
    private final EmbeddingClient embedding;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResourcePatternResolver resources;

    public DocumentIngestService(JdbcTemplate jdbc,
                                 ChunkingService chunking,
                                 EmbeddingClient embedding,
                                 ResourcePatternResolver resources) {
        this.jdbc = jdbc;
        this.chunking = chunking;
        this.embedding = embedding;
        this.resources = resources;
    }

    /** 入库一篇文档，返回文档 ID。 */
    public String ingestDocument(String title, String source, String body) {
        String docId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, title, source) VALUES (?, ?, ?)", docId, title, source);

        List<ChunkingService.Chunk> chunks = chunking.chunk(title, body);
        for (ChunkingService.Chunk chunk : chunks) {
            String embeddingJson = toJson(embedding.embed(chunk.content()));
            String metadata = toJson(new Metadata(chunk.sectionTitle(), source));
            jdbc.update(
                    "INSERT INTO chunks (id, document_id, content, chunk_index, metadata, embedding) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), docId, chunk.content(), chunk.index(), metadata, embeddingJson);
        }
        return docId;
    }

    /** 加载 classpath:samples/*.txt 全部样例语料入库，返回入库文档数。 */
    public int ingestSamples() throws IOException {
        Resource[] files = resources.getResources("classpath:samples/*.txt");
        int count = 0;
        for (Resource file : files) {
            String body = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String filename = file.getFilename() == null ? "sample" : file.getFilename();
            String title = filename.replaceAll("\\.txt$", "");
            ingestDocument(title, filename, body);
            count++;
        }
        return count;
    }

    public int chunkCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class);
        return n == null ? 0 : n;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败: " + e.getMessage(), e);
        }
    }

    private record Metadata(String sectionTitle, String source) {
    }
}
