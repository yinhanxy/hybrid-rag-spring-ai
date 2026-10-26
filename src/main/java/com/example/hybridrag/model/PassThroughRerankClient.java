package com.example.hybridrag.model;

import com.example.hybridrag.retrieval.dto.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认重排：按 RRF 顺序直通，不做二次打分。
 * 生产替换为外部重排模型端点时，实现本接口并把 isEnabled() 返回 true。
 */
@Component
public class PassThroughRerankClient implements RerankClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        List<ScoredChunk> result = candidates.stream().limit(topK).toList();
        List<ScoredChunk> numbered = new java.util.ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            numbered.add(result.get(i).withRank(i + 1));
        }
        return numbered;
    }
}
