package com.example.hybridrag.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 全文路召回：教学版内存倒排 + 简化 BM25 打分；无命中时降级到子串包含匹配。
 * 生产替换为 PostgreSQL tsvector + GIN 索引，中文分词替换点见 README 第 7 节。
 */
@Service
public class FullTextSearchService {

    private final JdbcTemplate jdbc;

    public FullTextSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> search(String query, int candidateK) {
        List<ChunkRow> rows = loadAll();
        Set<String> queryTokens = tokenize(query);

        List<Scored> scored = new ArrayList<>();
        for (ChunkRow row : rows) {
            Set<String> contentTokens = tokenize(row.content());
            double score = bm25Like(queryTokens, contentTokens, row.content().length());
            boolean containsRaw = row.content().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).trim());
            if (score > 0 || containsRaw) {
                scored.add(new Scored(row.content(), score + (containsRaw ? 0.5 : 0)));
            }
        }
        return scored.stream()
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
                        List.of(),
                        null));
    }

    /** 简化 BM25：词频累加，除以片段长度归一，k1 取 1.2 教学固定值。 */
    private double bm25Like(Set<String> queryTokens, Set<String> contentTokens, int length) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        double avgLen = 200.0;
        double k1 = 1.2;
        for (String token : queryTokens) {
            long tf = contentTokens.stream().filter(t -> t.equals(token)).count();
            if (tf > 0) {
                double lenNorm = 1.0 - 0.75 + 0.75 * (length / avgLen);
                score += (tf * (k1 + 1)) / (tf + k1 * lenNorm);
            }
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushAscii(StringBuilder sb, Set<String> tokens) {
        if (sb.length() > 0) {
            tokens.add(sb.toString());
            sb.setLength(0);
        }
    }

    private void flushCjk(StringBuilder sb, Set<String> tokens) {
        String cjk = sb.toString();
        if (!cjk.isEmpty()) {
            for (int i = 0; i < cjk.length(); i++) {
                tokens.add(String.valueOf(cjk.charAt(i)));
                if (i + 1 < cjk.length()) {
                    tokens.add(cjk.substring(i, i + 2));
                }
            }
            sb.setLength(0);
        }
    }

    private record Scored(String content, double score) {
    }
}
