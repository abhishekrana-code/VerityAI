-- =============================================================================
-- V7: Contradiction detection (Feature 8)
-- =============================================================================
-- A contradiction is a *claim pair* that cannot both be true at the same time
-- for the same subject and scope. The five approved classes are enforced by a
-- CHECK constraint so a future code path cannot invent a new label silently.
--
-- Claims are generated deterministically from document chunks (bounded), then
-- one bounded model call classifies candidate pairs. Rows store both claims,
-- both documents/chunks/pages, the class, a LOW | MEDIUM | HIGH severity (not
-- a probability), the model that classified the pair, and when it ran.

CREATE TABLE IF NOT EXISTS contradictions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_a_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    document_b_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_a_id UUID REFERENCES document_chunks(id) ON DELETE SET NULL,
    chunk_b_id UUID REFERENCES document_chunks(id) ON DELETE SET NULL,
    claim_a TEXT NOT NULL,
    claim_b TEXT NOT NULL,
    page_a INT,
    page_b INT,
    subject_entity VARCHAR(255),
    contradiction_type VARCHAR(40) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    explanation VARCHAR(600),
    model VARCHAR(120),
    pair_key VARCHAR(64) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_contradiction_pair UNIQUE (user_id, pair_key),
    CONSTRAINT chk_contradiction_type CHECK (contradiction_type IN (
        'NUMERIC_CONFLICT',
        'TEMPORAL_CONFLICT',
        'ATTRIBUTE_CONFLICT',
        'CAUSAL_CONFLICT',
        'POLARITY_CONFLICT'
    )),
    CONSTRAINT chk_contradiction_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE INDEX IF NOT EXISTS idx_contradictions_user_detected
    ON contradictions(user_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_contradictions_user_type
    ON contradictions(user_id, contradiction_type);
CREATE INDEX IF NOT EXISTS idx_contradictions_document_a
    ON contradictions(document_a_id);
CREATE INDEX IF NOT EXISTS idx_contradictions_document_b
    ON contradictions(document_b_id);
