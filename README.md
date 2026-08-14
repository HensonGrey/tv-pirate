# tv-pirate — backend

Spring Boot backend for the tv-pirate learning project.

**Stack:** Java 21 · Spring Boot 4.1 · Spring Security 7 · Spring Data JPA (Hibernate) · PostgreSQL · jjwt

## Features

- **Guest login** (`POST /api/auth/guest`) — one-click account, no password anywhere in the schema. Google OAuth is planned (button stub on the frontend), credentials don't exist yet.
- **httpOnly cookie session** — JWT access token (15 min) + opaque refresh token (30 days) delivered as `HttpOnly`, `SameSite=Lax` cookies. JS never sees them.
- **Refresh rotation** — refresh tokens are SHA-256 hashed in the DB and burned on use (replay → 401), so sessions renew silently and indefinitely.
- **Session probe** (`GET /api/me`) — the frontend's way to answer "am I logged in?" without touching token storage.
- **No hardcoded config** — DB, CORS origins, cookie Secure flag and JWT TTLs are all env-driven.

## Endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/guest` | public | guest account + token-pair cookies |
| `POST /api/auth/refresh` | cookie | rotate: burn old refresh token, issue new pair |
| `POST /api/auth/logout` | cookie | burn refresh token, expire cookies |
| `GET /api/me` | protected | session probe |
| `GET /api/hello` | protected | demo endpoint |
| `GET /api/public/hello` | public | demo endpoint |

The JWT filter reads the `access_token` cookie first and falls back to the `Authorization: Bearer` header (curl/Postman).

## Run

```powershell
# 1. copy .env.example to .env and fill in DB_PASSWORD / JWT_SECRET
cp .env.example .env

# 2. run (must be executed FROM backend/ — the .env import is relative)
.\mvnw.cmd spring-boot:run
```

Requires a local PostgreSQL database named `tv-pirate` (schema auto-managed via `ddl-auto=update` — fine for learning, swap to Flyway before production).

Frontend: [tv-pirate-frontend](https://github.com/HensonGrey/tv-pirate-frontend)
