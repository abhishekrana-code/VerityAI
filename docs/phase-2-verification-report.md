# Phase 2 Verification Report

**Scope:** Features 4–6 — semantic search, multi-document research chat, and
page-level citations with claim verification.

**Status:** Complete and verified. All automated suites green, zero skipped
tests, live end-to-end pass executed against the real stack (PostgreSQL +
pgvector, local ONNX embeddings, Groq `openai/gpt-oss-20b`).

---

## 1. Environment

| Component | Version / detail |
|---|---|
| OS | Windows 11 (dev) |
| Java | 21 |
| Spring Boot | 3.5 |
| PostgreSQL | 17 with pgvector 0.8.7 (`pgvector/pgvector:0.8.7-pg17`) |
| Embeddings | `Xenova/all-MiniLM-L6-v2` (quantized ONNX, 384-dim), SHA-256 pinned |
| LLM | Groq `openai/gpt-oss-20b` |
| Frontend | React + Vite + Tailwind, Vitest/jsdom |

---

## 2. What was delivered

### Feature 4 — Semantic search
- `V5__semantic_search_and_verification.sql`: `vector` extension, 384-dim
  embedding column, HNSW cosine index, claim-verification table.
- Local ONNX embedding service: first-run download, SHA-256 verification,
  local cache, offline reuse, graceful keyword-only fallback.
- `EmbeddingIndexService` (pipeline writes + startup backfill) and
  `EmbeddingBackfillRunner` (bounded, asynchronous, per-start cap).
- `RetrievalService`: full-text + cosine candidates fused with Reciprocal
  Rank Fusion (k=60), normalized ranks, honest `strategy` reporting
  (`keyword` / `semantic` / `hybrid`) and per-hit `keywordMatch` /
  `semanticMatch` / `similarity`.
- `POST /api/v1/search` with optional document scope, server-side result cap,
  ownership enforcement, 400/401/403 contracts.

### Feature 5 — Multi-document chat
- Conversations hold a document set; `PATCH /conversations/{id}` adds/removes
  documents at any time (context chips in the UI).
- Per-question optional `documentIds` narrows evidence further; foreign ids
  are rejected with 403.
- Evidence is strictly scoped to the conversation; a better-matching document
  outside the conversation is never cited.
- Multi-document prompt instructs explicit comparison and separate citations
  for disagreements; evidence is labeled as quoted data, never instructions.

### Feature 6 — Page-level citations & verification
- Citation chips carry document + page targets; clicking opens the PDF preview
  at the cited page.
- `GET/POST /conversations/{id}/messages/{messageId}/verify`:
  - Deterministic layer: claim segmentation, `[Sn]` extraction (ASCII + CJK
    brackets), invalid-reference detection, coverage, `UNSUPPORTED` for
    uncited claims.
  - One bounded, rate-limited model call classifies cited claims as
    `SUPPORTED` / `PARTIALLY_SUPPORTED` / `UNSUPPORTED` / `CONFLICTING`
    (nullable = not classified → `PARTIAL`).
  - Honest failure: model failure with nothing classified → `FAILED`, nothing
    stored, message stays `NOT_REQUESTED`; re-verify replaces rows atomically.
  - UI: coverage bar, support badges, invalid-reference warnings, and an
    explicit "not calibrated probabilities" disclaimer.

### Infrastructure / docs
- `docker-compose.yml` and all Testcontainers classes pinned to
  `pgvector/pgvector:0.8.7-pg17`.
- `docs/api.md` (full endpoint reference), `docs/testing.md` (suite guide),
  README feature/API/testing updates, `docs/deployment.md` upgrade note
  (alpine uid 70 → pgvector uid 999 volume chown), `.env.example` Phase 2 vars.
- `.gitignore` excludes `backup/` (database dumps contain user data).

---

## 3. Database migration evidence

- Flyway V5 applied on first start: `5 | semantic search and verification`.
- `vector` extension installed; `document_chunks.embedding` exists;
  `message_claim_verifications` table created.
- Startup backfill embedded **426 / 426** pre-existing chunks.
- Pre-migration dump taken before the image switch (not committed).

---

## 4. Automated tests

### Backend — 106 tests, 0 failures, 0 errors, 0 skipped

| Class | Tests | Covers |
|---|---:|---|
| `AuthIntegrationTest` | 9 | Register/login/logout, session, CSRF, 401 |
| `DocumentIntelligenceIntegrationTest` | 18 | Filters/sort, tags, collections, processing history, summaries, bulk delete, file access |
| `RateLimitIntegrationTest` | 3 | 429 + `Retry-After` |
| `DocumentChunkRepositoryTest` | 6 | FTS relevance, scoping, soft-delete/user isolation |
| `RateLimitServiceTest` | 8 | Per-IP/per-account/per-user buckets |
| `ChatServiceTest` | 6 | Evidence → answer → citation persistence |
| `GroqLlmServiceTest` | 13 | `[Sn]` extraction (incl. CJK), multi-doc prompt, injection hardening |
| `NoopEmbeddingServiceTest` | 2 | Provider-off contract |
| `OnnxEmbeddingServiceTest` | 5 | Real model: download/checksum, pooling, normalization |
| `ClaimVerificationServiceTest` | 13 | Segmentation, refs, coverage, class mapping, JSON parsing |
| `SemanticSearchIntegrationTest` | 9 | Paraphrase → `semantic`; overlap → `hybrid`; unindexed → `keyword`; 400/401/403; isolation; limit cap |
| `KeywordOnlySearchTest` | 3 | `provider=off` degradation, `model: null`, honest empty results |
| `ConversationRagIntegrationTest` | 11 | Citation integrity (evidence scoping, foreign docs 403) + verification endpoints (NOT_REQUESTED, deterministic, model round-trip, invalid `[S7]`, FAILED stores nothing, re-verify, 404 authz, USER 400) |

Test process notes (reported honestly):

- The three new integration classes (23 tests) were added in this verification
  pass because `SearchService`/`RetrievalService` had **no** coverage.
- First run of the new classes: 4 failures — 1 × CSRF-before-auth ordering
  (expected 401, got 403), 3 × test content (`[S1]` placed after a sentence
  boundary, so the cited claim was not the one classified). All four were test
  defects, fixed; retry: **20/20 + 3/3 green**.
- No tests are skipped; semantic tests run against the real cached model.
- The Groq LLM is never called from tests (`@MockitoBean LlmService`).
- The **first final full run** exposed one intermittent pre-existing failure
  (`bulkDeleteRemovesOwnedDocumentsAndReportsSkippedIds`, expects 0 documents
  after a bulk delete, saw 1). Root cause and fix are defect #5 in section 5.
  The affected class was re-run green (18/18) and the full suite re-run after
  the fix.

### Frontend gate

| Check | Result |
|---|---|
| `npm run typecheck` | 0 errors |
| `npm run lint` | 0 errors, 0 warnings |
| `npm test` | 12 / 12 passed |
| `npm run build` | success (pre-existing chunk-size warning only) |

---

## 5. Defects found and fixed during verification

1. **RRF fusion could return up to 2× the requested limit.** The union of the
   keyword and semantic branches (each capped at `limit`) was never trimmed.
   Fixed by trimming the fused ranking to `effectiveLimit`; pinned by
   `resultLimitIsCappedByServerMaximum`.
2. **Claim verification under-budgeted reasoning models.** The completion
   budget was `300 + 70 × claims`; `gpt-oss-20b` spends several hundred tokens
   on hidden reasoning, so the JSON was never emitted (empty content, live run
   reported `PARTIAL` with cited claims "Not classified"). Fixed to
   `min(maxTokens, 1200 + 250 × claims)`. Live re-run then returned
   `COMPLETED` with real `PARTIALLY_SUPPORTED` classifications.
3. **Non-existent Docker tag.** `pgvector/pgvector:pgvector17` does not exist;
   all five references corrected to the pinned `0.8.7-pg17`.
4. **Docs/config drift.** `docs/api.md` named `EMBEDDINGS_MODEL_DIR` (actual
   `EMBEDDINGS_CACHE_DIR`); `.env.example` lacked retrieval/search vars. Both
   corrected.
5. **Soft-deleted document could be resurrected by the processing thread**
   (exposed by the final full run as an intermittent
   `bulkDeleteRemovesOwnedDocumentsAndReportsSkippedIds` failure: one document
   remained after a bulk delete). The asynchronous processing thread loaded the
   document, then wrote the whole stale entity back with `save()`; if the user
   deleted the document in between, the stale `deletedAt = null` overwrote the
   soft delete and the document reappeared. Fixed with targeted, conditional
   updates (`UPDATE … WHERE id = :id AND deletedAt IS NULL`) for the
   PROCESSING and READY transitions, and processing now aborts early when the
   document was deleted. This is a pre-existing race, not a Phase 2 regression,
   but it was found and fixed during this verification.

---

## 6. Live end-to-end verification (real stack, real model calls)

Performed in the in-app browser against `http://localhost:5173` with the test
account, backend on `:8080`, Vite dev server on `:5173`.

| Step | Result |
|---|---|
| Login | success → `/dashboard` |
| `POST /search` paraphrase ("ocean warming … coral reefs") | `strategy: "hybrid"`, `model: "all-MiniLM-L6-v2-quantized"`, semantic-only hits returned (`keywordMatch: false`, `similarity: 0.449`) |
| `POST /search` keyword ("pass it on") | `strategy: "hybrid"`, mixed keyword/semantic hits, similarity only on semantic matches |
| Create conversation over 2 PDFs | 2 context chips, 0 messages |
| Multi-doc question | live Groq answer citing `[S3]` (AllSem23 p.62), `[S4]` (p.42), `[S6]`, `[S8]`; evidence status "Sufficient evidence" |
| Click citation `[S4]` | PDF preview modal opened `AllSem23.pdf`, `#page=42`, header "42 / 81" |
| Remove context chip | AllSem23 removed, chip count 2 → 1 |
| Add context chip | picker → AllSem23 re-added, both chips restored |
| Verify claims | deterministic unsupported claims, invalid `[S7]`/`[S8]` warnings, coverage 0.222, support badges, "not calibrated probabilities" disclaimer |
| Verification after budget fix | `COMPLETED`, cited claims `PARTIALLY_SUPPORTED`, model `openai/gpt-oss-20b` |
| Console | 0 errors, 0 warnings |
| Network | 72 requests, 0 failures |

---

## 7. Honest limitations / known gaps

- **No dedicated search page in the UI.** Feature 4 is complete at the API
  level (`POST /search`); the frontend has no search screen yet. Verified via
  the live API from the browser session.
- **Sidebar navigation** still lists Overview, Documents, Research workspace,
  Settings only. Knowledge Graph, Verification, Reports, and History pages are
  Phase 3 scope and are not present (no dead links added).
- **Verification quality depends on the model.** `supportClass` is an AI
  grounding assessment, not a probability; the UI states this explicitly.
- **Semantic search depends on the local model cache.** If the model cannot be
  downloaded/verified, search degrades honestly to keyword-only
  (`strategy: "keyword"`, `model: null`).
- **Groq rate limits** apply to chat/verification; 429 responses are surfaced
  with `Retry-After` and the verification endpoint stores nothing on failure.

---

## 8. Reproduce

```powershell
# Database (pgvector image)
docker compose up -d postgres

# Backend: 106 tests (Docker required)
cd backend
.\mvnw.cmd -B --no-transfer-progress test

# Frontend gate
cd ..\frontend
npm run typecheck; npm run lint; npm test; npm run build
```

Expected: backend `Tests run: 106, Failures: 0, Errors: 0, Skipped: 0`;
frontend 12/12 and a clean build.
