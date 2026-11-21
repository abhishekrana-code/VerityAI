# VerityAI Deployment Runbook

Self-hosted deployment on a small server (tested target: an 8 GB laptop running
**Ubuntu Server 24.04 LTS**). Two supported topologies:

| | A — Docker Compose (recommended) | B — Host services |
|---|---|---|
| Backend | container | systemd + jar |
| Frontend | nginx container | host nginx, static files |
| Database | container | container |
| Updates | `docker compose up -d --build` | rebuild + `systemctl restart` |
| Best for | one-command deploys, identical to dev | existing installs, custom host nginx |

```
A. Docker Compose
Internet ──► Cloudflare Tunnel ──► host :8080
                                      │
                                      ▼
                        nginx container (SPA + /api/)
                                      │
                                      ▼
                        backend container :8080 (not published)
                                      │
                                      ▼
                        postgres container :5432 (127.0.0.1:5434 on host)
```

---

# A. Docker Compose deployment

## A1. Prerequisites

```bash
sudo apt update
sudo apt install -y git
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER      # re-login afterwards
docker compose version             # needs >= 2.24 (env_file.required)
```

Disk: ~3 GB for images plus uploads. The first build downloads Maven/npm
dependencies inside the images, so give it a few minutes and outbound network.
No Java or Node installation is needed on the server.

## A2. Clone & configure

```bash
git clone <your-repo-url> /opt/verityai
cd /opt/verityai
cp .env.example .env
nano .env
```

Production `.env` changes (everything else keeps its example value):

| Key | Production value | Why |
|---|---|---|
| `POSTGRES_PASSWORD` | strong random value | Used by the Postgres container and the backend together |
| `GROQ_API_KEY` | your real key | Required for chat, summaries, verification |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `true` | Sessions must only travel over HTTPS. The compose file defaults it to `false` so plain-HTTP localhost works; set `true` behind the tunnel |
| `APP_BASE_URL` | `https://verity.yourdomain.com` | Origin used in password-reset emails. **Required in production**; when empty the link is derived from the browser `Origin` (which must be in `CORS_ALLOWED_ORIGINS`), otherwise no reset email is sent |
| `CORS_ALLOWED_ORIGINS` | `https://verity.yourdomain.com` | Explicit allowed origin (credentials enabled; no `*`) |
| `MAIL_HOST` | e.g. `smtp.gmail.com` | Enables password-reset email; leave empty to disable |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP credentials | Sender account / app password |
| `MAIL_FROM` | e.g. `no-reply@yourdomain.com` | From address (defaults to `MAIL_USERNAME`) |
| `EMBEDDINGS_PROVIDER` | `onnx` (or `off`) | `onnx` needs one-time outbound download of the MiniLM model, cached in a volume; `off` = keyword-only search |
| `RATE_LIMIT_LOGIN_PER_MINUTE_IP` | e.g. `10` | Tune per your usage |
| `RATE_LIMIT_REGISTER_PER_HOUR_IP` | e.g. `10` | Registration abuse limit |
| `RATE_LIMIT_PASSWORD_RESET_PER_HOUR_IP` / `..._ACCOUNT` | e.g. `5` / `3` | Reset-email abuse limits |
| `RATE_LIMIT_UPLOADS_PER_HOUR` | e.g. `10` | Tune per your usage |
| `RATE_LIMIT_AI_REQUESTS_PER_MINUTE` | e.g. `20` | Tune per your usage; `0` disables |

`SPRING_PROFILES_ACTIVE` in `.env` is ignored by the containers — compose always
runs the `prod` profile (quiet logs, no SQL bind-parameter TRACE). DB host,
upload/model directories and the Compose-internal proxy trust are also fixed in
`docker-compose.yml`, not taken from `.env`.

## A3. Start

```bash
cd /opt/verityai
docker compose up -d --build
docker compose ps            # wait for backend/frontend/postgres: (healthy)
```

URL: **http://&lt;server&gt;:8080** → landing page, register, upload a PDF, wait
for `READY`, ask a question.

The services use `restart: unless-stopped`, so they come back after a reboot
provided Docker is enabled at boot (`sudo systemctl enable docker`).

## A4. Ports

| Port | Service | Exposure |
|---|---|---|
| `APP_PORT` (default 8080) | nginx container: SPA + `/api/v1/*` | published — the only public port |
| 5434 | PostgreSQL | `127.0.0.1` only, for `psql`/Flyway on the host |
| 8080 (in-container) | Spring Boot | not published; reached through nginx |

`/actuator` is deliberately not proxied. Check readiness on the server:

```bash
docker compose exec backend curl -fsS localhost:8080/actuator/health/readiness
docker compose ps
```

## A5. Volumes and the non-root backend

| Volume | Container path | Contents |
|---|---|---|
| `<project>_uploads` | `/var/lib/verityai/uploads` | uploaded PDFs |
| `<project>_embeddings` | `/var/lib/verityai/models` | ONNX MiniLM cache |
| `<project>_postgres_data` | `/var/lib/postgresql/data` | database |

`<project>` is the directory name (`verityai_*` for `/opt/verityai`); confirm
with `docker volume ls`.

The JVM runs as uid/gid **10001 (`verity`)**. Named volumes are created
root-owned, so `backend/docker-entrypoint.sh` chowns the two writable
directories (top level only) and then drops root via `setpriv`. Practical
consequences:

- Uploaded files are owned by uid 10001 inside the volume, not by your host
  user. Read/inspect them with
  `docker compose exec backend ls -l /var/lib/verityai/uploads`; a host `tar`
  over the Docker volume path needs root.
- Files written by an older root-running container stay `root:root 0644` and
  remain readable, so upgrades need no data migration.
- `docker compose exec` opens a root shell (the image sets no default `USER`),
  which is exactly what lets the entrypoint fix volume ownership on every start.
- Never `chown -R` the Postgres volume or delete volumes to "fix" permissions:
  `docker compose down -v` destroys the database and uploads.

## A6. Public HTTPS via Cloudflare Tunnel

```bash
curl -L --output cloudflared.deb \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared.deb
cloudflared tunnel login
cloudflared tunnel create verityai-server
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: <TUNNEL_UUID>
credentials-file: /home/ubuntu/.cloudflared/<TUNNEL_UUID>.json
ingress:
  - hostname: verity.yourdomain.com
    service: http://localhost:8080
  - service: http_status:404
```

```bash
cloudflared tunnel route dns verityai-server verity.yourdomain.com
sudo cloudflared service install
```

Then set `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`, `APP_BASE_URL` and
`CORS_ALLOWED_ORIGINS` in `.env` and `docker compose up -d`.

> **Client-IP caveat behind the tunnel.** The frontend nginx overwrites
> `X-Forwarded-For` with its own peer address, and the backend trusts that
> header only from the Compose bridge (preset in `docker-compose.yml`). Requests
> arriving through the published port all come from the Docker bridge gateway,
> so per-IP rate limits and request logs see that single address for every
> visitor. That is fine for a small private deployment but wrong for public
> multi-user traffic. Either use topology B (host nginx, which already resolves
> the real client), or mount an nginx config that resolves Cloudflare's header:
>
> ```yaml
> # docker-compose.override.yml
> services:
>   frontend:
>     volumes:
>       - ./deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro
> ```
>
> Copy `frontend/nginx.conf` to `deploy/nginx.conf` and add inside `server {}`:
>
> ```nginx
> set_real_ip_from 172.16.0.0/12;
> set_real_ip_from 10.0.0.0/8;
> real_ip_header CF-Connecting-IP;
> real_ip_recursive on;
> ```
>
> Only do this if port 8080 is not reachable except from the local tunnel —
> otherwise a client could spoof `CF-Connecting-IP`.

## A7. Updates and rollback

```bash
cd /opt/verityai && git pull
docker compose up -d --build      # Flyway migrations run on backend start
```

```bash
# Rollback
git checkout <previous-tag-or-commit>
docker compose up -d --build
```

Images are rebuilt only from this checkout; no registry is involved.

## A8. Backups

`/etc/cron.daily/verityai-backup`:

```bash
#!/bin/sh
set -e
mkdir -p /var/backups/verityai

# Database (pg_dump from inside the container)
docker exec verityai-postgres pg_dump -U verityai verityai | gzip \
  > /var/backups/verityai/verityai-$(date +%F).sql.gz

# Uploads (named volume; adjust the prefix if the project directory is not "verityai")
docker run --rm -v verityai_uploads:/data:ro -v /var/backups/verityai:/backup alpine \
  tar czf /backup/uploads-$(date +%F).tar.gz -C /data .

find /var/backups/verityai -name '*.sql.gz' -mtime +14 -delete
find /var/backups/verityai -name 'uploads-*.tar.gz' -mtime +14 -delete
```

```bash
sudo chmod +x /etc/cron.daily/verityai-backup
```

**Restore:**

```bash
gunzip -c /var/backups/verityai/verityai-YYYY-MM-DD.sql.gz \
  | docker exec -i verityai-postgres psql -U verityai -d verityai
docker run --rm -v verityai_uploads:/data -v /var/backups/verityai:/backup alpine \
  tar xzf /backup/uploads-YYYY-MM-DD.tar.gz -C /data
docker compose exec backend sh -c 'chown verity:verity /var/lib/verityai/uploads'
```

## A9. Logs

```bash
docker compose logs -f backend     # app logs (stdout)
docker compose logs -f frontend    # nginx access/error logs
docker compose logs --tail 200 postgres
```

Cap Docker's log growth (e.g. `/etc/docker/daemon.json` with
`{"log-opts":{"max-size":"10m","max-file":"3"}}`, then restart Docker) or the
journal fills the disk on a laptop server.

---

# B. Host services (alternative)

The original topology: backend jar under systemd, static SPA on host nginx,
Postgres in Docker. Use it if you already run this install or need custom
host-level nginx config (e.g. precise client-IP handling without the caveat
above).

```
Internet ──► Cloudflare Tunnel (HTTPS, no open router ports)
                │
                ▼
            nginx :80 ── static SPA (/var/www/verityai)
                │  └── /api/ ──► Spring Boot :8080 (systemd)
                │                     │
                │                     ▼
                │              PostgreSQL 17 (Docker, port 5434)
                └── /actuator is NOT proxied — backend port is firewalled
```

## B1. OS preparation

1. Flash a USB drive with **Ubuntu Server 24.04 LTS** (minimal install, no
   desktop GUI) and install with the **OpenSSH server** role selected.
2. Prevent suspend when the lid is closed (laptop servers):
   ```bash
   sudo nano /etc/systemd/logind.conf
   # uncomment and set:
   HandleLidSwitch=ignore
   HandleLidSwitchExternalPower=ignore
   HandleLidSwitchDocked=ignore
   sudo systemctl restart systemd-logind
   ```

## B2. Install dependencies

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git ufw nginx openjdk-21-jdk-headless

# Docker Engine (for PostgreSQL)
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER   # re-login afterwards

# Node.js 20+ (frontend build)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

## B3. Clone & configure

```bash
git clone <your-repo-url> /opt/verityai
cd /opt/verityai
cp .env.example .env
nano .env
```

Production `.env` changes (same keys as A2, plus the profile and upload path):

| Key | Production value | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | Quiet logs; secure cookie default (see `application-prod.yml`) |
| `POSTGRES_PASSWORD` | a strong random value | Used by both Docker Compose and the backend |
| `GROQ_API_KEY` | your real key | Required for chat |
| `UPLOAD_DIR` | `/var/lib/verityai/uploads` | Absolute path outside the web root |
| `APP_BASE_URL` | `https://verity.yourdomain.com` | Required for password-reset links |
| `CORS_ALLOWED_ORIGINS` | `https://verity.yourdomain.com` | Explicit allowed origin |
| `MAIL_*` | SMTP values | Password-reset email; empty disables sending |
| `RATE_LIMIT_*` | tune | See A2 |

## B4. Build

```bash
# Database only — do not run the full compose stack in this topology
cd /opt/verityai && docker compose up -d postgres

# Backend jar (tests need Docker; skip them for speed on the server).
# The POM repackages the executable jar, so target/*.jar is runnable.
cd /opt/verityai/backend && ./mvnw -B --no-transfer-progress clean package -DskipTests

# Frontend (VITE_API_BASE_URL is intentionally unset → same-origin /api/v1)
cd /opt/verityai/frontend && npm ci && npm run build
sudo mkdir -p /var/www/verityai
sudo cp -r dist/* /var/www/verityai/
```

> **Upgrading an existing install:** `docker-compose.yml` uses the
> `pgvector/pgvector` image (PostgreSQL 17 + the `vector` extension). Recreating
> the container is enough — Flyway applies pending migrations on first backend
> start. If the old container was `postgres:17-alpine`, its data directory is
> owned by uid 70 while the pgvector image runs as uid 999; fix the ownership
> once, without touching your data:
>
> ```bash
> docker stop verityai-postgres
> docker run --rm -v verityai_postgres_data:/data alpine chown -R 999:999 /data
> docker compose up -d postgres   # recreates the container on the pgvector image
> ```
>
> Take a `pg_dump` backup first if this is anything but a dev machine.

## B5. Backend as a systemd service

`/etc/systemd/system/verityai.service`:

```ini
[Unit]
Description=VerityAI Backend
After=network.target docker.service
Wants=docker.service

[Service]
User=ubuntu
WorkingDirectory=/opt/verityai/backend
ExecStart=/usr/bin/java -Xms256m -Xmx768m -jar /opt/verityai/backend/target/verityai-backend-0.1.0-SNAPSHOT.jar
Restart=always
RestartSec=10
EnvironmentFile=/opt/verityai/.env

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now verityai
sudo systemctl status verityai
```

The uploads directory must exist and be writable by the service user:

```bash
sudo mkdir -p /var/lib/verityai/uploads
sudo chown -R ubuntu:ubuntu /var/lib/verityai/uploads
```

## B6. Nginx reverse proxy

`/etc/nginx/sites-available/verityai`:

```nginx
server {
    listen 80;
    server_name localhost;

    root /var/www/verityai;
    index index.html;

    # Cloudflare Tunnel connects from loopback. Trust only that peer to supply
    # the client IP, and take it from CF-Connecting-IP (set by Cloudflare).
    set_real_ip_from 127.0.0.1;
    set_real_ip_from ::1;
    real_ip_header CF-Connecting-IP;
    real_ip_recursive on;

    # Static SPA
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Backend API
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # OVERWRITE, never append: a client-supplied X-Forwarded-For must not
        # survive into the backend, which trusts loopback proxy headers.
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 30m;   # ≥ MAX_FILE_SIZE_MB
    }
}
```

> **Client-IP correctness matters for rate limiting.** The backend honours
> `X-Forwarded-For` only from peers matching `TRUSTED_PROXY_PATTERN`
> (loopback by default). With Cloudflare Tunnel, `real_ip_header
> CF-Connecting-IP` makes `$remote_addr` the real visitor; without Cloudflare,
> `$remote_addr` is already the client.

> `/actuator` is intentionally **not** proxied — health checks run on the
> server itself (`curl http://localhost:8080/...`), and the firewall keeps
> :8080 local.

```bash
sudo ln -s /etc/nginx/sites-available/verityai /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl restart nginx
```

**Firewall** — expose only SSH + nginx:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
```

## B7. Public HTTPS via Cloudflare Tunnel

As in A6, but point the tunnel at host nginx:

```yaml
ingress:
  - hostname: verity.yourdomain.com
    service: http://localhost:80
  - service: http_status:404
```

## B8. Backups (host topology)

```bash
#!/bin/sh
set -e
docker exec verityai-postgres pg_dump -U verityai verityai | gzip \
  > /var/backups/verityai/verityai-$(date +%F).sql.gz
tar czf /var/backups/verityai/uploads-$(date +%F).tar.gz -C /var/lib/verityai uploads
find /var/backups/verityai -name '*.sql.gz' -mtime +14 -delete
find /var/backups/verityai -name 'uploads-*.tar.gz' -mtime +14 -delete
```

## B9. Logs

```bash
journalctl -u verityai -f              # tail live
journalctl -u verityai --since today   # today's logs
```

Cap journal size in `/etc/systemd/journald.conf` (`SystemMaxUse=200M`), then
`sudo systemctl restart systemd-journald`.

---

## Verification checklist

After `docker compose up -d --build` (A) or `systemctl restart verityai` +
nginx (B):

1. `docker compose ps` → backend/frontend/postgres `(healthy)`; or
   `curl -fsS http://localhost:8080/actuator/health/liveness` → `{"status":"UP"}`
2. Open the URL (`http://<server>:8080` for A, `http://<server>/` for B) → landing page loads
3. Register → sign in (registration does **not** create a session) → upload a PDF →
   wait for `READY` → ask a question → answer includes citations
4. Exceed `RATE_LIMIT_AI_REQUESTS_PER_MINUTE` → response is `429` with a
   `Retry-After` header and the UI shows “Rate limit reached”
5. If `MAIL_HOST` is configured: request a password reset, open the emailed
   link, set a new password, and confirm the old session is signed out
6. Sign in from a second browser → the first session receives `401` (single
   session per account)
7. Upload a non-PDF renamed to `.pdf` → `400 VALIDATION_ERROR` (magic-byte gate)
8. From a foreign `Origin`, send a CORS preflight → rejected; the configured
   `CORS_ALLOWED_ORIGINS` origin is allowed with credentials

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Compose fails: `env_file` / `required` unsupported | Docker Compose < 2.24 — upgrade Docker Engine/Compose |
| `port is already allocated` on 8080 | Something else listens on `APP_PORT`; set `APP_PORT=3000` in `.env` or stop the other process |
| Backend container restarts at startup | `docker compose logs backend`; usually a wrong `POSTGRES_PASSWORD` (existing volume keeps the old password) or a DB that is not healthy yet |
| Uploads return `500` / `Permission denied` after manually messing with volumes | Restart the backend so the entrypoint re-chowns the uploads dir; never delete the volume to fix permissions |
| `401` on every API call from the browser over HTTPS | Session cookie marked secure but served over plain HTTP — finish the tunnel/HTTPS setup, or (LAN testing only) set `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` and `docker compose up -d` |
| `403` on POST/DELETE from a custom client | Missing CSRF: fetch `GET /api/v1/auth/csrf`, send the `XSRF-TOKEN` cookie back as `X-XSRF-TOKEN` header |
| `429 RATE_LIMITED` / `AI_RATE_LIMITED` | Expected when quotas are exhausted; wait `Retry-After` or raise `RATE_LIMIT_*` in `.env` |
| Everyone shares one rate-limit bucket behind the tunnel | Docker publishes from the bridge gateway; see the client-IP caveat in A6 |
| Chat answers “LLM call failed” | `GROQ_API_KEY` missing/invalid in `.env` (the service runs without it) |
| Password-reset email never arrives | `MAIL_HOST`/credentials unset, or the SMTP provider rejected the sender; reset requests still return `200` by design. Gmail needs an App Password, not the account password. Check the logs for mail errors; if the log says the reset link cannot be built, set `APP_BASE_URL` |
| Reset link opens "site can't be reached" | The link points at a host that is not reachable from the browser. Set `APP_BASE_URL` to the public URL |
| `429 RATE_LIMITED` on registration/reset | The per-IP (and per-account) pre-auth limits are working; wait `Retry-After` or raise the `RATE_LIMIT_*` values |
| Upload returns `503 QUEUE_FULL` | The bounded document-processing queue is saturated; retry after `Retry-After` (the file is not stored) |
| Uploads stuck in `PROCESSING`/`FAILED` | `docker compose logs backend` (A) or `journalctl -u verityai` (B); the uploads dir must be writable by the app user |
| `no main manifest attribute` in backend logs | A jar built before the POM bound `spring-boot:repackage`; rebuild the image/jar |
