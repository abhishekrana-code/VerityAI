-- =============================================================================
-- V6: Knowledge graph (PostgreSQL only) + report/history read models
-- =============================================================================
-- The graph is stored entirely in PostgreSQL: no external graph database.
-- Extraction is deterministic (pattern-based entity candidates, merged with
-- the LLM-extracted ENTITIES summary section when it exists). Relations are
-- co-occurrence links inside the same chunk -- they are evidence pointers,
-- never asserted semantic facts. The API/UI label them accordingly.

-- -----------------------------------------------------------------------------
-- Per-document graph lifecycle (mirrors summary_status, so the UI can show an
-- honest NONE | PENDING | READY | FAILED state instead of an empty graph).
-- -----------------------------------------------------------------------------
ALTER TABLE documents ADD COLUMN IF NOT EXISTS knowledge_status VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS knowledge_error VARCHAR(300);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS knowledge_generated_at TIMESTAMPTZ;

-- -----------------------------------------------------------------------------
-- Entities
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_entities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    mention_count INT NOT NULL DEFAULT 1,
    first_page INT,
    evidence_excerpt VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_knowledge_entity_document UNIQUE (document_id, normalized_name, entity_type)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_entities_user
    ON knowledge_entities(user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_entities_document
    ON knowledge_entities(document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_entities_user_name
    ON knowledge_entities(user_id, normalized_name);

-- -----------------------------------------------------------------------------
-- Relations: undirected co-occurrence edges. source_entity_id < target_entity_id
-- is enforced so the same pair can never be stored twice per relation type.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_relations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    source_entity_id UUID NOT NULL REFERENCES knowledge_entities(id) ON DELETE CASCADE,
    target_entity_id UUID NOT NULL REFERENCES knowledge_entities(id) ON DELETE CASCADE,
    relation_type VARCHAR(40) NOT NULL DEFAULT 'CO_OCCURS',
    weight INT NOT NULL DEFAULT 1,
    first_page INT,
    evidence_excerpt VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_knowledge_relation UNIQUE (document_id, source_entity_id, target_entity_id, relation_type),
    CONSTRAINT chk_knowledge_relation_order CHECK (source_entity_id < target_entity_id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_relations_user
    ON knowledge_relations(user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_relations_document
    ON knowledge_relations(document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_relations_source
    ON knowledge_relations(source_entity_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_relations_target
    ON knowledge_relations(target_entity_id);

-- -----------------------------------------------------------------------------
-- Read-model indexes for the report/history/verification pages
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_documents_user_ready
    ON documents(user_id, status, updated_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_processing_events_created
    ON document_processing_events(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_role_created
    ON messages(conversation_id, role, created_at DESC);
