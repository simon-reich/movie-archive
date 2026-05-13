# MovieArchive

A personal, searchable film archive. Search movies via TMDB, enrich them with OMDB and Wikipedia data, and find everything again with full-text search powered by OpenSearch.

**Tech stack:** Nuxt 3 + Spring Boot 3 + PostgreSQL 16 + OpenSearch 2.x + Caddy

---

## Prerequisites

- Docker + Docker Compose
- Java 21 (for local backend development)
- Node.js 20+ + pnpm (for local frontend development)

---

## Quick Start

```bash
# 1. Copy and configure environment variables
cp .env.example .env
# Edit .env — set DB_PASSWORD, JWT_SECRET, ENCRYPTION_MASTER_KEY

# 2. Start all services
docker compose up -d

# 3. Open the app
open http://localhost

# 4. Inspect mails (dev SMTP)
open http://localhost:8025
```

Services:

| Service | URL |
|---|---|
| App | http://localhost |
| Backend API | http://localhost/api |
| Swagger UI | http://localhost/api/swagger-ui.html |
| Mailpit (mail UI) | http://localhost:8025 |
| OpenSearch Dashboards | http://localhost:5601 |

---

## Local Development

### Backend

```bash
cd backend
./gradlew bootRun
# Runs on port 8080. Expects Postgres + OpenSearch from docker compose.
```

Run tests:

```bash
./gradlew test
./gradlew jacocoTestReport   # coverage report in build/reports/jacoco/
```

### Frontend

```bash
cd frontend
pnpm install
pnpm dev
# Runs on port 3000.
```

Run tests:

```bash
pnpm test           # unit + component tests (Vitest)
pnpm test:e2e       # E2E tests (Playwright) — requires full stack running
```

---

## SMTP Configuration (Production)

Set the following ENV vars (in `.env` or your deployment config):

```env
MAIL_HOST=smtp.brevo.com
MAIL_PORT=587
MAIL_USERNAME=your@email.com
MAIL_PASSWORD=your-smtp-key
MAIL_FROM=noreply@yourdomain.com
```

Recommended providers:
- **Brevo** — 300 mails/day free, [brevo.com](https://brevo.com)
- **Resend** — 3,000 mails/month free, [resend.com](https://resend.com)

---

## API Keys

After signing up, go to **Settings** to add your API keys:

- **TMDB API key** (required) — [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api)
- **OMDB API key** (optional, enriches IMDB ratings + content rating + box office) — [omdbapi.com/apikey.aspx](https://www.omdbapi.com/apikey.aspx)

Keys are stored AES-256-GCM encrypted in the database.

---

## Project Structure

```
movie-archive/
├── backend/          # Spring Boot 3 + Java 21
├── frontend/         # Nuxt 3 + Vue 3 + TypeScript
├── docker-compose.yml
├── Caddyfile         # Reverse proxy config
├── .env.example      # Environment variable template
└── CLAUDE.md         # AI assistant context
```

---

## Deployment (Production)

For production, Caddy handles automatic HTTPS. Set `APP_BASE_URL` to your domain:

```env
APP_BASE_URL=https://yourdomain.com
```

Update `Caddyfile` to use your domain instead of `localhost`.
