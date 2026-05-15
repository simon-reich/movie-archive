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

<!-- GSD:project-start source:PROJECT.md -->
## Project

**MovieArchive**

Personal web app to build a searchable film archive. Movies are fetched via TMDB, optionally enriched with OMDB and Wikipedia data, then indexed in OpenSearch for full-text and faceted search. Designed for personal use today, architected to support multiple users later.

**Core Value:** Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

### Constraints

- **Tech Stack:** Spring Boot 3 + Java 25 + Nuxt 3 — keine Änderungen am Stack, in CLAUDE.md festgelegt
- **English-only:** UI, Code, Logs, Tests, Commits — kein i18n, kein Locale-Switching
- **External APIs:** Immer gemockt in Tests (WireMock BE, MSW FE) — nie echte API-Calls in CI
- **Tests liefern mit dem Feature:** Kein Feature-Merge ohne Tests
- **Single-user-first:** Architektur unterstützt Multi-User (movies-{userId} Index), aber v1 für Einzelnutzer
- **Responsive:** Mobile-first ab Phase 7, Desktop-first vorher akzeptabel
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## Scope
- Phase 1: JWT auth + BCrypt password hashing
- Phase 2: AES-256-GCM encryption for API keys
- Phases 3–4: async enrichment pipeline + OpenSearch indexing with custom analyzer
- Phases 5–6: search queries against OpenSearch
## Locked Core Stack (Reference)
| Layer | Technology | Version in build.gradle.kts |
|-------|------------|------------------------------|
| Backend framework | Spring Boot | 3.5.0 |
| Language | Java | 25 (toolchain) |
| Security | Spring Security | managed by Spring Boot BOM |
| Database | PostgreSQL | 16 (via Testcontainers postgres:16-alpine) |
| Search | OpenSearch | 2.x (opensearch-java 2.19.0) |
| HTTP transport | Apache HttpClient 5 | 5.4.4 |
| Build | Gradle Kotlin DSL | — |
| Migrations | Flyway | managed by Spring Boot BOM |
## JWT Authentication — JJWT 0.12.6
### Why this version
### Correct 0.12.x API
### Spring Security integration pattern
### What NOT to do with JJWT
| Avoid | Why |
|-------|-----|
| `.setSubject()` / `.setExpiration()` | Removed in 0.12.x — compile error |
| `.parseClaimsJws()` | Replaced by `.parseSignedClaims()` in 0.12.x |
| Catching `ExpiredJwtException` separately to renew access tokens | Access tokens are short-lived (15 min) — let them expire and use the refresh cookie flow |
| Storing JWT secret as plain text in application.properties | Use `${JWT_SECRET}` ENV var; minimum 32 chars for HS256 |
## BCrypt Password Hashing
### Configuration
### SHA-256 token hashing
### What NOT to do with passwords
| Avoid | Why |
|-------|-----|
| Argon2 instead of BCrypt | Spring Security does support Argon2, but BCrypt is decided for this project — changing now means Flyway migration for existing hashes |
| Cost factor below 10 | Insecure on modern hardware |
| MD5 / SHA-256 for passwords | Not a password hashing algorithm — no work factor |
## AES-256-GCM Encryption (API Keys at Rest)
### Why raw JDK, not Spring Security Crypto
### Implementation pattern
### IV management rule
### Masked display in API responses
### What NOT to do with AES-GCM
| Avoid | Why |
|-------|-----|
| Reusing the same IV | Catastrophic for GCM — breaks confidentiality and authentication. Always generate fresh IV per encrypt call. |
| CBC mode | No authentication tag — vulnerable to padding oracle. GCM provides both encryption and integrity. |
| Storing IV separately from ciphertext | Increases schema complexity; prepend-IV-to-ciphertext is the standard pattern. |
| Hardcoding master key in source | Use `${ENCRYPTION_MASTER_KEY}` ENV var. Already configured in application.properties. |
| PBKDF2 key derivation | Unnecessary overhead when the ENV-sourced key is already 32 random bytes. |
## OpenSearch Java Client 2.19.0
### Client bean configuration
### Index creation with custom analyzer
### Index-per-user pattern
### Document indexing
### What NOT to do with OpenSearch client
| Avoid | Why |
|-------|-----|
| `RestClientTransport` (deprecated) | Replaced by `ApacheHttpClient5Transport` in opensearch-java 2.x |
| `spring-data-opensearch` | Adds abstraction overhead not needed here; direct client gives full control over index settings and custom analyzers |
| Creating the index on every indexing call without existence check | Throws `ResourceAlreadyExistsException` — always check first |
| Indexing inside the web request thread | Blocks the request thread; indexing must happen in the `@Async` enrichment pipeline |
## Spring @Async + @Retryable — Enrichment Pipeline
- `org.springframework.retry:spring-retry` (explicit)
- `org.springframework:spring-aspects` (required for `@Retryable` AOP proxy)
### Thread pool configuration
### Enrichment service pattern
### Retry configuration
- `maxAttempts = 3`: 1 initial + 2 retries
- `delay = 1000`: 1 second initial delay
- `multiplier = 2.0`: exponential backoff → delays: 1s, 2s (before attempts 2 and 3)
- Apply to: `TmdbClient`, `OmdbClient`, `WikipediaClient` methods
- Do NOT apply `@Retryable` to the `@Async` method itself — nesting causes proxy interception issues
### OMDB graceful degradation
### WebClient vs RestTemplate for external API calls
### What NOT to do with @Async/@Retryable
| Avoid | Why |
|-------|-----|
| Self-invoking `@Async` or `@Retryable` methods from same class | Spring proxy is bypassed — annotations have no effect |
| Applying `@Retryable` to the `@Async` orchestrating method | The retry wraps the async submission, not the async execution — retry never fires |
| Using `SimpleAsyncTaskExecutor` (Spring default) | Creates unlimited threads; production code needs bounded pool |
| `RestTemplate` for external calls | In maintenance mode; `WebClient` (already on classpath) is the current standard |
| Catching `RetryExhaustedException` outside the client layer | Retry exhaustion should be caught in the enrichment service and logged, not propagated to the HTTP layer |
## Supporting Libraries (Already in build.gradle.kts)
| Library | Version | Purpose | Notes |
|---------|---------|---------|-------|
| MapStruct | 1.6.3 | DTO/entity mapping | Annotation processor — Lombok must come first in `annotationProcessor` order (already set via `lombok-mapstruct-binding:0.2.0`) |
| Bucket4j | 8.10.1 | Rate limiting | In-memory token bucket per IP or user; use for `/auth/*` endpoints to prevent brute force |
| Flyway | BOM-managed | Schema migrations | PostgreSQL dialect; V1–V3 already applied |
| Lombok | BOM-managed | Boilerplate reduction | `@Builder`, `@Data`, `@Slf4j` — use `@Slf4j` for consistent log format |
| GreenMail | 2.1.3 (test) | In-process SMTP | Already wired; use `@RegisterExtension` with `GreenMailExtension` |
| WireMock | 3.13.0 (test) | HTTP mock server | Stub TMDB, OMDB, Wikipedia in WireMock JSON fixture files |
| Testcontainers | BOM-managed (test) | Real DB/OS in tests | `postgres:16-alpine` + OpenSearch 2.x image — no H2 |
| Bucket4j (auth) | 8.10.1 | Brute-force protection | Apply rate limit on `/auth/login`, `/auth/forgot-password` |
## Version Compatibility
| Package | Compatible With | Notes |
|---------|-----------------|-------|
| `jjwt-api:0.12.6` | Spring Boot 3.5.0 / Spring Security 6.x | No Spring Security OAuth2 ResourceServer dependency needed — custom filter |
| `opensearch-java:2.19.0` | `httpclient5:5.4.4` | Transport requires httpclient5; already in build.gradle.kts |
| `spring-retry` | `spring-aspects` | Both required together; `@EnableRetry` must be present |
| `mapstruct:1.6.3` | `lombok` | `lombok-mapstruct-binding:0.2.0` must appear after both in `annotationProcessor` order — already set |
| `flyway-database-postgresql` | PostgreSQL 16 | PostgreSQL-specific Flyway module; required alongside `flyway-core` since Flyway 10 |
## Alternatives Considered
| Recommended | Alternative | Why Not |
|-------------|-------------|---------|
| JJWT 0.12.6 (locked) | Spring Security OAuth2 Resource Server (nimbus-jose-jwt) | Overkill for a personal app; JJWT is simpler and sufficient for HS256 |
| Raw JDK AES-GCM | Spring Security `AesBytesEncryptor` GCM | Forces PBKDF2 key derivation; unnecessary when master key is already 32 random bytes from ENV |
| `ApacheHttpClient5Transport` | `RestClientTransport` (deprecated) | RestClientTransport is deprecated in opensearch-java 2.x |
| `spring-data-opensearch` | Direct `opensearch-java` client | Spring Data abstraction hides index settings and custom analyzer config; direct client is more transparent for this use case |
| `WebClient` (webflux) | `RestTemplate` | RestTemplate is in maintenance mode; WebFlux already on classpath |
| Custom `ThreadPoolTaskExecutor` | `SimpleAsyncTaskExecutor` (default) | Default creates unbounded threads; bounded pool required for production stability |
## Sources
- `build.gradle.kts` — authoritative version numbers (HIGH confidence)
- `application.properties` — ENV variable names and defaults confirmed (HIGH confidence)
- `.claude/auth-flows.md`, `.claude/data-model.md`, `.claude/api-contracts.md` — project-specific design decisions (HIGH confidence)
- [OpenSearch Java client docs](https://docs.opensearch.org/latest/clients/java/) — `ApacheHttpClient5Transport` as recommended transport, builder patterns (HIGH confidence)
- [JJWT GitHub](https://github.com/jwtk/jjwt) — 0.12.x API method names confirmed (HIGH confidence)
- [Spring @Async / @Retryable Baeldung](https://www.baeldung.com/spring-async-retry) — proxy self-invocation trap, `CompletableFuture` pattern (MEDIUM confidence — well-established community source)
- [BCryptPasswordEncoder Spring Security docs](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.html) — constructor with strength parameter (HIGH confidence)
- WebSearch results for AES-GCM Java patterns — IV handling and GCM tag behavior (MEDIUM confidence — confirmed against JDK javadoc behavior)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
