package com.example.hybridrag.retrieval;

import com.example.hybridrag.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）倒数排名融合。
 * 标准：score(d) = Σ 1/(K + rank_i(d))；加权：score(d) = Σ w_i/(K + rank_i(d))。
 * 只用排名，不要求两路原始分数可比；权重数量与路数不一致时回退等权。
 */
@Service
public class RankFusionService {

    private final int k;

    public RankFusionService(AppProperties properties) {
        this.k = properties.retrieval().rrfK();
    }

    /** 标准等权 RRF。 */
    public List<Fused> fuse(List<List<String>> resultLists, int topK) {
        return fuseWithWeights(resultLists, null, topK);
    }

    /**
     * 加权 RRF。weights 为 null 或长度与路数不一致时，按等权处理。
     */
    public List<Fused> fuseWithWeights(List<List<String>> resultLists, List<Double> weights, int topK) {
        boolean weighted = weights != null && weights.size() == resultLists.size();
        Map<String, Double> scores = new HashMap<>();

        for (int i = 0; i < resultLists.size(); i++) {
            List<String> results = resultLists.get(i);
            double weight = weighted ? weights.get(i) : 1.0;
            for (int rank = 0; rank < results.size(); rank++) {
                String doc = results.get(rank);
                double contribution = weight / (k + rank + 1);
                scores.merge(doc, contribution, Double::sum);
            }
        }

        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return entries.stream()
                .limit(topK)
                .map(e -> new Fused(e.getKey(), e.getValue()))
                .toList();
    }

    /** 融合结果：内容与 RRF 分数。 */
    public record Fused(String content, double score) {
    }
}
