-- H2 内存库演示 schema。生产 PostgreSQL/pgvector 草案见 db/postgres-pgvector.sql。

CREATE TABLE IF NOT EXISTS documents (
    id          VARCHAR(36) PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    source      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chunks (
    id           VARCHAR(36) PRIMARY KEY,
    document_id  VARCHAR(36) NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    content      CLOB NOT NULL,
    chunk_index  INT NOT NULL,
    metadata     CLOB,
    embedding    CLOB
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(document_id);
