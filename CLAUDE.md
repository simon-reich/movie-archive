# MovieArchive — CLAUDE.md

Personal web app to build a searchable film archive. Movies are fetched via TMDB, optionally enriched with OMDB data, supplemented with Wikipedia content, and indexed in OpenSearch for full-text search.

**Repo:** https://github.com/simon-reich/movie-archive  
**Plan:** Confluence "MovieArchive App" space → "Project Overview & Plan" (v0.6)  
**Jira:** MOV project, Kanban board, MOV-1..MOV-101

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | Nuxt 3 + Vue 3 + TypeScript + TailwindCSS + shadcn-vue |
| State | Pinia |
| Backend | Spring Boot 3 + Java 21 + Spring Security + JWT |
| Mail | Spring Mail + Thymeleaf (dev: Mailpit) |
| Async | `@Async` + `@Retryable` (no queue infrastructure) |
| Database | PostgreSQL 16 (users, tokens, raw movie data) |
| Search | OpenSearch 2.x — one index per user: `movies-{userId}` |
| Proxy | Caddy (Docker Compose, routes `/api/*` → Spring, rest → Nuxt) |
| Build | Gradle (Kotlin DSL) / pnpm |
| CI | GitHub Actions |
| Testing BE | JUnit 5, Mockito, Testcontainers, WireMock, GreenMail, MockMvc |
| Testing FE | Vitest, Vue Test Utils, @nuxt/test-utils, MSW, Playwright |

Mono-repo layout: `backend/`, `frontend/`, `docker-compose.yml`.

---

## Key Conventions

- **English-only**: UI, code, logs, tests, commit messages. TMDB calls with `language=en-US`. Wikipedia: `en.wikipedia.org` only. No i18n library.
- **OMDB is optional**: If no key configured → skip call. OMDB failure never blocks the save flow.
- **Wikipedia 6-step fallback**: `{OriginalTitle}_{Year}_film` → `{OriginalTitle}_(film)` → `{OriginalTitle}` → same with `{Title}`. No hit → save film without wiki data.
- **Tokens**: Always stored as SHA-256 hash, never plaintext. Single-use via `consumed_at`.
- **API keys at rest**: AES-256-GCM encrypted, master key from ENV.
- **Tests ship with the feature**: No feature merge without tests.
- **External APIs are always mocked in tests**: WireMock (BE), MSW (FE).

---

## Architecture

```
Browser → Caddy → Nuxt FE (SSR)
                → Spring Boot BE → PostgreSQL (source of truth)
                                 → OpenSearch (derived, rebuildable)
                                 → Mailpit/SMTP (outbound only)
                        (async) → TMDB API → OMDB API → Wikipedia API
```

Save flow: `POST /movies/save` returns `202 Accepted` immediately; async task fetches TMDB → extracts `imdb_id` → fetches OMDB (if key) → fetches Wikipedia → persists to Postgres → indexes to OpenSearch.

---

## Phases

| # | Phase | Jira Epic |
|---|---|---|
| 0 | Repo setup, Docker Compose, Skeletons, CI | MOV-1 |
| 1 | Auth, Email Verification, Password Reset | MOV-2 |
| 2 | Settings, API Key Management (TMDB + OMDB) | MOV-3 |
| 3 | Save Movie Flow (TMDB + OMDB + Wikipedia) | MOV-4 |
| 4 | OpenSearch Indexing + Custom Analyzer | MOV-5 |
| 5 | Search (Simple + Advanced) | MOV-6 |
| 6 | Movie Detail Page + Personal Fields | MOV-7 |
| 7 | E2E Tests, Mobile, Polish, README | MOV-8 |

---

## Workflow (per Jira Ticket)

After completing a ticket:
1. **Commit** — one commit per ticket, message format: `MOV-XX: <summary>` (English)
2. **Jira** — transition the ticket to **Done** via `getTransitionsForJiraIssue` + `transitionJiraIssue`

Do both automatically without waiting to be asked.

---

## Detail Docs (load on demand)

- [`.claude/auth-flows.md`](.claude/auth-flows.md) — Auth endpoints, token specs, mail templates, forgot-password flow
- [`.claude/data-model.md`](.claude/data-model.md) — Postgres schema, OpenSearch mapping (all 40+ fields), custom analyzer
- [`.claude/api-contracts.md`](.claude/api-contracts.md) — TMDB, OMDB, Wikipedia API details, parameters, rate limits
- [`.claude/test-strategy.md`](.claude/test-strategy.md) — Test pyramid, tooling per layer, coverage targets, E2E happy paths, CI pipeline
