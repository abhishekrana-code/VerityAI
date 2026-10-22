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
| Deployment | Docker Compose, Nginx reverse proxy, Windows host |

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
├─ deployment/        # Nginx, compose overrides, scripts
├─ docs/              # Architecture, API, deployment guides
├─ docker-compose.yml # Postgres (dev) + full stack (prod)
├─ .env.example       # Template for environment variables
└─ README.md          # This file
```

---

## Key Features

- **Evidence-based answers** — Every claim cites a document chunk with page number
- **Document library** — Upload, process, search, retry failed PDFs
- **Research workspace** — Chat interface with citation panel, conversation history
- **Secure by default** — Session auth, HttpOnly cookies, CSRF, ownership enforcement
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
- `GROQ_API_KEY` — from https://console.groq.com/keys
- `POSTGRES_PASSWORD` — if different from default

---

## API Documentation

When running, OpenAPI UI available at:
- `http://localhost:8080/swagger-ui.html` (if springdoc enabled)
- `http://localhost:8080/api-docs` (JSON spec)

---

## Deployment (Windows Host)

See `deployment/README.md` for:
- Production Docker Compose
- Nginx configuration with HTTPS (self-signed or Let's Encrypt)
- Docker Desktop auto-start on boot
- Database backup/restore scripts
- Log rotation

---

## License

MIT — see LICENSE file.