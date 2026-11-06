-- V4: Document intelligence (Phase 1)
-- Additive only: persistent processing history, AI summaries, tags, collections.
-- Existing tables/records are untouched; all new columns have safe defaults.

-- ---------------------------------------------------------------------------
-- 1. Persistent document processing history (survives restarts)
--    Append-only event log: one row per stage transition per attempt.
--    Current state  = latest row for the document.
--    History        = all rows for the document.
--    Retry          = new attempt number.
-- ---------------------------------------------------------------------------
CREATE TABLE document_processing_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    attempt       INT  NOT NULL DEFAULT 1,
    stage         VARCHAR(30) NOT NULL,
    -- UPLOADED | QUEUED | EXTRACTING | CHUNKING | INDEXING | COMPLETED | FAILED
    error_code    VARCHAR(50),
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_processing_events_document
    ON document_processing_events(document_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. AI summaries (persisted, regenerable)
--    content is structured JSONB produced by the LLM - never fabricated here.
--    source distinguishes extracted facts from generated interpretations.
-- ---------------------------------------------------------------------------
CREATE TABLE document_summaries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    kind         VARCHAR(40) NOT NULL,
    -- EXECUTIVE_SUMMARY | KEY_FINDINGS | ENTITIES | DATES_AND_STATS | TOPICS | SUGGESTED_QUESTIONS
    source       VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    -- EXTRACTED (facts pulled from text) | GENERATED (interpretive prose)
    content      JSONB NOT NULL,
    model        VARCHAR(100),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, kind)
);

CREATE INDEX idx_document_summaries_document
    ON document_summaries(document_id);

-- Summary lifecycle on the document itself (queryable from the list API)
ALTER TABLE documents
    ADD COLUMN summary_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN summary_error  VARCHAR(300);

-- NONE | PENDING | READY | FAILED

-- ---------------------------------------------------------------------------
-- 3. Custom tags (per user)
-- ---------------------------------------------------------------------------
CREATE TABLE tags (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(50) NOT NULL,
    color      VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, name)
);

CREATE INDEX idx_tags_user ON tags(user_id);

CREATE TABLE document_tags (
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (document_id, tag_id)
);

CREATE INDEX idx_document_tags_tag ON document_tags(tag_id);

-- ---------------------------------------------------------------------------
-- 4. Document collections (per user)
-- ---------------------------------------------------------------------------
CREATE TABLE collections (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, name)
);

CREATE INDEX idx_collections_user ON collections(user_id, created_at DESC);

CREATE TABLE collection_documents (
    collection_id UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    document_id   UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (collection_id, document_id)
);

CREATE INDEX idx_collection_documents_document ON collection_documents(document_id);

-- Reuse the V1 updated_at trigger function for collections
CREATE TRIGGER update_collections_updated_at
    BEFORE UPDATE ON collections
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
