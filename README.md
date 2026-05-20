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

### Option A — Infrastructure only (recommended for development)

Start only the infra services (Postgres, OpenSearch, Mailpit, Dashboards) and run BE/FE locally:

```bash
# 1. Copy and configure environment variables
cp .env.example .env
# Edit .env — set DB_PASSWORD, JWT_SECRET, ENCRYPTION_MASTER_KEY

# 2. Start infra services
docker compose up -d

# 3. Run backend locally (port 8080)
cd backend && ./gradlew bootRun

# 4. Run frontend locally (port 3000)
cd frontend && pnpm install && pnpm dev
```

### Option B — Full stack via Docker

Build and start everything including Caddy, BE, and FE containers:

```bash
docker compose --profile app up -d
open http://localhost
```

### Services

| Service | URL | Profile |
|---|---|---|
| App (full stack) | http://localhost | app |
| Backend API | http://localhost:8080/api | always (local) |
| Swagger UI | http://localhost:8080/swagger-ui.html | always (local) |
| Mailpit (mail UI) | http://localhost:8025 | always |
| OpenSearch Dashboards | http://localhost:5601 | always |
| Postgres | localhost:5432 | always |
| OpenSearch | http://localhost:9200 | always |

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

## Running E2E Tests

Playwright E2E tests run against the full Docker Compose stack (`backend`, `frontend`, `caddy`, `postgres`, `opensearch`).

### Prerequisites

1. Copy `.env.example` to `.env` and set:
   - `SPRING_PROFILES_ACTIVE=test` — enables the test seed endpoint
   - `TEST_TMDB_KEY=<your-tmdb-api-key>` — used to search and save a real film

2. Start the full stack:
   ```bash
   docker compose --profile app up -d
   ```

3. Wait for the backend health check to pass:
   ```bash
   curl http://localhost/api/actuator/health
   ```

### Run

```bash
cd frontend
pnpm test:e2e
```

This runs all E2E specs on both Desktop Chrome and Mobile Chrome (Pixel 5). A Playwright HTML report is generated in `frontend/playwright-report/`.

To run only the happy-path spec:
```bash
pnpm test:e2e --grep "Happy path"
```

### CI

E2E tests run automatically on every push to `main` and on pull requests via the `e2e-ci.yml` GitHub Actions workflow. The `TEST_TMDB_KEY` secret must be set in the repository's GitHub Actions secrets.

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
