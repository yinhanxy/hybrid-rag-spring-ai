package com.example.hybridrag.model;

import java.util.List;

/**
 * 嵌入模型接口。默认本地确定性实现（无外部依赖、可离线复现）；
 * 生产可替换为 OpenAI 兼容 / 本地模型端点实现，配置见 README 第 7 节。
 */
public interface EmbeddingClient {

    /** 把一段文本映射为固定维度向量，元素已归一化便于余弦相似度计算。 */
    List<Double> embed(String text);

    /** 向量维度，与 application.yml 中 app.embedding.dimension 对齐。 */
    int dimension();
}
