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
migrations V1-V8 applied, so schema regressions surface immediately.

## External dependencies in tests

- **LLM (Groq): never called.** Integration tests that exercise chat or claim
  verification replace `LlmService` with a mock (`@MockitoBean`) that returns
  canned responses, so the suite is deterministic and offline-safe.
- **Mail (SMTP): never called.** `PasswordResetIntegrationTest` mocks
  `MailService`, captures the reset link, and exercises token single-use,
  expiry, password change, and session invalidation without a real mailbox.
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
| `AuthIntegrationTest` | 13 | Register (generic, session-free), login/logout, session-id rotation, single-session policy, profile persistence across password changes, CSRF contract, 401s |
| `DocumentIntelligenceIntegrationTest` | 18 | List filters/sort whitelist, tags, collections, processing history, summaries lifecycle, bulk delete, file access |
| `RateLimitIntegrationTest` | 4 | 429 + `Retry-After` at the HTTP layer; fake-PDF magic-byte rejection |
| `ForwardedHeaderIntegrationTest` | 1 | `X-Forwarded-For` from a trusted loopback peer drives the per-IP limiter |
| `CorsIntegrationTest` | 2 | Configured origin allowed with credentials; unlisted origin rejected |
| `PasswordResetIntegrationTest` | 5 | Generic request response, token capture, single-use/expiry, password change, session invalidation |
| `DocumentChunkRepositoryTest` | 6 | FTS relevance, scoped search, soft-delete/user isolation |
| `RateLimitServiceTest` | 10 | Per-IP/per-account/per-user buckets, registration/reset buckets, disabled limits |
| `ChatServiceTest` | 6 | Evidence → answer → citation persistence logic |
| `GroqLlmServiceTest` | 13 | `[Sn]` extraction (incl. CJK brackets), multi-document prompt, evidence-injection hardening |
| `OnnxEmbeddingServiceTest` | 5 | Real model: download/checksum, pooling, normalization, mask handling |
| `NoopEmbeddingServiceTest` | 2 | Provider-off contract |
| `ClaimVerificationServiceTest` | 13 | Deterministic layer: segmentation, refs, coverage, class mapping, JSON parsing |
| `SemanticSearchIntegrationTest` | 9 | **Hybrid search:** paraphrase → `semantic`, overlap → `hybrid`, unindexed → `keyword`, 403/401/400, cross-user isolation, server-side limit cap |
| `KeywordOnlySearchTest` | 3 | **Graceful degradation:** `provider=off` → keyword-only, `model: null`, honest empty results |
| `ConversationRagIntegrationTest` | 11 | **Multi-doc chat citation integrity** (evidence scoping, foreign docs 403) + **claim verification endpoints** (NOT_REQUESTED / deterministic / model round-trip / invalid `[S7]` / FAILED stores nothing / re-verify replaces / 404 authz / USER message 400) |
| `IntelligencePagesIntegrationTest` | 8 | **Phase 3 read models:** deterministic graph build (entities, summary-type merge, CO_OCCURS edges, type filter, ownership 404), verification list/stats/filters, report index (facts vs interpretations), processing-history ordering/errors, 401 on all read models |
| `ContradictionServiceTest` | 16 | **Feature 8 deterministic layer:** proper/topic/number/year extraction, shared-subject pairing, same-chunk/identical/duplicate rejection, pair cap, five-class + severity mapping, strict JSON parsing, canonical pair keys, prompt contract (scope/complementary/time/paraphrase exclusions) |
| `ContradictionIntegrationTest` | 5 | **Feature 8 endpoints:** detection stores both claims + document/page citations, class/severity filters and stats, failed model/invalid JSON store nothing and keep earlier findings, valid empty result replaces scope, no-candidate runs skip the model, ownership/404, unprocessed 400, CSRF, 401 |
| `CorsConfigTest` | 3 | Origin list parsing (trim, blanks, duplicates) and empty configuration |
| `ProcessingExecutorTest` | 1 | Saturated pool rejects instead of running PDF work on the request thread |

**Total: 154 backend tests.**

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
- Contradiction detection only stores pairs the classifier explicitly flagged
  with one of the five approved classes and a valid severity. A model failure or
  invalid JSON returns `FAILED` and stores nothing; earlier findings stay intact.
  Scope differences, complementary facts, different time windows and paraphrases
  are rejected in both the system and user prompts.
- Every stored contradiction carries both claims, both documents, both page
  numbers and both chunk IDs — the read model never returns a label without
  its evidence.

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
