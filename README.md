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
├─ docs/              # Architecture, implementation plan, deployment runbook
├─ docker-compose.yml # Postgres 17 (dev database)
├─ .env.example       # Template for environment variables
└─ README.md          # This file
```

---

## Key Features

- **Evidence-based answers** — Every claim cites a document chunk with page number
- **Document library** — Upload, process, search, retry failed PDFs
- **Research workspace** — Chat interface with citation panel, conversation history
- **Secure by default** — Session auth, HttpOnly cookies, CSRF, ownership enforcement
- **Rate limited** — Per-IP login and per-user upload/AI quotas return 429 with `Retry-After`
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

Everything else has working defaults. Note: the backend's JDBC credentials
are set in `backend/src/main/resources/application.yml` — keep
`POSTGRES_PASSWORD` at its default (`verityai_dev`), or change both together.

---

## Testing & CI

- **Backend**: 39 tests — JUnit 5 + Mockito + Testcontainers (PostgreSQL 17 in Docker).
  Run `cd backend && .\mvnw.cmd test` (Docker must be running).
- **Frontend**: 12 tests — Vitest + jsdom. Run `cd frontend && npm test`.
- **CI**: `.github/workflows/ci.yml` runs `mvnw verify` (backend) and lint/typecheck/test/build
  (frontend) on every push. Testcontainers requires a Docker-capable runner.

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
| GET/PATCH/DELETE | `/conversations/{id}` | Detail, rename/archive, delete |
| GET/POST | `/conversations/{id}/messages` | Read history / ask a question (RAG + citations) |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Probes (unauthenticated) |

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