-- Semantic search: one embedding vector per chunk, produced by a local Ollama embedding model.
-- Stored as a plain REAL[] (no extension needed); similarity is computed in Java over the
-- permission-filtered candidate chunks. Production upgrade path: pgvector's vector(n) column
-- with an HNSW index, keeping the same table shape.
CREATE TABLE chunk_embedding (
    chunk_id   BIGINT       PRIMARY KEY REFERENCES knowledge_chunk (id) ON DELETE CASCADE,
    model      VARCHAR(100) NOT NULL,          -- vectors from different models are not comparable
    dimensions INT          NOT NULL,
    embedding  REAL[]       NOT NULL,          -- L2-normalised, so cosine similarity = dot product
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_chunk_embedding_dimensions CHECK (dimensions > 0 AND array_length(embedding, 1) = dimensions)
);
CREATE INDEX idx_chunk_embedding_model ON chunk_embedding (model);
