package com.example.hybridrag.model;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 教学版本地嵌入：词元哈希到固定维度桶并归一化，确定性、无外部依赖、可离线复现。
 * 仅用于演示检索链路与 RRF 取舍，语义质量不可替代真实嵌入模型；
 * 生产替换点见 README 第 7 节。
 */
@Component
public class HashEmbeddingClient implements EmbeddingClient {

    private final int dimension;

    public HashEmbeddingClient(com.example.hybridrag.config.AppProperties properties) {
        this.dimension = properties.embedding().dimension();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<Double> embed(String text) {
        double[] vector = new double[dimension];
        for (String token : tokenize(text)) {
            int bucket = Math.floorMod(hash(token), dimension);
            vector[bucket] += 1.0;
        }
        double norm = 0.0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        List<Double> result = new ArrayList<>(dimension);
        if (norm == 0.0) {
            for (int i = 0; i < dimension; i++) {
                result.add(0.0);
            }
            return result;
        }
        for (double v : vector) {
            result.add(v / norm);
        }
        return result;
    }

    /**
     * 词元化：英文/数字按空白与标点切分并转小写；中文保留单字与相邻双字，
     * 让专有编号、型号类词在向量空间有基础区分度（教学近似）。
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (isCjk(c)) {
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

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private int hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return (digest[0] & 0xFF) | ((digest[1] & 0xFF) << 8);
        } catch (NoSuchAlgorithmException e) {
            return token.hashCode();
        }
    }
}
