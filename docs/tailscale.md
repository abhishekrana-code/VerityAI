# Public access via Tailscale Funnel

A way to reach the Compose stack from anywhere without buying hosting. This is
a stop-gap deployment, not a production one.

> **Exposure warning.** Funnel publishes the service on the public internet:
> anyone with the URL reaches the login page, and the URL is discoverable. Use
> `serve` instead of `funnel` (see the bottom) if the app should stay
> tailnet-only.

## How the current setup works

`docker-compose.tailscale.yml` adds a **second, dedicated Tailscale node** —
`verityai`. Tailscale allows one DNS name per device, so this is how VerityAI
gets its own hostname without renaming the machine that already serves other
apps.

| Piece | Value |
|---|---|
| Node / hostname | `verityai` |
| Public URL | `https://verityai.<tailnet>.ts.net` (port 443, no port suffix) |
| Funnel target | `http://frontend:80` — the nginx container, over the Compose network |
| Node state | the `tailscale-state` volume (`tailscaled.state`) |
| Host port | none — nothing is published on the host |

Because the node talks to `frontend` by service name, it must join the network
that `docker-compose.yml` creates (`verityai_default`, referenced as the
external network `verityai`). **Start the main stack first.**

## Setup

### 1. Start the stack

```bash
docker compose up -d
```

Wait until `docker compose ps` reports `backend` and `frontend` healthy.

### 2. Point the app at the public URL

In `.env`:

```dotenv
APP_BASE_URL=https://verityai.<tailnet>.ts.net
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080,https://verityai.<tailnet>.ts.net
# Empty: the SPA calls /api/v1 on its own origin, which nginx proxies.
VITE_API_BASE_URL=
# Funnel terminates TLS, but nginx forwards X-Forwarded-Proto: http, so keep the
# session cookie usable over the https origin by reading the request scheme.
SERVER_SERVLET_SESSION_COOKIE_SECURE=true
```

`APP_BASE_URL` is what password-reset emails link to. Leaving it empty makes the
backend derive the origin from the browser request, which is fine for localhost
but not for a tunnel.

Recreate the backend so it picks up the new environment:

```bash
docker compose up -d backend
```

### 3. Start the Tailscale node

An auth key skips the interactive login prompt. The Compose service has **no
`env_file` entry**, so it does not read `.env.tailscale` by itself: Compose only
substitutes `${TS_AUTHKEY}` from its own environment. Supply the key one of two
ways.

**a) Shell environment (simplest).**

PowerShell:

```powershell
$env:TS_AUTHKEY = "tskey-auth-…"   # never commit this value
docker compose -f docker-compose.tailscale.yml up -d
```

bash/zsh:

```bash
export TS_AUTHKEY="tskey-auth-…"
docker compose -f docker-compose.tailscale.yml up -d
```

**b) Both env files at once.** `--env-file` is what makes Compose interpolate
`${TS_AUTHKEY}`; it does not mount the file into the container.

```bash
docker compose --env-file .env --env-file .env.tailscale \
  -f docker-compose.yml -f docker-compose.tailscale.yml up -d
```

`.env.*` is gitignored, so a key in `.env.tailscale` stays local. Never paste a
real key into this document, the compose file, or a commit.

**Without a key**, start the node alone and approve it interactively:

```bash
docker compose -f docker-compose.tailscale.yml up -d
docker logs -f verityai-tailscale     # prints a login URL
```

The custom entrypoint runs `tailscaled` directly and `tailscale up
--timeout=0s`, so the login URL stays valid until it is used (the image's
default `containerboot` aborts the login after 60 seconds and rotates the URL).

### 4. Verify

```bash
docker logs verityai-tailscale | tail -20    # should show "Funnel started"
curl -fsS https://verityai.<tailnet>.ts.net/ | head -5
curl -fsS -H "Origin: https://verityai.<tailnet>.ts.net" \
  https://verityai.<tailnet>.ts.net/api/v1/auth/csrf
```

The second call must return `Access-Control-Allow-Origin` with the tunnel
origin. If it does not, the origin is missing from `CORS_ALLOWED_ORIGINS` and
the browser will fail every mutating request with a 403.

## Notes and limits

- **Stop it:** `docker compose -f docker-compose.tailscale.yml down`. Funnel
  configuration is kept in the node state; starting the node again resumes it.
- **Re-login / new node:** the node identity lives in the `tailscale-state`
  volume. Removing that volume makes Tailscale treat this as a brand-new device
  (new approval, and the DNS name may be suffixed).
- **Funnel must be enabled** for the tailnet: admin console → DNS → HTTPS
  certificates and Funnel, plus a `nodeAttrs: funnel` grant in the policy.
- **Client IPs collapse.** nginx overwrites `X-Forwarded-For` with its own peer
  address (deliberately — see `frontend/nginx.conf`), and Funnel arrives from
  the Tailscale container, so every visitor shares one rate-limit bucket and one
  log address. Fine for sharing with one or two people, wrong for public
  multi-user traffic.
- **Scheme headers.** TLS is terminated by Tailscale; nginx forwards
  `X-Forwarded-Proto: http` (its own `$scheme`). Keep that in mind when debugging
  secure-cookie or origin behaviour.
- **Uploads** fit: `client_max_body_size 30m` in `frontend/nginx.conf` is above
  the backend's `MAX_FILE_SIZE_MB` (25 MB), so its own limit and message win.
- **Vite dev server.** `frontend/vite.config.ts` allowlists `.ts.net` hosts so
  the dev server or `vite preview` can sit behind a tunnel for debugging. Do not
  expose the *dev* server publicly: it can serve arbitrary repository files
  (including `.env`) through `/@fs/`. The Compose/nginx path above serves only
  the built bundle.

## Tailnet-only alternative

Replace `funnel` with `serve` in the entrypoint of
`docker-compose.tailscale.yml` (and drop the funnel node attribute):

```yaml
tailscale --socket=/tmp/tailscaled.sock serve --bg --https=443 http://frontend:80
```

On an already-running node the equivalent one-off is:

```bash
docker exec verityai-tailscale tailscale --socket=/tmp/tailscaled.sock \
  funnel --https=443 off
docker exec verityai-tailscale tailscale --socket=/tmp/tailscaled.sock \
  serve --bg --https=443 http://frontend:80
```
