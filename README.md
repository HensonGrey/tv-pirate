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

Frontend: [tv-pirate-frontend](https://github.com/HensonGrey/tv-pirate-frontend)
