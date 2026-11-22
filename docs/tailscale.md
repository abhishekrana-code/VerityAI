# Tailscale Funnel (temporary access)

A quick way to reach the locally running Compose stack from another device
without buying hosting. This is a stop-gap, not a production deployment.

> **Exposure warning.** `tailscale funnel` publishes the service on the public
> internet: anyone who has the URL can reach the login page, and the URL is
> discoverable. Use `tailscale serve` instead if the app should stay
> tailnet-only (command at the bottom).

## Layout used here

| Public URL | Proxies to | Purpose |
|---|---|---|
| `https://<host>.<tailnet>.ts.net` (443) | `http://127.0.0.1:5180` | A different app — deliberately left alone |
| `https://<host>.<tailnet>.ts.net:8443` | `http://127.0.0.1:8080` | VerityAI (nginx in `docker-compose.yml`) |

Funnel only accepts the ports `443`, `8443`, and `10000`, which is why the
existing 443 mapping cannot be reused for VerityAI.

## Setup

1. Start the stack (nginx on 8080, backend and Postgres on the Compose network):

   ```bash
   docker compose up -d
   ```

2. Point the app at the public URL in `.env`:

   ```dotenv
   APP_BASE_URL=https://<host>.<tailnet>.ts.net:8443
   CORS_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,https://<host>.<tailnet>.ts.net:8443
   # Empty: the SPA calls /api/v1 on its own origin, which nginx proxies.
   VITE_API_BASE_URL=
   ```

   `APP_BASE_URL` is what password-reset emails link to. Leaving it empty makes
   the backend derive the origin from the browser request, which is fine for
   localhost but not for a tunnel.

3. Recreate the backend so it picks up the new environment:

   ```bash
   docker compose up -d backend
   ```

4. Publish the port:

   ```bash
   tailscale funnel --bg --https=8443 8080
   tailscale funnel status
   ```

5. Verify from anywhere:

   ```bash
   curl -fsS https://<host>.<tailnet>.ts.net:8443/ | head -5
   curl -fsS -H "Origin: https://<host>.<tailnet>.ts.net:8443" \
     https://<host>.<tailnet>.ts.net:8443/api/v1/auth/csrf
   ```

   The second call must return `Access-Control-Allow-Origin` with the tunnel
   origin; otherwise the origin is missing from `CORS_ALLOWED_ORIGINS` and the
   browser will fail every mutating request.

## Notes and limits

- **Stop it:** `tailscale funnel --https=8443 off`.
- **Funnel must be enabled** for the tailnet (admin console → DNS → HTTPS
  certificates and Funnel, plus an `nodeAttrs: funnel` grant in the policy).
- **Client IPs collapse.** nginx overwrites `X-Forwarded-For` with its own peer,
  and Funnel connects from loopback, so every visitor shares one rate-limit
  bucket and one log address. Acceptable for sharing with one or two people,
  wrong for public multi-user traffic.
- **TLS is terminated by Tailscale.** Keep `SERVER_SERVLET_SESSION_COOKIE_SECURE`
  as-is for a mixed local/HTTPS setup; set it to `true` once plain HTTP is gone.
- **Uploads** already fit: `client_max_body_size 30m` in `frontend/nginx.conf`
  matches the backend's `MAX_FILE_SIZE_MB`.
- **Vite dev server.** `frontend/vite.config.ts` binds to `127.0.0.1` and
  allowlists `.ts.net` hosts so the dev server or `vite preview` can sit behind
  a tunnel for debugging. Do not expose the *dev* server publicly: it can serve
  arbitrary files from the repository (including `.env`) through `/@fs/`. The
  Compose/nginx path above serves only the built bundle.

## Tailnet-only alternative

Replace `funnel` with `serve`; nothing else changes:

```bash
tailscale funnel --https=8443 off
tailscale serve --bg --https=8443 8080
```
