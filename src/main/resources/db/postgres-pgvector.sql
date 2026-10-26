-- =============================================================
-- 生产形态草案：PostgreSQL 16 + pgvector + 全文检索
-- 本工程教学版使用 H2 内存库与应用层召回；此文件仅作迁移对照，
-- 教学版运行不依赖 PostgreSQL。索引参数为起步值，需按语料规模压测调优。
-- =============================================================

-- 扩展：向量 + 全文
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS documents (
    id          UUID PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    source      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chunks (
    id           UUID PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    content      TEXT NOT NULL,
    chunk_index  INT NOT NULL,
    metadata     JSONB,
    embedding    vector(1024)
);

-- 向量路：HNSW + 余弦距离，ef_search 按召回时延调优
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw
    ON chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 全文路：tsvector 列 + GIN 索引；中文需接入 zhparser 或外部分词器
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;

CREATE INDEX IF NOT EXISTS chunks_tsv_gin
    ON chunks USING gin (content_tsv);

-- 向量路候选：SELECT id, content, 1 - (embedding <=> :query_vector) AS score
--   FROM chunks ORDER BY embedding <=> :query_vector LIMIT :candidateK;
-- 全文路候选：SELECT id, content, ts_rank(content_tsv, query) AS score
--   FROM chunks WHERE content_tsv @@ plainto_tsquery('simple', :query)
--   ORDER BY score DESC LIMIT :candidateK;
-- 两路结果进入应用层 RRF 融合，再按 README 第 7 节接入重排模型。
