# VerityAI

**Answers you can verify.** — A premium AI research assistant with evidence-based answers from your documents.

---

## Overview

VerityAI is a full-stack AI research platform that lets users upload PDFs and ask questions, receiving answers grounded in the uploaded documents with traceable citations.

### Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite 7, Tailwind CSS 4, shadcn/ui, TanStack Query, React Router 7 |
| Backend | Java 21, Spring Boot 3.5, Maven Wrapper, Spring Security (Session + CSRF), Spring Data JPA, Flyway |
| Database | PostgreSQL 17 (Docker), full-text search |
| AI | Groq API (OpenAI-compatible), Java `HttpClient` + Jackson |
| Deployment | Docker Compose (Postgres), nginx reverse proxy, Cloudflare Tunnel — see [docs/deployment.md](docs/deployment.md) |

---

## Quick Start (Windows)

### Prerequisites
- Java 21 (Temurin/Eclipse Adoptium)
- Node.js 20+ (LTS)
- Docker Desktop for Windows
- Git

### 1. Clone & Configure
```powershell
git clone <your-repo-url> VerityAI
cd VerityAI
copy .env.example .env
# Edit .env and set GROQ_API_KEY at minimum
```

### 2. Start Database
```powershell
docker compose up -d postgres
# Verify: docker compose logs -f postgres
```

### 3. Backend
```powershell
cd backend
# Load .env into this terminal (Spring reads OS environment variables):
Get-Content ..\.env | ForEach-Object {
  if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
  }
}
.\mvnw.cmd spring-boot:run
# Runs on http://localhost:8080
# Health: http://localhost:8080/actuator/health
```

### 4. Frontend (new terminal)
```powershell
cd frontend
npm install
npm run dev
# Runs on http://localhost:5173 (proxies /api to backend)
```

---

## Project Structure
```
verity-ai/
├─ frontend/          # React SPA
├─ backend/           # Spring Boot API
├─ docs/              # Architecture, API reference, testing, deployment runbook
├─ docker-compose.yml # Postgres 17 + pgvector (dev database)
├─ .env.example       # Template for environment variables
└─ README.md          # This file
```

---

## Key Features

- **Evidence-based answers** — Every claim cites a document chunk with page number
- **Document library** — Upload, process, search, retry failed PDFs
- **Research workspace** — Chat interface with citation panel, conversation history
- **Hybrid semantic search** (Phase 2) — Local ONNX embeddings + PostgreSQL pgvector fused with full-text search (RRF); degrades honestly to keyword-only when the model is unavailable
- **Multi-document research chat** (Phase 2) — Ask across several PDFs at once; sources are compared explicitly and cited per claim; context documents can be added/removed at any time
- **Claim verification** (Phase 2) — Deterministic citation coverage + AI grounding classes (Supported / Partially supported / Unsupported / Conflicting) with clickable page-level citation jumps into the PDF
- **Knowledge graph** (Phase 3) — PostgreSQL-only, deterministic entity extraction with evidence excerpts and same-passage (`CO_OCCURS`) links rendered as an interactive SVG graph
- **Verification, Reports & History pages** (Phase 3) — Library-wide read models over stored claim results, generated summaries (facts vs interpretations) and persisted processing events
- **Contradiction detection** (Phase 3, Feature 8) — Compares bounded claim pairs that share a subject and reports only genuine conflicts in five classes (numeric, temporal, attribute, causal, polarity), each with both claims, documents and page citations plus a LOW/MEDIUM/HIGH impact rating
- **Dedicated search page** — Hybrid retrieval UI with document scope, honest strategy/model badges and PDF page jumps
- **Secure by default** — Session auth, HttpOnly cookies, CSRF, ownership enforcement
- **Rate limited** — Per-IP *and* per-account login limits, per-user upload/AI quotas return 429 with `Retry-After`
- **Observable** — Structured logs, request IDs, health endpoints, Actuator metrics

---

## Development Commands

| Task | Command |
|---|---|
| Backend test | `cd backend && .\mvnw.cmd test` |
| Backend build | `cd backend && .\mvnw.cmd package -DskipTests` |
| Frontend test | `cd frontend && npm test` |
| Frontend build | `cd frontend && npm run build` |
| Lint frontend | `cd frontend && npm run lint` |
| Typecheck frontend | `cd frontend && npm run typecheck` |
| DB migration | `cd backend && .\mvnw.cmd flyway:migrate` |
| Reset DB | `docker compose down -v && docker compose up -d postgres` |

---

## Environment Variables

See `.env.example` for all options. Minimum required:
- `GROQ_API_KEY` — from https://console.groq.com/keys (chat fails without it)

Everything else has working defaults. Phase 2 additions (all optional):
- `EMBEDDINGS_PROVIDER=onnx|off` — local embeddings; `off` = keyword-only search
- `RETRIEVAL_HYBRID`, `RETRIEVAL_MIN_SIMILARITY` — semantic branch tuning
- `VERIFY_ENABLED`, `VERIFY_MODEL`, `VERIFY_MAX_CLAIMS`, `VERIFY_MAX_TOKENS` — claim verification

Phase 3 additions (all optional):
- `KNOWLEDGE_GRAPH_ENABLED`, `KNOWLEDGE_GRAPH_BACKFILL_LIMIT`,
  `KNOWLEDGE_GRAPH_REBUILD_MAX_DOCUMENTS`, `KNOWLEDGE_GRAPH_MAX_NODES`,
  `KNOWLEDGE_GRAPH_MAX_EDGES` — deterministic PostgreSQL knowledge graph
- `CONTRADICTION_ENABLED`, `CONTRADICTION_MODEL`, `CONTRADICTION_MAX_DOCUMENTS`,
  `CONTRADICTION_MAX_CHUNKS_PER_DOCUMENT`, `CONTRADICTION_MAX_CLAIMS_PER_DOCUMENT`,
  `CONTRADICTION_MAX_PAIRS`, `CONTRADICTION_MAX_TOKENS` — contradiction detection

Note: the backend's JDBC credentials
are set in `backend/src/main/resources/application.yml` — keep
`POSTGRES_PASSWORD` at its default (`verityai_dev`), or change both together.

---

## Testing & CI

- **Backend**: 135 tests — JUnit 5 + Mockito + Testcontainers (PostgreSQL 17 + pgvector in Docker).
  Run `cd backend && .\mvnw.cmd test` (Docker must be running). See [docs/testing.md](docs/testing.md).
- **Frontend**: 17 tests — Vitest + jsdom. Run `cd frontend && npm test`.
- **CI**: `.github/workflows/ci.yml` runs `mvnw verify` (backend) and lint/typecheck/test/build
  (frontend) on every push. Testcontainers requires a Docker-capable runner.

The Groq LLM is never called from tests (mocked); the ONNX embedding model is
downloaded once, SHA-256 verified, and cached locally — semantic tests skip
honestly when it is unavailable.

Verification reports: [Phase 2](docs/phase-2-verification-report.md) ·
[Phase 3](docs/phase-3-verification-report.md).

---

## API Overview

All endpoints live under `/api/v1` and return JSON. Auth is a `SESSION` cookie;
state-changing requests (everything except `/auth/**`) also need the `XSRF-TOKEN`
cookie echoed back as an `X-XSRF-TOKEN` header.

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register`, `/auth/login`, `/auth/logout` | Session cookie auth; CSRF-exempt |
| GET | `/auth/csrf`, `/auth/me` | Provision CSRF token / current user |
| PATCH | `/auth/users/me` | Profile update |
| POST | `/auth/users/me/password` | Change password |
| GET/POST | `/documents` | List / upload PDF (multipart field `file`) |
| GET/DELETE | `/documents/{id}`, `/documents/{id}/status` | Detail, processing status, delete |
| POST | `/documents/{id}/retry` | Re-run failed processing |
| GET/POST | `/conversations` | List / create |
| GET/PATCH/DELETE | `/conversations/{id}` | Detail, rename/archive, edit document context, delete |
| GET/POST | `/conversations/{id}/messages` | Read history / ask a question (RAG + citations) |
| GET/POST | `/conversations/{id}/messages/{messageId}/verify` | Read stored claim verification / run it (bounded model call) |
| POST | `/search` | Hybrid semantic + keyword search over your documents |
| GET | `/knowledge-graph` | Evidence-backed entity graph (library or one document), filters `type`, `q`, `limit` |
| POST | `/knowledge-graph/rebuild` | Rebuild one, several, or (bounded) all READY graphs |
| GET | `/verifications`, `/verifications/stats` | Stored claim-verification index and totals |
| GET | `/contradictions`, `/contradictions/stats` | Stored contradiction findings, class/severity totals |
| POST | `/contradictions/detect` | Run one bounded contradiction scan (bounded model call) |
| GET | `/reports` | Report index over persisted summary sections |
| GET | `/history/processing` | Persisted processing-event feed across your documents |
| GET/POST/PATCH/DELETE | `/tags`, `/tags/{id}` | Tag management |
| GET/POST/PATCH/DELETE | `/collections`… | Collection management (see [docs/api.md](docs/api.md)) |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Probes (unauthenticated) |

Full request/response shapes: **[docs/api.md](docs/api.md)**.

Error responses share one envelope: `{ code, message, fieldErrors?, requestId }`.
Rate-limited responses (429) additionally carry a `Retry-After` header.

---

## Deployment

Self-hosted deployment (Ubuntu server, systemd, nginx, Cloudflare, backups) is
documented in **[docs/deployment.md](docs/deployment.md)**.

Key production settings:
- `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` (HTTPS)
- `SPRING_PROFILES_ACTIVE=prod`
- `RATE_LIMIT_*`, `GROQ_API_KEY`, and an absolute `UPLOAD_DIR` outside the web root

---

## License

MIT — see LICENSE file.
## Premium UI refresh

The frontend now uses a premium dark visual system by default, with a saved light/system theme option. The refresh updates the shared application shell, navigation, brand mark, landing experience, dashboard, authentication screens, and research conversation styling while retaining the existing React Router routes, API client, React Query data flows, and Spring Boot backend.

### Frontend setup

```bash
cd frontend
npm ci
npm run dev
```

### Frontend validation

```bash
npm run typecheck
npm run lint
npm test
npm run build
```

The redesign does not introduce new backend endpoints or replace the existing LLM/RAG integration.
