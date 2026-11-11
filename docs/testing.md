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

# Browser E2E (Playwright; starts its own Postgres, backend, Vite and SMTP sink)
cd frontend
npm run test:e2e
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
| Browser E2E | Playwright (Chromium) | Real UI against an isolated backend, Postgres, Vite and SMTP sink |

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
| `DocumentIntelligenceIntegrationTest` | 19 | List filters/sort whitelist, tags, collections, processing history, summaries lifecycle, bulk delete, file access, per-user duplicate detection (same bytes allowed for another tenant) |
| `RateLimitIntegrationTest` | 4 | 429 + `Retry-After` at the HTTP layer; fake-PDF magic-byte rejection |
| `ForwardedHeaderIntegrationTest` | 1 | `X-Forwarded-For` from a trusted loopback peer drives the per-IP limiter |
| `CorsIntegrationTest` | 2 | Configured origin allowed with credentials; unlisted origin rejected |
| `PasswordResetIntegrationTest` | 5 | Generic request response, token capture, single-use/expiry, password change, session invalidation |
| `PasswordResetLinkResolutionTest` | 9 | Reset-link base URL: explicit config wins, browser origin used only when allowlisted, forged origin rejected, non-HTTP schemes rejected, default ports normalised |
| `PasswordResetLinkOriginIntegrationTest` | 2 | With `APP_BASE_URL` unset, the emailed link follows the requesting `Origin`; an origin outside the allowlist is refused (403) and mints no token |
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

**Total: 166 backend tests.**

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
verification panel, PDF jumps, graph inspector) are not covered in jsdom; the
critical user journeys below are covered by the browser E2E suite.

## Browser E2E (Playwright)

`frontend/e2e/` runs the real React app in Chromium against a **completely
isolated** stack. It never touches a developer backend, the development
database, or a real mail provider.

| Component | Port | Notes |
|---|---:|---|
| PostgreSQL (pgvector) | 5435 | `docker-compose.e2e.yml`, tmpfs storage, database `verityai_e2e` |
| Backend | 8081 | Started by `e2e/start-backend.mjs`; rate limits disabled |
| Vite | 5174 | `VITE_API_PROXY_TARGET=http://127.0.0.1:8081` |
| SMTP sink | 1025 | Captures mail; no external delivery |
| Mailbox API | 1026 | HTTP API used by tests to read captured messages |

Run it:

```bash
cd frontend
npm run test:e2e          # headless, starts every dependency
npm run test:e2e:ui       # interactive UI mode
npm run test:e2e:install  # install the Chromium binary once
```

`start-backend.mjs` starts the isolated Postgres container automatically when
port 5435 is closed, then runs Spring Boot with:

- `EMBEDDINGS_PROVIDER=off` (keyword-only retrieval, no model download),
- `SUMMARY_ENABLED=false` (no Groq calls),
- `MAIL_HOST=127.0.0.1`, `MAIL_PORT=1025`, STARTTLS/auth disabled,
- every `RATE_LIMIT_*` set to `0` (unlimited),
- `UPLOAD_DIR=frontend/e2e/.tmp/uploads`.

Covered journeys:

| Spec | Covers |
|---|---|
| `auth.spec.ts` | Protected/public route guards, register → login → logout, session-id rotation + single-session policy (two browser contexts), profile display-name persistence across reloads |
| `documents.spec.ts` | Real PDF upload through the dropzone, document appears in the list, server-side `%PDF-` gate rejects a disguised non-PDF |
| `password-reset.spec.ts` | Non-enumerating reset request, captured email → token → new password, old password rejected, new password works; unknown address gets the same response; a signed-in browser can open a reset link without being redirected to the dashboard |

The SMTP sink decodes quoted-printable/base64 and multipart bodies, so the
reset-link extraction keeps working if the mail format changes.

Troubleshooting:

- **Docker not running** — `start-backend.mjs` exits with a clear message.
- **Port already in use** — the E2E stack uses 5435/8081/5174/1025/1026;
  stop stale E2E processes or run `docker compose -f docker-compose.e2e.yml down -v`.
- **Slow first run** — Maven compiles the backend before Spring Boot starts;
  the Playwright startup timeout is 10 minutes.

## CI

`.github/workflows/ci.yml` runs three jobs on every push and pull request:

1. **Backend** — `mvnw verify` (requires a Docker-capable runner for Testcontainers).
2. **Frontend** — `lint`, `build` (typecheck + bundle) and unit tests.
3. **E2E** — starts `docker-compose.e2e.yml`, installs Chromium, and runs the
   Playwright suite against the isolated stack. On failure it uploads
   `playwright-report/` and `test-results/` as artifacts.

## Reporting rules

- Never mark a feature complete with failing or skipped tests — list every
  failure and skip in the verification report with its reason.
- Skipped semantic tests (model unavailable) must be called out explicitly.
- Live LLM behavior (Groq responses, citation quality) is verified manually
  in the browser E2E pass — unit tests only pin the contract around it.
