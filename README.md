# tv-pirate — backend

Spring Boot backend for the tv-pirate learning project.

**Stack:** Java 21 · Spring Boot 4.1 · Spring Security 7 · Spring Data JPA (Hibernate) · PostgreSQL · jjwt

## Features

- **Guest login** (`POST /api/auth/guest`) — one-click account, no password anywhere in the schema.
- **Google sign-in** — server-side authorization-code redirect: a `state` cookie guards against login CSRF, and accounts are keyed on Google's permanent `sub`, not the email. Needs `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` in `.env`, plus `http://localhost:8080/api/auth/google/callback` registered as a redirect URI on the OAuth client.
- **Account deletion** (`DELETE /api/auth/account`) — the FK cascade removes everything the user owns.
- **httpOnly cookie session** — JWT access token (15 min) + opaque refresh token (30 days) delivered as `HttpOnly`, `SameSite=Lax` cookies. JS never sees them.
- **Refresh rotation** — refresh tokens are SHA-256 hashed in the DB and burned on use (replay → 401), so sessions renew silently and indefinitely.
- **Session probe** (`GET /api/me`) — the frontend's way to answer "am I logged in?" without touching token storage.
- **Provider profile pictures** — `users.profile_picture_url` stores the avatar URL the provider hands over for free (Google `picture` claim); guests get null and the frontend falls back to the default avatar.
- **No hardcoded config** — DB, CORS origins, cookie Secure flag and JWT TTLs are all env-driven.

## Endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/guest` | public | guest account + token-pair cookies |
| `POST /api/auth/refresh` | cookie | rotate: burn old refresh token, issue new pair |
| `GET /api/auth/google` | public | redirect to Google's consent screen |
| `GET /api/auth/google/callback` | public | Google returns here; sets the token-pair cookies and redirects to the frontend |
| `POST /api/auth/logout` | cookie | burn refresh token, expire cookies |
| `DELETE /api/auth/account` | cookie | delete the account, expire cookies |
| `GET /api/me` | protected | session probe |

The JWT filter reads the `access_token` cookie first and falls back to the `Authorization: Bearer` header (curl/Postman).

## Run

```powershell
# 1. copy .env.example to .env and fill in DB_PASSWORD / JWT_SECRET
cp .env.example .env

# 2. run (start.cmd finds JAVA_HOME and runs from backend/, where the .env import expects it)
.\start
```

Requires a local PostgreSQL database named `tv-pirate`. Schema is managed by **Liquibase migrations** (every change has an up + `--rollback` down, see `src/main/resources/db/changelog/` — that folder's `agents.md` has the workflow); Hibernate runs in `validate` mode and never alters the DB.

## Before deploying (Oracle Cloud VM + Caddy)

- [ ] **Credentials** below: create the prod values and put them in the VM's `.env`.
- [ ] **Privacy notice** page, linked from the app: who runs it, what is stored and why, retention, rights and the CPDP complaint route.
- [ ] **Caddy:** only 80/443 public; 8080 stays private. Raise the at-once caps in `RateLimitPolicy` together with `server.tomcat.threads.max`.
- [ ] **Env switches:** `COOKIE_SECURE=true`, `CORS_ALLOWED_ORIGINS` and `FRONTEND_URL` set to the prod origin, `RATE_LIMIT_ENABLED=true`.
- [ ] **Frontend build:** `VITE_API_BASE_URL` is read at build time, so build with the prod API URL.
- [ ] **New Relic:** unzip the [Java agent](https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip) to `/opt/newrelic`, start with `java -javaagent:/opt/newrelic/newrelic.jar -jar <app>.jar` (the flag goes before `-jar`), and pass `.env` to the process as real env vars, since the agent starts before Spring's `.env` import.

### Credentials per environment

Names only, never commit the values. Stream providers (Videasy, Vixsrc) need no key.

| Service | Env var(s) | Required | Dev | Prod |
|---|---|---|---|---|
| Postgres | `DB_USERNAME`, `DB_PASSWORD`, `DB_HOST`, `DB_PORT` | yes | local `tv-pirate` DB | the VM's Postgres, with its own user and password |
| JWT signing | `JWT_SECRET` | yes | any 32+ byte random value | a **new** random value; changing it later signs everyone out |
| TMDB | `TMDB_READ_ACCESS_TOKEN` | yes | the v4 read token from themoviedb.org/settings/api | the same token works, or create a second one to keep them apart |
| OpenSubtitles | `OPENSUBTITLES_API_KEY` | no, no captions without it | the key from opensubtitles.com/consumers | the same key, mind the small daily quota; a separate consumer for prod keeps the quota apart |
| Google sign-in | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` | no, guests only without it | OAuth client with `http://localhost:8080/api/auth/google/callback` registered | a separate OAuth client; register `https://<api-domain>/api/auth/google/callback` exactly as `GOOGLE_REDIRECT_URI`, and publish the consent screen or add each user as a test user |
| Cloudflare Turnstile | `TURNSTILE_SECRET_KEY` (FE: `VITE_TURNSTILE_SITE_KEY`) | yes in prod, guest creation skips the bot check without it | Cloudflare's always-pass test keys, or the real widget with `localhost` on its hostname list | the real widget's secret, with the prod FE domain on its hostname list |
| New Relic | `NEW_RELIC_LICENSE_KEY`, `NEW_RELIC_APP_NAME` (querying only: `NEW_RELIC_ACCOUNT_ID`, `NEW_RELIC_USER_KEY`) | no, logs stay on the console without it | usually no agent; if you run one, a separate app name like `tv-pirate-backend-dev` | an `Ingest - License` key from one.eu.newrelic.com/api-keys, shown in full only once at creation; app name `tv-pirate-backend-prod` |

**Scripts and e2e suites:** don't blank the Turnstile keys to get past the bot check, use Cloudflare's test keys so the real flow runs. Pair always-pass secret `1x0000000000000000000000000000000AA` with site key `1x00000000000000000000AA`. A script calling `POST /api/auth/guest` directly sends `{"turnstileToken":"XXXX.DUMMY.TOKEN.XXXX"}`. Secret `2x0000000000000000000000000000000AA` always fails and `3x0000000000000000000000000000000AA` answers "already spent", for testing the error toasts. Test keys still call Cloudflare, so an offline run must blank both keys, which turns the check off. Guest creation is also rate limited (`RATE_LIMIT_ENABLED=false` for scripted runs).

Frontend: [tv-pirate-frontend](https://github.com/HensonGrey/tv-pirate-frontend)
