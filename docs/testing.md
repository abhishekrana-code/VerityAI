# VerityAI Testing Guide

How the test suite is organized, how to run it, and what each layer covers.

## Quick start

```bash
# Backend (Docker required — Testcontainers spins up PostgreSQL + pgvector)
cd backend
.\mvnw.cmd -B --no-transfer-progress test          # Windows
./mvnw -B --no-transfer-progress test              # macOS/Linux

# Frontend
cd frontend
npm run typecheck && npm run lint && npm test && npm run build
```

Run a single class:

```bash
.\mvnw.cmd test "-Dtest=SemanticSearchIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## Layers

| Layer | Tooling | Notes |
|---|---|---|
| Unit | JUnit 5 + AssertJ + Mockito | Pure logic: prompts, segmentation, rate limiting, embedding math |
| Repository | Testcontainers (`pgvector/pgvector:0.8.7-pg17`) | Real SQL: FTS (`ts_rank`), vector similarity, generated `tsvector` column |
| Integration (MVC) | `@SpringBootTest` + `MockMvc` + Testcontainers | Full filter chain: session auth, CSRF, validation, ownership, rate limits |
| Frontend | Vitest + jsdom | API client, error mapping |

Every Testcontainers class boots a **fresh** PostgreSQL container with Flyway
migrations V1–V6 applied, so schema regressions surface immediately.

## External dependencies in tests

- **LLM (Groq): never called.** Integration tests that exercise chat or claim
  verification replace `LlmService` with a mock (`@MockitoBean`) that returns
  canned responses, so the suite is deterministic and offline-safe.
- **Embedding model (ONNX): downloaded once, then cached** at
  `~/.verityai/models` with SHA-256 verification. Semantic-search tests embed
  for real and are **skipped (not failed)** via `assumeTrue(...)` when the
  model is unavailable — check the run output for `Skipped` counts and report
  them honestly.
- **Documents:** uploads use a minimal fake PDF; the parser legitimately fails
  it (FAILED status), which the processing-history assertions rely on.
  Search/verification tests seed documents and chunks directly through the
  repositories instead.

## Backend test classes

| Class | Tests | Covers |
|---|---:|---|
| `AuthIntegrationTest` | 9 | Register/login/logout, session cookie, CSRF contract, 401s |
| `DocumentIntelligenceIntegrationTest` | 18 | List filters/sort whitelist, tags, collections, processing history, summaries lifecycle, bulk delete, file access |
| `RateLimitIntegrationTest` | 3 | 429 + `Retry-After` at the HTTP layer |
| `DocumentChunkRepositoryTest` | 6 | FTS relevance, scoped search, soft-delete/user isolation |
| `RateLimitServiceTest` | 8 | Per-IP/per-account/per-user buckets, disabled limits |
| `ChatServiceTest` | 6 | Evidence → answer → citation persistence logic |
| `GroqLlmServiceTest` | 13 | `[Sn]` extraction (incl. CJK brackets), multi-document prompt, evidence-injection hardening |
| `OnnxEmbeddingServiceTest` | 5 | Real model: download/checksum, pooling, normalization, mask handling |
| `NoopEmbeddingServiceTest` | 2 | Provider-off contract |
| `ClaimVerificationServiceTest` | 13 | Deterministic layer: segmentation, refs, coverage, class mapping, JSON parsing |
| `SemanticSearchIntegrationTest` | 9 | **Hybrid search:** paraphrase → `semantic`, overlap → `hybrid`, unindexed → `keyword`, 403/401/400, cross-user isolation, server-side limit cap |
| `KeywordOnlySearchTest` | 3 | **Graceful degradation:** `provider=off` → keyword-only, `model: null`, honest empty results |
| `ConversationRagIntegrationTest` | 11 | **Multi-doc chat citation integrity** (evidence scoping, foreign docs 403) + **claim verification endpoints** (NOT_REQUESTED / deterministic / model round-trip / invalid `[S7]` / FAILED stores nothing / re-verify replaces / 404 authz / USER message 400) |
| `IntelligencePagesIntegrationTest` | 8 | **Phase 3 read models:** deterministic graph build (entities, summary-type merge, CO_OCCURS edges, type filter, ownership 404), verification list/stats/filters, report index (facts vs interpretations), processing-history ordering/errors, 401 on all read models |

**Total: 114 backend tests.**

### Notable behaviors the tests pin down

- Search never blocks on embeddings; an unavailable model degrades to
  keyword-only with `strategy: "keyword"` and an honest `model` field.
- The fused (RRF) result list is trimmed to the requested limit — the union of
  two `limit`-capped branches must not leak `2×limit` results.
- Claim verification's deterministic layer runs with zero model calls when no
  claim cites anything; a model failure with nothing classified reports
  `FAILED` and stores **nothing** (message stays `NOT_REQUESTED`).
- `supportClass` is presented as an AI grounding assessment, never a
  probability — the API and UI both say so explicitly.
- Evidence handed to the model is scoped to the conversation's documents (or
  an explicit `documentIds` subset); a better-matching document outside the
  conversation is never cited.

## Frontend test classes

| File | Tests | Covers |
|---|---:|---|
| `src/lib/api.test.ts` | 7 | API client: base URL, CSRF header injection, error envelope parsing |
| `src/lib/errors.test.ts` | 5 | Error → user-facing message mapping |
| `src/features/knowledge-graph/graphLayout.test.ts` | 5 | Deterministic SVG layout: bounds, stable positions, mention-scaled radii, missing-edge tolerance |

**Total: 17 frontend tests.** Component-level UI flows (context picker,
verification panel, PDF jumps, graph inspector) are not covered in jsdom. They
are validated by typecheck/lint/production build plus the API-level live checks
in `docs/phase-3-verification-report.md`; there is no automated browser E2E
suite in this repository.

## CI

`.github/workflows/ci.yml` runs `mvnw verify` (backend, requires a
Docker-capable runner for Testcontainers) and `lint`/`typecheck`/`test`/`build`
(frontend) on every push.

## Reporting rules

- Never mark a feature complete with failing or skipped tests — list every
  failure and skip in the verification report with its reason.
- Skipped semantic tests (model unavailable) must be called out explicitly.
- Live LLM behavior (Groq responses, citation quality) is verified manually
  in the browser E2E pass — unit tests only pin the contract around it.
