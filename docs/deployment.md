# VerityAI Deployment Runbook

Self-hosted production deployment on a small server (tested target: an 8 GB
laptop running **Ubuntu Server 24.04 LTS**).

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

---

## 1. OS Preparation

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

## 2. Install Dependencies

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

## 3. Clone & Configure

```bash
git clone <your-repo-url> /opt/verityai
cd /opt/verityai
cp .env.example .env
nano .env
```

**Production `.env` changes** (everything else keeps its example value):

| Key | Production value | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | Quiet logs; secure cookie default (see `application-prod.yml`) |
| `GROQ_API_KEY` | your real key | Required for chat |
| `UPLOAD_DIR` | `/var/lib/verityai/uploads` | Absolute path outside the web root |
| `RATE_LIMIT_LOGIN_PER_MINUTE_IP` | e.g. `10` | Tune per your usage |
| `RATE_LIMIT_UPLOADS_PER_HOUR` | e.g. `10` | Tune per your usage |
| `RATE_LIMIT_AI_REQUESTS_PER_MINUTE` | e.g. `20` | Tune per your usage; `0` disables |

> The `prod` profile forces `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` by
> default — sessions only travel over HTTPS (correct behind the tunnel).

## 4. Build

```bash
# Database
cd /opt/verityai && docker compose up -d postgres

# Backend jar (tests need Docker; skip them for speed on the server)
cd /opt/verityai/backend && ./mvnw -B --no-transfer-progress clean package -DskipTests

# Frontend (VITE_API_BASE_URL is intentionally unset → same-origin /api/v1)
cd /opt/verityai/frontend && npm ci && npm run build
sudo mkdir -p /var/www/verityai
sudo cp -r dist/* /var/www/verityai/
```

> **Upgrading an existing install (Phase 2):** `docker-compose.yml` now uses
> the `pgvector/pgvector` image (PostgreSQL 17 + the `vector` extension).
> Recreating the container is enough — Flyway applies `V5` automatically on
> first backend start. If the old container was `postgres:17-alpine`, its data
> directory is owned by uid 70 while the pgvector image runs as uid 999; fix
> the ownership once, without touching your data:
>
> ```bash
> docker stop verityai-postgres
> docker run --rm -v verityai_postgres_data:/data alpine chown -R 999:999 /data
> docker compose up -d postgres   # recreates the container on the pgvector image
> ```
>
> Take a `pg_dump` backup first if this is anything but a dev machine.

## 5. Backend as a systemd Service

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

## 6. Nginx Reverse Proxy

`/etc/nginx/sites-available/verityai`:

```nginx
server {
    listen 80;
    server_name localhost;

    root /var/www/verityai;
    index index.html;

    # Static SPA
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Backend API
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 30m;   # ≥ MAX_FILE_SIZE_MB
    }
}
```

> `/actuator` is intentionally **not** proxied — health checks run on the
> server itself (`curl http://localhost:8080/...`), and the firewall below
> keeps :8080 local.

```bash
sudo ln -s /etc/nginx/sites-available/verityai /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl restart nginx
```

**Firewall** — expose only SSH + nginx (the backend's :8080 stays local,
so rate-limit keys see real client IPs only via nginx):

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
```

## 7. Public HTTPS via Cloudflare Tunnel

No router port-forwarding, works behind CGNAT:

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
    service: http://localhost:80
  - service: http_status:404
```

```bash
cloudflared tunnel route dns verityai-server verity.yourdomain.com
sudo cloudflared service install
```

Point your domain's DNS at the tunnel (the `route dns` command does this).

## 8. Backups

**Database** (daily cron, 14 days retained):

```bash
sudo mkdir -p /var/backups/verityai
```

`/etc/cron.daily/verityai-backup`:

```bash
#!/bin/sh
set -e
docker exec verityai-postgres pg_dump -U verityai verityai | gzip \
  > /var/backups/verityai/verityai-$(date +%F).sql.gz
find /var/backups/verityai -name '*.sql.gz' -mtime +14 -delete
tar czf /var/backups/verityai/uploads-$(date +%F).tar.gz -C /var/lib/verityai uploads
find /var/backups/verityai -name 'uploads-*.tar.gz' -mtime +14 -delete
```

```bash
sudo chmod +x /etc/cron.daily/verityai-backup
```

**Restore:**

```bash
gunzip -c /var/backups/verityai/verityai-YYYY-MM-DD.sql.gz \
  | docker exec -i verityai-postgres psql -U verityai -d verityai
```

## 9. Logs

The app logs to stdout → systemd journal:

```bash
journalctl -u verityai -f              # tail live
journalctl -u verityai --since today   # today's logs
```

Cap journal size in `/etc/systemd/journald.conf` (`SystemMaxUse=200M`),
then `sudo systemctl restart systemd-journald`.

Production log levels are set in
`backend/src/main/resources/application-prod.yml` (INFO app logs, no SQL
bind-parameter TRACE).

---

## Verification Checklist

After `systemctl restart verityai` + nginx:

1. `curl -fsS http://localhost:8080/actuator/health/liveness` → `{"status":"UP"}`
2. `curl -fsS http://localhost:8080/actuator/health/readiness` → `{"status":"UP"}`
3. Open `http://<server>/` → landing page loads
4. Register → login → upload a PDF → wait for `READY` → ask a question →
   answer includes citations
5. Exceed `RATE_LIMIT_AI_REQUESTS_PER_MINUTE` → response is `429` with a
   `Retry-After` header and the UI shows “Rate limit reached”

## Operations

```bash
# Deploy an update
cd /opt/verityai && git pull
cd backend && ./mvnw -B --no-transfer-progress clean package -DskipTests
cd ../frontend && npm ci && npm run build && sudo cp -r dist/* /var/www/verityai/
sudo systemctl restart verityai

# Rollback
cd /opt/verityai && git checkout <previous-tag-or-commit>
# rebuild + restart as above
```

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `401` on every API call from the browser over HTTPS | Session cookie marked secure but served over plain HTTP — finish the tunnel/HTTPS setup, or (LAN testing only) set `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` in `.env` |
| `403` on POST/DELETE from a custom client | Missing CSRF: fetch `GET /api/v1/auth/csrf`, send the `XSRF-TOKEN` cookie back as `X-XSRF-TOKEN` header |
| `429 RATE_LIMITED` / `AI_RATE_LIMITED` | Expected when quotas are exhausted; wait `Retry-After` seconds or raise `RATE_LIMIT_*` in `.env` |
| Chat answers “LLM call failed” | `GROQ_API_KEY` missing/invalid in `.env` (service runs without it) |
| Uploads stuck in `PROCESSING`/`FAILED` | Check `journalctl -u verityai`; `UPLOAD_DIR` must exist and be writable by the service user |
| Backend won't start: DB connection refused | `docker compose ps` — Postgres not up; `docker compose up -d postgres` |
