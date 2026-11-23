# VerityAI

**Answers you can verify.** — A premium AI research assistant with evidence-based answers from your documents.

---

## Overview

VerityAI is a full-stack AI research platform that lets users upload PDFs and ask questions, receiving answers grounded in the uploaded documents with traceable citations.

### Stack

| Layer | Technology |
|---|---|
| Frontend | React 18, TypeScript, Vite 5, Tailwind CSS 3, TanStack Query, React Router 6 |
| Backend | Java 21, Spring Boot 3.5, Maven Wrapper, Spring Security (Session + CSRF), Spring Data JPA, Flyway |
| Database | PostgreSQL 17 (Docker), full-text search |
| AI | Groq API (OpenAI-compatible), Java `HttpClient` + Jackson |
| Deployment | Docker Compose (Postgres, backend, nginx-served SPA), optional host nginx/systemd, Cloudflare Tunnel — see [docs/deployment.md](docs/deployment.md) |

---

## Quick Start (Docker — recommended)

Everything — Postgres 17 + pgvector, the Spring Boot API and the nginx-served
React SPA — runs from one compose file:

```bash
git clone <your-repo-url> VerityAI
cd VerityAI
cp .env.example .env          # Windows PowerShell: copy .env.example .env
# edit .env: at minimum set LLM_API_KEY (the default endpoint is Groq)
docker compose up -d --build
```

Open **http://localhost:8080**, register an account, upload a PDF and wait for
`READY`. The first build takes a few minutes (Maven/npm fetch inside the
images); later starts take seconds.

The app has four places: **Home** (upload, recent documents), **Library** (all
documents, with tabs for a document's summary, entities and processing),
**Ask** (cited answers over your PDFs) and **Checks** (claim verification).
Summaries are generated when you open a document's Summary tab, not during
upload — uploading a PDF makes no model calls. Reports and the knowledge-graph
canvas are gone: a summary exports as Markdown, and related documents live in
the Entities tab.

### Ports

| Port | Service | Notes |
|---|---|---|
| 8080 | nginx: SPA + `/api` | The only entry point; `/api/v1/*` is proxied to the backend |
| 5434 | PostgreSQL | Bound to `127.0.0.1` only, for `psql`/Flyway from the host |
| 8080 (container) | Spring Boot | Not published on the host; reach it through nginx |

`/actuator` is intentionally not proxied. Health:
`docker compose exec backend curl -fsS localhost:8080/actuator/health`.

### .env keys that matter

| Key | Default | Effect |
|---|---|---|
| `LLM_API_KEY` (or `GROQ_API_KEY`) | placeholder | Chat, summaries, verification. Uploads still index without it |
| `LLM_MODEL`, `LLM_API_URL` | Groq defaults | Any OpenAI-compatible `/chat/completions` endpoint |
| `POSTGRES_PASSWORD` | `verityai_dev` | Shared by the Postgres container and the backend |
| `EMBEDDINGS_PROVIDER` | `onnx` | `onnx` = local semantic search (one-time model download into a volume); `off` = keyword-only |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | Set `true` once served over HTTPS |
| `APP_BASE_URL` | empty | Public origin used in password-reset links |
| `MAIL_*` | empty | Password-reset email; empty disables sending |
| `MAX_FILE_SIZE_MB` | `25` | Backend limit; nginx accepts up to 30 MB |
| `UPLOAD_RECONCILE_ENABLED` | `false` | Hourly sweep of stored PDFs that no document references; logs each removal, ignores files younger than `UPLOAD_RECONCILE_GRACE_MINUTES` (60), and starts in `UPLOAD_RECONCILE_DRY_RUN=true` so it only logs until you set it to `false` |
| `APP_PORT` | `8080` | Host port for the UI, e.g. `APP_PORT=3000 docker compose up -d` |

Compose fills in the container-only values itself (`SPRING_PROFILES_ACTIVE=prod`,
DB host `postgres`, `/var/lib/verityai/uploads`, `/var/lib/verityai/models`), so
the same `.env` also works for the host-based flow below. Full list:
`.env.example`.

### Volumes and the non-root backend

Named volumes survive `docker compose down`; only `docker compose down -v`
deletes them (database included).

| Volume | Container path | Contents |
|---|---|---|
| `verityai_uploads` | `/var/lib/verityai/uploads` | uploaded PDFs |
| `verityai_embeddings` | `/var/lib/verityai/models` | ONNX MiniLM model cache |
| `verityai_postgres_data` | `/var/lib/postgresql/data` | database |

The JVM runs as uid/gid **10001 (`verity`)**. Docker creates named volumes
root-owned, so `backend/docker-entrypoint.sh` chowns the two writable
directories and then drops root with `setpriv`. Consequences:

- New uploads are owned by uid 10001 inside the volume, not by your host user.
  Inspect them with `docker compose exec backend ls -l /var/lib/verityai/uploads`;
  back up through a container rather than via the host path (which needs root).
- Files written by an older root-running container stay `root:root 0644` and
  remain readable, so existing uploads keep working with no migration.
- `docker compose exec` still opens a root shell (the image declares no default
  `USER`), which is what lets the entrypoint repair volume ownership at startup.

### Common commands

```bash
docker compose ps                     # status + health
docker compose logs -f backend        # app logs (stdout)
docker compose up -d --build          # rebuild and restart after a pull
docker compose down                   # stop, keep data
docker compose down -v                # stop and DELETE database + uploads
```

---

## Alternative: run on the host (hot reload)

Use this for backend/frontend development with instant reload. It needs Java 21,
Node 20+ and Docker (for the database only), and assumes nothing is already
listening on 8080/5173 (stop the compose stack first if it is).

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
# Edit .env and set LLM_API_KEY at minimum (Groq is the default endpoint)
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
├─ docker-compose.yml # Full stack: Postgres 17 + pgvector, backend, nginx-served frontend
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
- **Entities and related documents** (Phase 3) — Deterministic entity extraction with evidence excerpts on each document page, plus IDF-style document connections from shared entities (years and two-letter codes ignored)
- **Checks page** (Phase 3) — Answers with their stored claim results and a **Needs attention** filter, plus a **Conflicts** tab (Beta). Summaries export as Markdown from the document page
- **Contradiction detection** (Phase 3, Feature 8) — Compares bounded claim pairs that share a subject and reports only genuine conflicts in five classes (numeric, temporal, attribute, causal, polarity), each with both claims, documents and page citations plus a LOW/MEDIUM/HIGH impact rating
- **Dedicated search page** — Hybrid retrieval UI with document scope, honest strategy/model badges and PDF page jumps
- **Themed UI** — Premium dark visual system by default, with a saved light/system option; theming only touches the shared shell, auth screens, dashboard and conversation styling, not routes, the API client or data flows
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
| Reset DB + uploads (destroys data) | `docker compose down -v && docker compose up -d --build` |

---

## Environment Variables

See `.env.example` for all options. Minimum required:
- `LLM_API_KEY` — any OpenAI-compatible provider; the default endpoint is Groq
  (https://console.groq.com/keys). Chat fails without it. The older
  `GROQ_API_KEY` / `GROQ_MODEL` / `GROQ_API_URL` variables still work as
  fallbacks, so existing `.env` files need no change.

Everything else has working defaults. Phase 2 additions (all optional):
- `EMBEDDINGS_PROVIDER=onnx|off` — local embeddings; `off` = keyword-only search
- `RETRIEVAL_HYBRID`, `RETRIEVAL_MIN_SIMILARITY` — semantic branch tuning
- `VERIFY_ENABLED`, `VERIFY_MODEL`, `VERIFY_MAX_CLAIMS`, `VERIFY_MAX_TOKENS` — claim verification

Phase 3 additions (all optional):
- `KNOWLEDGE_GRAPH_ENABLED`, `KNOWLEDGE_GRAPH_BACKFILL_LIMIT` — deterministic entity extraction
- `CONTRADICTION_ENABLED`, `CONTRADICTION_MODEL`, `CONTRADICTION_MAX_DOCUMENTS`,
  `CONTRADICTION_MAX_CHUNKS_PER_DOCUMENT`, `CONTRADICTION_MAX_CLAIMS_PER_DOCUMENT`,
  `CONTRADICTION_MAX_PAIRS`, `CONTRADICTION_MAX_TOKENS` — contradiction detection

Production/security additions (all optional except the DB secret):
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` —
  explicit DB overrides; the password falls back to `POSTGRES_PASSWORD`
- `CORS_ALLOWED_ORIGINS` — comma-separated explicit origins (credentials enabled)
- `FORWARD_HEADERS_STRATEGY`, `TRUSTED_PROXY_PATTERN` — trust `X-Forwarded-*` only
  from loopback/private proxies
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`,
  `MAIL_STARTTLS`, `MAIL_SSL` — password-reset email; leave `MAIL_HOST` empty to disable
- `APP_BASE_URL`, `PASSWORD_RESET_TOKEN_TTL_MINUTES` — reset-link origin and expiry
- `RATE_LIMIT_REGISTER_PER_HOUR_IP`, `RATE_LIMIT_PASSWORD_RESET_PER_HOUR_IP`,
  `RATE_LIMIT_PASSWORD_RESET_PER_HOUR_ACCOUNT` — pre-auth abuse limits

Changing `POSTGRES_PASSWORD` updates both Docker Compose and the backend unless
`SPRING_DATASOURCE_PASSWORD` is set explicitly.

---

## Testing & CI

- **Backend**: 166 tests — JUnit 5 + Mockito + Testcontainers (PostgreSQL 17 + pgvector in Docker).
  Run `cd backend && .\mvnw.cmd test` (Docker must be running). See [docs/testing.md](docs/testing.md).
- **Frontend**: 17 tests — Vitest + jsdom. Run `cd frontend && npm test`.
- **E2E**: Playwright + Chromium against an isolated Postgres/backend/Vite/SMTP
  stack (ports 5435/8081/5174/1025). Run `cd frontend && npm run test:e2e`
  (Docker must be running). It never touches your dev database or sends real
  email. See [docs/testing.md](docs/testing.md).
- **CI**: `.github/workflows/ci.yml` runs `mvnw verify` (backend),
  lint/typecheck/test/build (frontend), and the Playwright E2E suite on every
  push. Testcontainers and E2E require a Docker-capable runner.

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
| POST | `/auth/register`, `/auth/login`, `/auth/logout` | Session cookie auth; CSRF-exempt. Registration is session-free and returns a generic response |
| POST | `/auth/password-reset/request`, `/auth/password-reset/confirm` | Non-enumerating reset request; single-use token confirmation invalidates all sessions |
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
| GET | `/knowledge-graph/entities` | Merged entity index (library or one document), filters `type`, `q`, `limit` |
| GET | `/knowledge-graph/connections` | Document pairs that share entities |
| GET | `/verifications`, `/verifications/stats` | Stored claim-verification index and totals |
| GET | `/contradictions`, `/contradictions/stats` | Stored contradiction findings, class/severity totals |
| POST | `/contradictions/detect` | Run one bounded contradiction scan (bounded model call) |
| GET/POST/PATCH/DELETE | `/tags`, `/tags/{id}` | Tag management |
| GET/POST/PATCH/DELETE | `/collections`… | Collection management (see [docs/api.md](docs/api.md)) |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Probes (unauthenticated) |

Full request/response shapes: **[docs/api.md](docs/api.md)**.

Error responses share one envelope: `{ code, message, fieldErrors?, requestId }`.
Rate-limited responses (429) additionally carry a `Retry-After` header.

---

## Deployment

Two supported paths, both documented in **[docs/deployment.md](docs/deployment.md)**:

1. **Docker Compose (recommended):** `docker compose up -d --build` on the
   server, one published port, Cloudflare Tunnel in front.
2. **Host services:** systemd jar + host nginx (the original runbook), with
   Postgres still in Docker.

For quick temporary access from another device, **[docs/tailscale.md](docs/tailscale.md)**
publishes the Compose stack through Tailscale Funnel on port 8443 (public
internet exposure; `tailscale serve` keeps it tailnet-only).

Key production settings:
- `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` (HTTPS)
- `APP_BASE_URL` and `CORS_ALLOWED_ORIGINS` set to the public origin
- `POSTGRES_PASSWORD`, `GROQ_API_KEY`, `RATE_LIMIT_*` tuned for the deployment
- Host-based installs only: an absolute `UPLOAD_DIR` outside the web root

---

## License

MIT — see LICENSE file.
