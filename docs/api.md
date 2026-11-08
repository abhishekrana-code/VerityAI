# VerityAI API Reference

All endpoints live under `/api/v1` and return JSON. Authentication uses a
`SESSION` cookie; every state-changing request (everything except `/auth/**`)
also needs the `XSRF-TOKEN` cookie echoed back as an `X-XSRF-TOKEN` header.
Fetch a token first from `GET /auth/csrf`.

**Conventions**

- Errors share one envelope: `{ code, message, fieldErrors?, requestId }`.
  429 responses additionally carry a `Retry-After` header.
- List endpoints are paginated: request `?page=&size=` (Spring defaults),
  responses carry `{ content, page, size, totalElements, totalPages, first, last }`.
- All data is scoped to the authenticated user. Cross-user access returns
  404 (existence is not leaked) or 403 where a request references another
  user's resource ids explicitly.

---

## Auth — `/auth`

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` | `{ email, password, name? }` → creates user, sets session cookie |
| POST | `/auth/login` | `{ email, password }` → sets session cookie. Rate-limited per client IP **and** per target account (429 + `Retry-After`) |
| POST | `/auth/logout` | Revokes the session |
| GET | `/auth/me` | Current user (`401` when anonymous) |
| GET | `/auth/csrf` | `{ token }` + `XSRF-TOKEN` cookie |
| PATCH | `/auth/users/me` | `{ name?, email? }` profile update |
| POST | `/auth/users/me/password` | `{ currentPassword, newPassword }` |

---

## Documents — `/documents`

| Method | Path | Notes |
|---|---|---|
| POST | `/documents` | multipart `file` (PDF only). Enqueued for processing; returns the document with `status=UPLOADED/PROCESSING` |
| GET | `/documents` | Paginated list. Filters: `status`, `q`, `tagId`, `collectionId`, `page`, `size` |
| GET | `/documents/{id}` | Detail incl. `sha256`, `pageCount`, tags, collection |
| GET | `/documents/{id}/status` | Processing state (`UPLOADED`, `PROCESSING`, `READY`, `FAILED`) + `errorMessage` |
| GET | `/documents/{id}/processing` | Stage-by-stage processing history (extract → chunk → summarize → embed) |
| GET | `/documents/{id}/summaries` | Generated summary sections (summary, findings, entities, items, topics, questions) |
| POST | `/documents/{id}/summaries/regenerate` | Re-runs summarization (rate-limited AI request) |
| GET | `/documents/{id}/file` | Streams the stored PDF (ownership-checked) |
| POST | `/documents/{id}/tags/{tagId}` / DELETE | Attach / detach a tag |
| POST | `/documents/{id}/retry` | Re-run failed processing |
| DELETE | `/documents/{id}` | Delete document + stored file |
| POST | `/documents/bulk-delete` | `{ ids: [...] }` → deletes owned docs only, reports `deletedIds`/`skippedIds` |

Processing is asynchronous; poll `/documents/{id}/status` or the SSE-free
`/processing` history. Chunks are embedded with the local ONNX model while the
document indexes — see *Semantic search* below.

---

## Search — `/search` (Phase 2)

| Method | Path | Notes |
|---|---|---|
| POST | `/search` | Hybrid semantic + keyword search over **your** documents |

```jsonc
// Request
{
  "query": "customer churn drivers",     // required, ≤ 2000 chars
  "documentIds": ["…"],                  // optional scope, ≤ 50 ids (must all be yours → 403 otherwise)
  "limit": 20                            // optional, ≥ 1, capped by SEARCH_MAX_RESULTS (default 30)
}
```

```jsonc
// Response
{
  "query": "customer churn drivers",
  "strategy": "hybrid",                  // "keyword" | "semantic" | "hybrid"
  "model": "Xenova/all-MiniLM-L6-v2",    // null when the semantic branch did not run
  "count": 5,
  "results": [{
    "chunkId": "…", "documentId": "…", "documentName": "report.pdf",
    "pageNumber": 7, "chunkIndex": 3,
    "content": "full chunk text", "excerpt": "short snippet",
    "rank": 0.93,                        // relevance in [0,1] after RRF fusion
    "similarity": 0.81,                  // cosine similarity; null for keyword-only hits
    "keywordMatch": true,
    "semanticMatch": true
  }]
}
```

Behavior guarantees:

- **Never blocks on embeddings.** If the embedding model is unavailable (first
  run without network, checksum failure), search degrades to pure keyword (FTS)
  and reports `strategy: "keyword"`, `model: null` — results still return.
- Not-yet-indexed chunks are simply absent from the semantic branch; keyword
  matches still appear (`keywordMatch: true`, `similarity: null`).
- Ranking: keyword and semantic candidates are fused with Reciprocal Rank
  Fusion (k=60), normalized to [0,1]; keyword-only results keep raw `ts_rank`.
- Re-indexing is safe: embeddings are idempotent upserts keyed by chunk id.

---

## Conversations — `/conversations`

| Method | Path | Notes |
|---|---|---|
| GET | `/conversations` | Paginated list (`q`, `page`, `size`) |
| POST | `/conversations` | `{ title, documentIds? }` — create a conversation over selected documents |
| GET | `/conversations/{id}` | Detail incl. `documents: [{ id, originalFilename }]` |
| PATCH | `/conversations/{id}` | `{ title?, documentIds?, archived? }` — add/remove documents from context at any time |
| DELETE | `/conversations/{id}` | Delete conversation (cascades messages/citations) |
| GET | `/conversations/{id}/messages` | Paginated message history (`page`, `size`), each message with its `citations[]` |
| POST | `/conversations/{id}/messages` | Ask a question — RAG over the conversation's documents (rate-limited AI request) |

**Asking a question** — `POST /conversations/{id}/messages`:

```jsonc
// Request
{ "question": "How did revenue change across the reports?", "documentIds": ["…"] }
// documentIds is optional; omit to use the conversation's context documents.
```

Response is the assistant `Message` with citations attached:

```jsonc
{
  "id": "…", "role": "ASSISTANT", "content": "… [S1] … [S2]",
  "status": "COMPLETED", "model": "gpt-oss-20b",
  "promptTokens": 1200, "completionTokens": 300, "latencyMs": 2100,
  "evidenceStatus": "SUFFICIENT",          // SUFFICIENT | INSUFFICIENT | NONE
  "createdAt": "…",
  "citations": [{
    "chunkId": "…", "documentId": "…", "documentName": "report.pdf",
    "pageNumber": 7, "excerpt": "…", "rank": 0.93, "citationIndex": 0
  }]
}
```

Citations are only attached for text the model actually cited (`[Sn]` →
`citations[n-1]`). When the conversation spans **≥2 documents**, the prompt
instructs the model to compare sources explicitly and to report disagreements
with both citations instead of blending them. Evidence text is labeled as
quoted data that must never be followed as instructions.

---

## Claim verification — `/conversations/{id}/messages/{messageId}/verify` (Phase 2)

| Method | Path | Notes |
|---|---|---|
| GET | `…/verify` | Stored verification, if any. Never calls the model, never rate-limited |
| POST | `…/verify` | Runs verification (deterministic analysis + **one** bounded model call) and stores it. Rate-limited AI request |

```jsonc
// Response (both GET and POST)
{
  "messageId": "…",
  "status": "COMPLETED",       // NOT_REQUESTED | COMPLETED | PARTIAL | FAILED
  "coverage": 0.75,            // deterministic: fraction of analyzed claims citing ≥1 valid source (null when no claims)
  "model": "gpt-oss-20b",      // classifier model; null when no model ran
  "verifiedAt": "…",
  "totalClaims": 8,            // claims found in the text (may exceed claims.length when capped)
  "error": null,               // present when status = FAILED
  "claims": [{
    "claimIndex": 0, "sentenceIndex": 0,
    "claimText": "Revenue grew 14 percent. [S1]",
    "supportClass": "SUPPORTED",  // SUPPORTED | PARTIALLY_SUPPORTED | UNSUPPORTED | CONFLICTING | null (not classified)
    "explanation": "The cited excerpt states the figure directly.",
    "citationIndexes": [0],       // 0-based indexes into the message's citations
    "hasInvalidCitation": false
  }],
  "invalidReferences": [{ "claimIndex": 3, "claimText": "…", "reference": 7 }]  // [S7] does not exist
}
```

Semantics:

- **Deterministic layer** (always runs): claim segmentation, `[Sn]` extraction
  (ASCII + fullwidth/CJK brackets), invalid-reference detection, coverage, and
  `UNSUPPORTED` for claims that cite nothing. A message with no citations at
  all is fully classified without any model call.
- **Model layer**: one bounded, rate-limited completion classifies the
  remaining claims against their cited excerpts. `supportClass` is an AI
  assessment of grounding — **never** a calibrated probability.
- Failure is honest: if the model call fails and nothing was classified,
  `status = FAILED`, `error` explains why, and **nothing is stored** — the
  message stays `NOT_REQUESTED` for a clean retry. Partial classification
  stores what it has (`status = PARTIAL`).
- `GET` before any `POST` returns `status = NOT_REQUESTED` with empty `claims`.
- Re-running (`POST` again) replaces stored rows atomically (delete + insert).

---

## Knowledge graph — `/knowledge-graph` (Phase 3)

| Method | Path | Notes |
|---|---|---|
| GET | `/knowledge-graph` | Graph for the user's library, or one document via `documentId`. Filters: `type`, `q`, `limit` (1–300, default 120) |
| POST | `/knowledge-graph/rebuild` | `{ documentIds?: [...] }` — rebuilds those documents, or up to 10 of the user's READY documents when omitted. CSRF required |

```jsonc
{
  "documentId": "…",              // null for a library-wide graph
  "documentName": "report.pdf",   // null for a library-wide graph
  "scope": "DOCUMENT",            // DOCUMENT | LIBRARY
  "status": "READY",              // NONE | PENDING | READY | FAILED (document scope)
  "error": null,
  "generatedAt": "…",
  "entityCount": 42,
  "relationCount": 118,
  "documentCount": 1,
  "returnedEntities": 42,
  "truncated": false,
  "nodes": [{
    "id": "…", "name": "Alpha Corp", "type": "ORGANIZATION",
    "mentionCount": 7, "documentId": "…", "documentName": "report.pdf",
    "firstPage": 3, "evidenceExcerpt": "…"
  }],
  "edges": [{
    "id": "…", "source": "…", "target": "…",
    "relationType": "CO_OCCURS", "weight": 4,
    "documentId": "…", "firstPage": 3, "evidenceExcerpt": "…"
  }]
}
```

Semantics:

- **PostgreSQL only.** No external graph database; entities and relations live
  in `knowledge_entities` / `knowledge_relations` (V6).
- **Deterministic extraction.** Dates, metrics, acronyms, proper nouns and
  CamelCase names are extracted with regexes; the summary model's `ENTITIES`
  section is merged in when present to improve entity *types*. No model call is
  made to build a graph.
- **`CO_OCCURS` is not a fact.** An edge means both entities were mentioned in
  the same chunk; `weight` is the number of such passages. The API and UI say so
  explicitly, and every node/edge carries an evidence excerpt and first page.
- Extraction is bounded (150 entities / 1500 relations per document, 300 nodes /
  4000 edges per response) and runs synchronously after document processing,
  after summaries complete, and via a bounded asynchronous startup backfill.
- All reads are ownership-scoped; a foreign `documentId` returns **404**.

---

## Verification index — `/verifications` (Phase 3)

| Method | Path | Notes |
|---|---|---|
| GET | `/verifications` | Paginated assistant answers (`page`, `size`, `status`) with stored verification results |
| GET | `/verifications/stats` | Totals for the current user |

`status` filter: `ALL` (default) | `VERIFIED` | `UNVERIFIED` | `PARTIAL` | `COMPLETED`.
This endpoint only **reads** stored results; running verification still uses
`POST /conversations/{id}/messages/{messageId}/verify`, preserving its
ownership checks and rate limit.

```jsonc
{
  "content": [{
    "messageId": "…", "conversationId": "…", "conversationTitle": "Q3 review",
    "contentExcerpt": "…", "createdAt": "…",
    "messageModel": "gpt-oss-20b", "evidenceStatus": "SUFFICIENT", "citationCount": 3,
    "status": "COMPLETED",        // NOT_REQUESTED | PARTIAL | COMPLETED
    "coverage": 0.75,             // deterministic; null when nothing was analyzed
    "verificationModel": "gpt-oss-20b", "verifiedAt": "…",
    "totalClaims": 8, "analyzedClaims": 6, "classifiedClaims": 6,
    "invalidReferenceCount": 0,
    "supportCounts": { "SUPPORTED": 4, "PARTIALLY_SUPPORTED": 1, "UNSUPPORTED": 1, "CONFLICTING": 0 }
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true
}
```

`GET /verifications/stats` → `{ total, verified, partial, completed, unverified }`.

---

## Reports — `/reports` (Phase 3)

| Method | Path | Notes |
|---|---|---|
| GET | `/reports` | Paginated report index over READY documents (`page`, `size`, `q`, `summaryStatus`) |

`summaryStatus`: `ALL` (default) | `NONE` | `PENDING` | `READY` | `FAILED`.
A report is the persisted summary set; listing never calls a model.

```jsonc
{
  "content": [{
    "documentId": "…", "documentName": "annual-report.pdf", "pageCount": 42,
    "updatedAt": "…", "summaryStatus": "READY", "summaryError": null,
    "sectionCount": 6, "model": "gpt-oss-20b", "generatedAt": "…",
    "executiveSummary": "…",
    "keyFindings": [{ "text": "…", "type": "FACT" }, { "text": "…", "type": "INTERPRETATION" }],
    "entityCount": 18, "dateStatCount": 9, "topicCount": 6, "questionCount": 5
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true
}
```

Regeneration remains on `POST /documents/{id}/summaries/regenerate`
(ownership-checked, rate-limited, atomic replace).

---

## History — `/history` (Phase 3)

| Method | Path | Notes |
|---|---|---|
| GET | `/history/processing` | Paginated processing-event feed across the user's documents (`page`, `size`), newest first |

Every row is a persisted `document_processing_events` stage transition — nothing
is synthesized. Research-session history is served by the existing
`GET /conversations`, which already returns newest-updated first.

```jsonc
{
  "content": [{
    "id": "…", "documentId": "…", "documentName": "report.pdf",
    "attempt": 2, "stage": "COMPLETED",
    "errorCode": null, "errorMessage": null, "createdAt": "…"
  }],
  "page": 0, "size": 25, "totalElements": 12, "totalPages": 1, "first": true, "last": true
}
```

---

## Tags — `/tags`

| Method | Path | Notes |
|---|---|---|
| GET | `/tags` | List all tags |
| POST | `/tags` | `{ name, color? }` |
| PATCH | `/tags/{id}` | `{ name?, color? }` |
| DELETE | `/tags/{id}` | Delete (documents keep their other tags) |

---

## Collections — `/collections`

| Method | Path | Notes |
|---|---|---|
| GET | `/collections` | List collections |
| GET | `/collections/{id}` | Detail |
| GET | `/collections/{id}/documents` | Paginated documents in the collection |
| POST | `/collections` | `{ name, description? }` |
| PATCH | `/collections/{id}` | Rename / describe |
| DELETE | `/collections/{id}` | Delete collection (documents survive) |
| POST | `/collections/{id}/documents` | `{ documentIds: [...] }` add documents |
| DELETE | `/collections/{id}/documents/{documentId}` | Remove one document |

---

## Health — `/actuator/health`

| Path | Notes |
|---|---|
| `/actuator/health`, `/liveness`, `/readiness` | Probes, unauthenticated |

---

## Rate limits

| Scope | Config key | Default behavior |
|---|---|---|
| Login per IP | `rate-limit.login` | 429 + `Retry-After` |
| Login per account (any IP) | `rate-limit.login` | same window/limit, separate bucket |
| Uploads per user | `rate-limit.upload` | 429 |
| AI requests per user (chat, summaries, verify) | `rate-limit.ai` | 429 |

A non-positive configured limit disables that check (set `0` for unlimited).

## Embeddings & retrieval configuration

| Env var | Default | Meaning |
|---|---|---|
| `EMBEDDINGS_PROVIDER` | `onnx` | `onnx` (local model) or `off` (keyword-only) |
| `EMBEDDINGS_CACHE_DIR` | `~/.verityai/models` | Model cache (downloaded once, SHA-256 verified, reused offline) |
| `RETRIEVAL_HYBRID` | `true` | Enable the semantic branch of search/chat retrieval |
| `RETRIEVAL_MIN_SIMILARITY` | `0.10` | Cosine floor for semantic candidates |
| `RETRIEVAL_RRF_K` | `60` | Reciprocal Rank Fusion constant |
| `SEARCH_MAX_RESULTS` / `SEARCH_DEFAULT_RESULTS` | `30` / `20` | Search cap / default |
| `VERIFY_ENABLED` / `VERIFY_MODEL` | `true` / *(chat model)* | Claim verification switch / dedicated model |
| `VERIFY_MAX_CLAIMS` / `VERIFY_MAX_TOKENS` | `25` / `2500` | Per-message analysis cap / model budget |
