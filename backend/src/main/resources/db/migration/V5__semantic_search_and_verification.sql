-- =============================================================================
-- V5: Semantic search (pgvector) + claim-level citation verification
-- =============================================================================
-- Backward compatible: the embedding column is nullable, so every existing row
-- keeps working. Chunks without an embedding are simply skipped by the vector
-- branch of hybrid search and still matched by full-text search.

-- pgvector extension (bundled in pgvector/pgvector:* images; plain Postgres
-- images fail here — docker-compose.yml and Testcontainers use the pgvector image)
CREATE EXTENSION IF NOT EXISTS vector;

-- 384-dim embeddings (all-MiniLM-L6-v2). NULL = not embedded yet.
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding vector(384);

-- Partial HNSW index: only embedded chunks are indexed; NULLs are excluded by
-- the predicate, so un-indexed documents never break or slow the index.
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

-- =============================================================================
-- CLAIM VERIFICATION (Phase 2, Feature 6)
-- =============================================================================
-- One row per claim of an assistant message that was checked against its cited
-- evidence. support_class is an AI assessment of grounding, never a calibrated
-- probability: SUPPORTED | PARTIALLY_SUPPORTED | UNSUPPORTED | CONFLICTING.
-- NULL support_class = claim analyzed but not yet classified by the model
-- (partial verification); the row still records the claim and its citations.
CREATE TABLE IF NOT EXISTS message_claim_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    claim_index INT NOT NULL,
    sentence_index INT NOT NULL,
    claim_text TEXT NOT NULL,
    support_class VARCHAR(32),
    explanation TEXT,
    citation_indexes JSONB NOT NULL DEFAULT '[]'::jsonb,
    model VARCHAR(100),
    verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_message_claim UNIQUE (message_id, claim_index)
);

CREATE INDEX IF NOT EXISTS idx_message_claim_verifications_message
    ON message_claim_verifications(message_id);
