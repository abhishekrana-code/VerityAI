# Phase 3 Verification Report — Knowledge Graph & Intelligence Pages

**Date:** 2026-11-07
**Branch:** `master`
**Base commit:** `4d7af8f` (end of Phase 2) — the work described here is uncommitted at the time of writing.
**Environment:** Windows 11, Java 21, Node 20, Docker Desktop, PostgreSQL 17 + pgvector 0.8.7, live Groq account.

---

## 1. Scope

This report covers the Phase 2 gap-fill plus Phase 3 Feature 7:

| Area | Status |
|---|---|
| Dedicated search page (`/dashboard/search`) | Done |
| Knowledge Graph, PostgreSQL only (Feature 7) | Done |
| Verification page (library-wide read model) | Done |
| Reports page (persisted summary sections) | Done |
| History page (processing events + research sessions) | Done |
| Navigation + routes for all pages | Done |
| Feature 8 — Contradiction Detection (5 classifications) | **Not implemented** — the five classifications still need confirmation before building |

Feature 8 is deliberately excluded: the spec requires five specific
contradiction classes, and guessing them would produce a taxonomy the product
owner never asked for. Everything in this report was implemented without
touching existing auth, sessions, CSRF, uploads, retrieval, chat or verification
business logic.

---

## 2. What shipped

### Backend

| Item | Files |
|---|---|
| V6 migration: graph tables, per-document graph lifecycle, read-model indexes | `db/migration/V6__knowledge_graph_and_intelligence_pages.sql` |
| Deterministic entity/relation extraction | `service/knowledge/KnowledgeExtractor.java` |
| Graph build/read/backfill service | `service/knowledge/KnowledgeGraphService.java` |
| Graph API | `controller/KnowledgeGraphController.java`, DTOs |
| Verification index + stats | `service/verification/VerificationListService.java`, `controller/VerificationController.java` |
| Report index | `service/report/ReportService.java`, `controller/ReportController.java` |
| Processing-history feed | `service/history/HistoryService.java`, `controller/HistoryController.java` |
| Lifecycle hooks | `DocumentService` (rebuild after processing, clear on delete), `SummaryService` (rebuild after summaries) |
| Batched reads | fetch joins / `IN` queries in message, verification, summary, entity and relation repositories |

Schema (V6):

- `knowledge_entities` — unique per `(document_id, normalized_name, entity_type)`, indexed by user/document/name.
- `knowledge_relations` — `CO_OCCURS` edges, unique per `(document_id, source, target, type)`, with a `CHECK (source_entity_id < target_entity_id)` so an undirected pair cannot be stored twice.
- `documents.knowledge_status / knowledge_error / knowledge_generated_at` — mirrors `summary_status`, so the UI can show an honest `NONE | PENDING | READY | FAILED`.
- Read-model indexes on `documents(user_id, status, updated_at) WHERE deleted_at IS NULL`, `document_processing_events(created_at DESC)` and `messages(conversation_id, role, created_at DESC)`.

Extraction is deterministic and bounded:

- Patterns: dates, metrics, acronyms, proper nouns, CamelCase names.
- Merged with the summary model's `ENTITIES` section (summary type wins over the pattern fallback).
- Relations are same-passage co-occurrence only. The API field is `relationType: "CO_OCCURS"` and every node/edge carries `firstPage` + `evidenceExcerpt`.
- Caps: 150 entities and 1500 relations per document; 300 nodes / 4000 edges per response; 10 documents per explicit rebuild; 20 documents per startup backfill.

### Frontend

| Item | Files |
|---|---|
| Shared loading/empty/error/stat/pagination primitives | `components/ui/PageStates.tsx` |
| Search page | `features/search/SearchPage.tsx` |
| Knowledge graph page + deterministic layout | `features/knowledge-graph/KnowledgeGraphPage.tsx`, `graphLayout.ts` (+ tests) |
| Verification page | `features/verification/VerificationPage.tsx` |
| Reports page | `features/reports/ReportsPage.tsx` |
| History page | `features/history/HistoryPage.tsx` |
| Routes / navigation / ⌘K | `app/App.tsx`, `components/Layout.tsx` |

Every page has loading, empty, error and populated states, keyboard-reachable
controls, labelled inputs and a mobile-first layout. No new graph library was
added: the SVG force layout is a small deterministic function that is unit
tested for bounds, stability and mention-scaled radii.

---

## 3. Live verification (real account, real data)

Performed against the running backend on `http://localhost:8080` with the real
account `you@gmail.com` and the three real processed PDFs already in the
library.

| Check | Result |
|---|---|
| `POST /auth/login` | authenticated |
| `GET /documents?size=10` | 3 documents, all `READY`, all `summaryStatus=READY` |
| `POST /knowledge-graph/rebuild` `{}` | `requested=3, rebuilt=3, skipped=0, errors=0` |
| `GET /knowledge-graph?limit=60` | `scope=LIBRARY, status=READY, entityCount=317, relationCount=3007, documentCount=3, returnedEntities=60, truncated=true` |
| Graph content sample | `PCC [ACRONYM] 155 mentions p22`, `CO3 [ACRONYM] 148 p2`, edges `CO_OCCURS weight=119 p2` |
| `GET /knowledge-graph?documentId=…` | `FBL_PassItOn… status=READY, entities=150, relations=1500, nodes=40` (per-document caps reached, response capped as designed) |
| `GET /verifications?size=5` | 6 assistant answers; 1 `COMPLETED` (coverage 0.222, 9/9 analyzed, 5 citations), 5 `NOT_REQUESTED` |
| `GET /verifications/stats` | `total=6, verified=1, completed=1, partial=0, unverified=5` |
| `GET /reports?size=5` | 3 reports; each 6 sections; e.g. `alpha-report.pdf` 5 findings, 5 entities |
| `GET /history/processing` | 0 rows for this account — see limitation 5 |
| Fresh-account upload → `GET /history/processing` | 3 real events: `UPLOADED`, `QUEUED`, `FAILED` with `PROCESSING_FAILED: Missing root object specification in trailer.` |
| Fresh-account upload of a valid PDF → `GET /knowledge-graph?documentId=…` | document `READY`; graph `status=READY`, 6 entities (`2023 [DATE]`, `42 [METRIC]`, `Alpha Corp [ORGANIZATION]`, …), 15 `CO_OCCURS` edges, `generatedAt` set — **built during processing, with no rebuild call and no restart** |
| `POST /search {"query":"revenue","limit":3}` | `strategy=hybrid, model=all-MiniLM-L6-v2-quantized, 3 hits` |

The knowledge graph, verification index, report index and search results above
are all derived from the user's stored rows. No fixture data is served by any
endpoint.

---

## 4. Defects found and fixed during verification

1. **500 on graph rebuild** — `KnowledgeExtractor` used `Set.of(...)` with a
   duplicated element, which throws at class-initialisation time
   (`NoClassDefFoundError: Could not initialize class …`). Replaced with
   `Set.copyOf(List.of(...))` for both stopword sets; rebuild then returned
   `rebuilt=3`.
2. **Wrong coverage expectation in the new test** — the assertion assumed
   `UNSUPPORTED` lowers coverage. Coverage is the deterministic fraction of
   analyzed claims that cite at least one source; support class is independent.
   The test now seeds a third claim with no citation and asserts the documented
   2/3 coverage, plus the correct support counts.
3. **Redundant query in the verification index** — conversation titles were
   re-fetched with `findAllById` even though the page query already fetch-joins
   the conversation. Removed.
4. **Page-size clamp** — this codebase does not enable method validation, so
   `@Min/@Max` on `@RequestParam` is decorative. The three new services now
   clamp `page`/`size` defensively instead of trusting the annotation.
5. **Frontend correctness** — fixed a stateful global regex in `Highlighted`
   (alternating highlights were wrong), removed a non-existent `graph.model`
   field, made the header search control a real button, made entity-index rows
   keyboard accessible, and removed a `sort` parameter the conversations API
   never supported.
6. **Graph build was a no-op during processing** — the builder only reads
   `READY` documents, but `DocumentService` called it while the row was still
   `PROCESSING`. The call was moved after the status update, so a newly
   processed document gets its graph immediately instead of waiting for the
   next startup backfill or summary run. (The backfill and post-summary rebuild
   already covered the gap, which is why live data still had graphs.)

---

## 5. Test results

### Backend — 114 tests, 0 failures, 0 errors, 0 skipped

```
cd backend
.\mvnw.cmd -B --no-transfer-progress test
```

The new `IntelligencePagesIntegrationTest` (8 tests) covers:

- deterministic graph build from chunks + merged summary entity types,
- `CO_OCCURS` edges with weight and evidence,
- entity-type filtering,
- cross-user 404 / empty-library scoping,
- invalid entity type → 400,
- verification index statuses, coverage, support counts, filters and stats,
- report index sections, fact/interpretation labels and ownership,
- processing-history ordering and error fields,
- 401 on every new read model without a session.

All model calls in tests are mocked (`@MockitoBean LlmService`); no test calls Groq.

### Frontend — typecheck, lint, 17 tests, production build

```
cd frontend
npm run typecheck   # 0 errors
npm run lint        # 0 warnings
npm test            # 17/17
npm run build       # success
```

Build emits the existing single-chunk warning (`index-*.js` ≈ 765 kB, gzip
217 kB). It is a size warning, not an error; code-splitting is a follow-up.

---

## 6. Honest limitations

1. **`CO_OCCURS` is not a relationship.** An edge means two entities appear in
   the same chunk. The API and UI state this in the payload, the legend and the
   explanatory panel. No semantic relation extraction is claimed.
2. **Extraction quality is heuristic.** On the real corpus the most-mentioned
   nodes are acronyms such as `PCC`, `CO1`–`CO6`. This is honest pattern output,
   not entity resolution: the same entity in two documents is stored as two
   nodes, and there is no coreference merging.
3. **Caps are real.** A document stops at 150 entities / 1500 relations, the
   library response at 300 nodes / 4000 edges, and one rebuild at 10 documents.
   The UI shows a truncation banner when it applies.
4. **Library `status`** is `READY` when any entity exists; per-document status
   is stored separately. A partially built library therefore still reads
   `READY` at library scope.
5. **History only contains events that were recorded.** The three documents in
   the demo account were processed before processing events existed, so their
   history is empty. The endpoint was verified end-to-end with a fresh upload
   that produced real `UPLOADED → QUEUED → FAILED` rows.
6. **Feature 8 is absent.** No contradiction detection, no five-class taxonomy,
   no cross-document claim comparison. This report does not claim otherwise.
7. **Verification is still a bounded model call.** The new page only reads
   stored results and triggers the existing, rate-limited verification endpoint.
   A failed verification stores nothing.
8. **No new frontend graph dependency** — the SVG layout is custom and unit
   tested, but it is not a general-purpose graph engine (no pan/zoom, no
   clustering).

---

## 7. Reproduce

```bash
# 1. Start PostgreSQL (pgvector 0.8.7 / PG 17)
docker compose up -d postgres

# 2. Backend (applies V1–V6)
cd backend
.\mvnw.cmd spring-boot:run

# 3. Frontend
cd frontend
npm install
npm run dev

# 4. Full gates
cd backend && .\mvnw.cmd -B --no-transfer-progress test
cd frontend && npm run typecheck && npm run lint && npm test && npm run build
```
