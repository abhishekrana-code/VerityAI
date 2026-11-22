-- VerityAI product metrics — read-only queries over the existing schema.
-- No tracking code, no extra tables: run these against the app database with
-- `psql "$DATABASE_URL" -f docs/metrics.sql`, or copy one query at a time.
--
-- Conventions used here:
--   * users.deleted_at IS NULL  → the account still exists (account deletion is a
--     hard delete, so such rows only exist for accounts deleted before Phase 5).
--   * documents.deleted_at IS NULL → not removed by the user.
--
-- Table/column names verified against
-- backend/src/main/resources/db/migration/V1 … V9.

-- ---------------------------------------------------------------------------
-- 1. Signups per week
-- ---------------------------------------------------------------------------
SELECT date_trunc('week', created_at)::date AS week,
       COUNT(*)                             AS signups
FROM users
WHERE deleted_at IS NULL
GROUP BY 1
ORDER BY 1;

-- ---------------------------------------------------------------------------
-- 2. Share of users who uploaded at least one document
-- ---------------------------------------------------------------------------
WITH per_user AS (
    SELECT u.id,
           COUNT(d.id) AS documents
    FROM users u
    LEFT JOIN documents d
        ON d.user_id = u.id AND d.deleted_at IS NULL
    WHERE u.deleted_at IS NULL
    GROUP BY u.id
)
SELECT COUNT(*)                                                        AS users,
       COUNT(*) FILTER (WHERE documents > 0)                           AS uploaders,
       ROUND(100.0 * COUNT(*) FILTER (WHERE documents > 0) / COUNT(*), 1) AS uploaders_pct
FROM per_user;

-- ---------------------------------------------------------------------------
-- 3. Share of uploaders who asked at least one question
-- ---------------------------------------------------------------------------
WITH per_user AS (
    SELECT u.id,
           EXISTS (
               SELECT 1 FROM documents d
               WHERE d.user_id = u.id AND d.deleted_at IS NULL
           ) AS uploaded,
           EXISTS (
               SELECT 1
               FROM conversations c
               JOIN messages m ON m.conversation_id = c.id
               WHERE c.user_id = u.id AND m.role = 'USER'
           ) AS asked
    FROM users u
    WHERE u.deleted_at IS NULL
)
SELECT COUNT(*) FILTER (WHERE uploaded)                            AS uploaders,
       COUNT(*) FILTER (WHERE uploaded AND asked)                   AS uploaders_who_asked,
       ROUND(100.0 * COUNT(*) FILTER (WHERE uploaded AND asked)
             / NULLIF(COUNT(*) FILTER (WHERE uploaded), 0), 1)      AS asked_pct
FROM per_user;

-- ---------------------------------------------------------------------------
-- 4. Median time from a document becoming READY to the owner's first question
-- ---------------------------------------------------------------------------
-- "Ready" = the COMPLETED processing event. A document-scoped conversation link
-- is not required: a user asking in any conversation counts, which is the
-- behaviour the product actually has. Pairs where the question came first are
-- excluded rather than counted as zero.
WITH ready AS (
    SELECT d.id AS document_id,
           d.user_id,
           MIN(e.created_at) AS ready_at
    FROM documents d
    JOIN document_processing_events e
        ON e.document_id = d.id AND e.stage = 'COMPLETED'
    WHERE d.deleted_at IS NULL
    GROUP BY d.id, d.user_id
),
first_question AS (
    SELECT c.user_id,
           MIN(m.created_at) AS asked_at
    FROM conversations c
    JOIN messages m ON m.conversation_id = c.id AND m.role = 'USER'
    GROUP BY c.user_id
)
SELECT ROUND(
           EXTRACT(EPOCH FROM PERCENTILE_CONT(0.5) WITHIN GROUP (
               ORDER BY fq.asked_at - r.ready_at)) / 60.0,
           1) AS median_minutes_ready_to_first_question
FROM ready r
JOIN first_question fq ON fq.user_id = r.user_id AND fq.asked_at >= r.ready_at;

-- ---------------------------------------------------------------------------
-- 5. Share of assistant answers that were verified
-- ---------------------------------------------------------------------------
-- A message counts as verified when at least one claim row exists for it.
SELECT COUNT(*)                                                        AS answers,
       COUNT(*) FILTER (WHERE v.verified)                              AS verified,
       ROUND(100.0 * COUNT(*) FILTER (WHERE v.verified) / COUNT(*), 1) AS verified_pct
FROM messages m
LEFT JOIN (
    SELECT DISTINCT message_id, TRUE AS verified
    FROM message_claim_verifications
) v ON v.message_id = m.id
WHERE m.role = 'ASSISTANT';

-- ---------------------------------------------------------------------------
-- 6. Processing failure rate by cause
-- ---------------------------------------------------------------------------
-- Grouped by the failed document's error_code (the enum-like short code the
-- pipeline stores), counting each document once.
SELECT COALESCE(d.error_code, 'unknown')                AS cause,
       COUNT(*)                                          AS failed_documents,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS share_pct
FROM documents d
WHERE d.status = 'FAILED'
  AND d.deleted_at IS NULL
GROUP BY 1
ORDER BY failed_documents DESC;
