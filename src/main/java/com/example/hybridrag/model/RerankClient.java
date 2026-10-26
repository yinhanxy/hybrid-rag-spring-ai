package com.example.hybridrag.model;

import com.example.hybridrag.retrieval.dto.ScoredChunk;

import java.util.List;

/**
 * 重排接口。默认直通实现按 RRF 顺序返回；
 * 生产可替换为外部重排模型端点，在融合之后、取 topK 之前二次筛选。
 */
public interface RerankClient {

    /** 是否启用重排；关闭时检索链路直接取 RRF 前 topK。 */
    boolean isEnabled();

    /** 对融合候选按相关性重新排序，返回 topK 条。 */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK);
}
