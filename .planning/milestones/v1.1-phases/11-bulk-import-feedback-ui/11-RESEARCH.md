# Phase 11: Bulk Import Feedback UI - Research

**Researched:** 2026-08-24
**Domain:** Server-push progress (SSE) over an existing JWT header-auth API + persisted batch reporting on top of an existing async single-line-at-a-time import pipeline
**Confidence:** HIGH (backend/frontend facts) / MEDIUM (schema design recommendation — new, not yet locked)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Live progress is pushed via WebSocket/SSE, not polling. — Reversibility: costly — rationale: no existing WebSocket/SSE infrastructure in this project (confirmed via codebase scout: no such dependency in `backend/build.gradle.kts`, no reactor/websocket usage anywhere in `backend/src/main/java`). This is a new capability for the backend, not a reuse of the existing single-movie polling pattern in `frontend/pages/add.vue:69-96`. Research should investigate the minimal-footprint approach given Spring Boot 3 (WebSocket vs. SSE via `text/event-stream` — SSE is simpler for one-directional server→client progress and likely the better fit than full WebSocket).
  - **Claude's Discretion:** SSE vs. full WebSocket — user picked "WebSocket/SSE" as one bucket; research should recommend the simpler one (likely SSE) given the one-directional nature of progress updates, unless research finds a concrete reason otherwise.
- **D-02:** Every bulk-import upload gets its own batch identifier (e.g. `import_batch_id` + timestamp), and `BulkImportLine` rows are tagged with the batch they belong to. — Reversibility: one-way — rationale: requires a schema migration (new column, backfill strategy for existing Phase 10 rows which have no batch concept today — they'll need a synthetic/legacy batch id or be excluded from the report list). This is the foundation the whole report-browsing feature depends on; reversing it later means migrating data again.
- **D-03:** A new page lists past bulk-import batches (date, line count, status distribution); clicking one opens its full per-line results list. — Reversibility: reversible — rationale: pure UI/routing, no data-model lock-in beyond D-02.
- **D-04:** `poster_path` is cached onto `BulkImportLine` at save time (in `saveAndUpsert`, `BulkImportService.java:180-184`, where `tmdbId` is already known/fetched) — no extra TMDB calls when rendering the results list later. — Reversibility: reversible — rationale: additive column, no migration of existing behavior required beyond a schema addition.
  - Rows with no `tmdbId` (AMBIGUOUS, NOT_FOUND, PARSE_ERROR) have no poster — results list needs a text-only/placeholder fallback for these.
- **D-05:** The results view is read-only for this phase — no inline "pick a TMDB candidate and save" action from AMBIGUOUS/NOT_FOUND rows. Manual correction stays on the existing Add Film search flow. — Reversibility: reversible — rationale: this narrows Phase 11 scope; making rows actionable later is a pure additive feature, not a rework.
- **D-06:** NOT a downloadable file (CSV/PDF/etc.) — the user explicitly rejected this ("Kein Download als File, das ist irgendwie zu fizzelig"). Reports are stored in Postgres (via D-02's batch grouping) and viewed in-app only. — Reversibility: reversible.

### Claude's Discretion

- SSE vs. full WebSocket (see D-01 above) — this research recommends **SSE**, with a concrete reason: see `## Architecture Patterns` below (auth-header incompatibility of native `EventSource` resolved via `fetch()`-based SSE consumption, not WebSocket).
- Exact schema shape for D-02's batch identifier (dedicated table vs. bare column) — CONTEXT.md's example text (`import_batch_id` + timestamp) suggests a bare column; this research recommends a dedicated `bulk_import_batch` table instead, with a concrete reason (see `## Architecture Patterns`). Flagged in `## Assumptions Log` for planner/user confirmation since it deviates from the CONTEXT.md example.

### Deferred Ideas (OUT OF SCOPE)

- **Inline resolution of AMBIGUOUS/NOT_FOUND rows** from the results view (pick a TMDB candidate and save directly) — explicitly deferred per D-05; candidate for a future phase (tracked as IMPORT-V2-01 in REQUIREMENTS.md Future Requirements).
- **Downloadable/exportable report file** (CSV/PDF) — explicitly rejected as the primary mechanism (D-06) but could be a small additive feature later since the underlying data will already be persisted per-batch.
- Wikipedia batch-reload progress indicator (separate todo, not folded into this phase) — may reuse this phase's SSE pattern as prior art later.
- Real CSV parsing for bulk import (separate todo, not folded into this phase) — about upload file *format*, not results/feedback UI.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| IMPORT-05 | User sieht während des laufenden Imports einen Live-Fortschritt (verarbeitet / gesamt) | SSE via Spring `SseEmitter` pushed directly from `BulkImportService.runImport()`'s existing loop (no DB polling needed — `i`/`rawLines.size()` are already in scope); frontend consumes via `@microsoft/fetch-event-source` (not native `EventSource`, which cannot send the app's required `Authorization: Bearer` header) — see `## Architecture Patterns` |
| IMPORT-06 | Nach Abschluss zeigt eine Ergebnisübersicht pro Zeile: Titel, Poster (falls gefunden), Status (gespeichert / mehrdeutig / nicht gefunden / Parse-Fehler) | New `bulk_import_batch` table + `batch_id`/`poster_path` columns on `BulkImportLine` (migration V10); new GET batch-list and batch-detail endpoints; frontend results list reuses `add.vue`'s poster-grid/status-overlay markup — see `## Architecture Patterns`, `## Code Examples` |
</phase_requirements>

## Summary

This phase adds two things on top of the Phase 10 bulk-import engine: (1) a live progress push while `BulkImportService.runImport()` is running, and (2) a persisted, revisitable per-batch results report. The single hardest technical fact this research surfaced is that **the app's entire auth model is header-only** (`JwtAuthFilter` reads `Authorization: Bearer <token>` and nothing else — no cookie fallback, no query-param fallback — `[VERIFIED: backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java:33-34]`, quote: `"String authHeader = request.getHeader(\"Authorization\"); if (authHeader != null && authHeader.startsWith(\"Bearer \"))"`), and the browser's native `EventSource` API **cannot set custom headers**. This rules out plain `new EventSource(url)` for D-01's SSE choice and is why the frontend must consume SSE via `fetch()` + a stream-parsing helper instead — the standard, well-documented workaround, not a hack. Backend-side, Spring MVC's `SseEmitter` (already available for free — no new Gradle dependency, since `spring-boot-starter-web` is already on the classpath) is the correct fit; the codebase's servlet-stack `starter-web` + `starter-webflux`-for-`WebClient`-only combination (already established for `TmdbClient`/`OmdbClient`/`WikipediaClient`) is fully compatible with `SseEmitter`, which is a servlet-MVC class, not a WebFlux one.

For IMPORT-06, the phase 10 schema (`bulk_import_line`, `V9__create_bulk_import_line.sql`, verified this session) has no batch concept and no poster storage. This research recommends adding a dedicated `bulk_import_batch` table (rather than a bare `import_batch_id` column as CONTEXT.md's example text suggested) because the batch list page (D-03) needs a stable `total_lines` count for reconnect/multi-tab scenarios that a bare column on the line rows cannot cheaply provide (blank lines are never persisted as rows — D-02 of Phase 10 — so "rows with this batch_id" always undercounts the true submitted line count needed for "processed / total").

**Primary recommendation:** Push progress directly from `BulkImportService.runImport()`'s existing `for` loop via an in-memory `SseEmitter` registry keyed by batch id (no DB polling, no scheduled task); consume it on the frontend with `@microsoft/fetch-event-source` (`[VERIFIED: npm registry — OK verdict, see Package Legitimacy Audit]`) so the existing `Authorization: Bearer` header pattern keeps working unchanged; add a `bulk_import_batch` table + `batch_id`/`poster_path` columns on `bulk_import_line` in a new Flyway `V10__*.sql`; add three new REST endpoints plus one SSE endpoint on `BulkImportController`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Live progress computation (processed/total count) | API / Backend | — | `BulkImportService.runImport()` already owns the loop index and total line count; no other tier has this state |
| Live progress transport (push to browser) | API / Backend | Browser / Client | Backend pushes via `SseEmitter`; browser consumes via `fetch()`-based SSE client — client owns reconnect/retry policy |
| Batch persistence (id, total_lines, per-line status/poster) | Database / Storage | API / Backend | Postgres is already the source of truth for `BulkImportLine` (D-06: report lives in Postgres, not a file) |
| Batch list / batch detail rendering | Browser / Client | API / Backend | New Nuxt page(s) fetch from new GET endpoints; no SSR-specific logic needed (data is user-scoped, behind auth) |
| Poster image rendering | Browser / Client | CDN / Static | Poster URLs point directly at TMDB's image CDN (`image.tmdb.org`), same as existing `add.vue:103-106` — browser fetches images directly, backend only stores the `poster_path` string |
| IDOR / ownership enforcement on batch id | API / Backend | — | `batchId` becomes a path variable on 3 new endpoints; must be ownership-checked per request the same way `WikiReloadController.assertOwnership()` already does for `userId` |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.web.servlet.mvc.method.annotation.SseEmitter` | Bundled with `spring-boot-starter-web` 3.5.0 (already a dependency `[VERIFIED: backend/build.gradle.kts]`) | Server push of progress events over `text/event-stream` | Built into Spring MVC — zero new backend dependency; purpose-built for exactly this one-directional server→client push use case |
| `@microsoft/fetch-event-source` | `2.0.1` `[VERIFIED: npm registry — published 2021-04-25, 3.2M weekly downloads, repo `github.com/Azure/fetch-event-source`, not deprecated]` | Frontend SSE client that supports custom headers (`Authorization: Bearer`), unlike native `EventSource` | Native `EventSource` cannot send the app's required Bearer header (see Summary) — this is the standard, widely-used (3.2M/week) library that wraps `fetch()` + `ReadableStream` parsing to solve exactly that gap, maintained by Microsoft (Azure OpenAI SDK dependency), MIT licensed |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Flyway (already on classpath) | BOM-managed `[VERIFIED: backend/build.gradle.kts]` | New migration `V10__*.sql` for `bulk_import_batch` table + `batch_id`/`poster_path` columns | Every schema change in this codebase goes through Flyway (V1–V9 precedent) |
| Jackson (`ObjectMapper`, already on classpath via `spring-boot-starter-web`) | BOM-managed | Serializing the SSE `data:` payload as JSON | `SseEmitter.event().data(obj, MediaType.APPLICATION_JSON)` uses the same `HttpMessageConverter` chain as normal `@RestController` JSON responses — no new serialization code needed |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| SSE (`SseEmitter` + `fetch-event-source`) | Full WebSocket (`spring-boot-starter-websocket` + STOMP or raw) | Bidirectional channel the phase doesn't need (progress is server→client only); adds a new Gradle dependency, a new `WebSocketConfig`, and a new frontend WS client — strictly more surface area for the same outcome. CONTEXT.md's own discretion note already leans SSE; this research confirms no concrete reason to prefer WebSocket. |
| `@microsoft/fetch-event-source` | Hand-rolled `fetch()` + `ReadableStream` + manual SSE-frame parsing | SSE framing (multi-line `data:`, `id:`, `retry:`, reconnection with `Last-Event-ID`) has real edge cases; hand-rolling it duplicates a well-maintained ~small library for no benefit — see `## Don't Hand-Roll` |
| Query-param JWT for the SSE endpoint (`?token=...`) | — | Rejected: puts the access token in URLs (server access logs, browser history, `Referer` headers) — a documented anti-pattern and a deviation from this app's header-only JWT convention (`JwtAuthFilter` has no query-param code path today) |
| Dedicated `bulk_import_batch` table | Bare `import_batch_id` column on `bulk_import_line` (as CONTEXT.md's example literally suggests) | A bare column can't cheaply answer "how many lines were submitted in this batch" for reconnect/multi-tab scenarios, because blank lines are parsed-and-skipped, never persisted as rows (Phase 10 D-02) — `COUNT(*) WHERE batch_id = X` always undercounts the true submitted total. A dedicated table captures `total_lines` synchronously in the POST handler (where `rawLines.size()` is already known) before the async job starts. |

**Installation:**
```bash
# frontend/
pnpm add @microsoft/fetch-event-source
```
No backend Gradle changes needed — `SseEmitter` ships with `spring-boot-starter-web`, already a dependency.

**Version verification:** `npm view @microsoft/fetch-event-source version` → `2.0.1`, confirmed live against the npm registry this session `[VERIFIED: npm registry]`.

## Package Legitimacy Audit

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|--------------|---------|-------------|
| `@microsoft/fetch-event-source` | npm | ~5 years (published 2021-04-25) | 3,226,748/week | `github.com/Azure/fetch-event-source` | OK | Approved |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

No `postinstall` script present (`gsd_run query package-legitimacy check` signal: `postinstall: null`) — no supply-chain red flag for this package.

## Architecture Patterns

### System Architecture Diagram

```
                         ┌─────────────────────────────────────────┐
                         │  Browser (Nuxt page: /add, new /imports) │
                         └─────────────────────────────────────────┘
                              │ 1. POST /movies/bulk-import (file)         │ 4. GET /movies/bulk-import/batches
                              │    Authorization: Bearer <jwt>             │    GET /movies/bulk-import/batches/{id}
                              │    ↳ 202 { status, batchId }               │    (normal JSON fetch, existing pattern)
                              ▼                                            ▲
                    ┌───────────────────────┐                              │
                    │ BulkImportController   │──────────────────────────────┘
                    │  (Caddy → /api/* strip │
                    │   prefix → :8080)      │
                    └───────────────────────┘
                              │ synchronous: reads file, counts lines,
                              │ inserts bulk_import_batch row (total_lines known here)
                              ▼
                    ┌───────────────────────┐        3. SSE: GET /movies/bulk-import/{batchId}/progress
                    │ bulkImportExecutor      │           (fetch-event-source, Authorization header)
                    │ (core=1/max=1/queue=1)  │◄──────────────────────────────────────────┐
                    │ runImport() loop        │                                            │
                    │  for i in rawLines:      │  2. self.processLine() → TMDB search →     │
                    │   processLine()          │     saveAndUpsert() → upsertLine()         │
                    │   ├─ push SSE event ─────┼──► BulkImportProgressEmitterRegistry ───────┘
                    │   │  {processed, total}  │     (in-memory Map<batchId, List<SseEmitter>>)
                    │   Thread.sleep(pacingMs)  │
                    └───────────────────────┘
                              │ per-line writes
                              ▼
                    ┌───────────────────────┐
                    │ PostgreSQL              │
                    │  bulk_import_batch      │  (id, user_id, total_lines, created_at)
                    │  bulk_import_line       │  (+ batch_id FK, + poster_path)
                    └───────────────────────┘
```

Trace of the primary use case: user uploads a file → `BulkImportController` synchronously creates the `bulk_import_batch` row (so `total_lines` and `batchId` exist before the 202 response) → returns `batchId` to the browser → browser opens an SSE connection to `/movies/bulk-import/{batchId}/progress` using `fetch-event-source` (Authorization header attached) → `runImport()`'s existing per-line loop pushes a `progress` event after each line via the emitter registry → on the last line, pushes a `complete` event and calls `emitter.complete()` → browser then does a normal authenticated GET to `/movies/bulk-import/batches/{batchId}` to render the final per-line results list (title/poster/status), reusing `add.vue`'s existing poster-grid markup.

### Recommended Project Structure

```
backend/src/main/java/de/moviearchive/bulkimport/
├── BulkImportController.java          # existing — add 3 GET endpoints + 1 SSE endpoint
├── BulkImportService.java             # existing — runImport() gains a progress-push call per line
├── BulkImportProgressService.java     # NEW — owns the in-memory SseEmitter registry (register/publish/complete/remove)
├── BulkImportBatch.java               # NEW — entity for bulk_import_batch
├── BulkImportBatchRepository.java     # NEW
├── BulkImportLine.java                # existing — add batchId, posterPath fields
├── BulkImportLineRepository.java      # existing — add findByBatchId, status-count-by-batch queries
├── dto/
│   ├── BulkImportBatchSummary.java    # NEW record — {batchId, createdAt, totalLines, statusCounts}
│   ├── BulkImportBatchDetail.java     # NEW record — {batchId, createdAt, totalLines, lines: [...]}
│   └── BulkImportLineResult.java      # NEW record — {title, originalTitle, year, status, posterPath}
└── ...

backend/src/main/resources/db/migration/
└── V10__create_bulk_import_batch.sql  # NEW

frontend/
├── pages/
│   ├── add.vue                        # existing — after successful upload, route/link to new batch page
│   └── imports/
│       ├── index.vue                  # NEW — D-03 batch list page
│       └── [batchId].vue              # NEW — D-03 batch detail + D-01 live progress (if still running)
├── composables/
│   └── useBulkImport.ts               # NEW — wraps GET batches / GET batch detail / SSE progress subscription
```

### Pattern 1: Push SSE progress directly from the existing async loop (no DB polling)

**What:** `runImport()` already iterates `rawLines` with an index `i` and knows `rawLines.size()` — the exact numbers IMPORT-05 needs ("processed / total"). Push a progress event to all emitters registered for this batch after each line, instead of a separate scheduled task polling row counts.
**When to use:** Any time the producer of a metric already has that metric in scope inside its own loop — pushing it out is simpler and lower-latency than having a consumer re-derive it from persisted state.
**Example (grounded in the verified loop shape in `BulkImportService.java:68-91`, quote: `"for (int i = 0; i < rawLines.size(); i++) { ... self.processLine(...) ... }"`):**
```java
// BulkImportService.runImport() — inside the existing loop, after self.processLine(...)
self.processLine(email, tmdbKey, rawLines.get(i)).ifPresent(enrichmentService::enrich);
progressService.publish(batchId, i + 1, rawLines.size()); // NEW — after each line
```

### Pattern 2: `SseEmitter` with an explicit long timeout (never the container default)

**What:** Spring Boot's async request timeout defaults to a short window (Spring Boot property default 10s per `[CITED: Spring Boot GitHub issue discussion]`; the underlying embedded Tomcat's own async timeout defaults to ~30s if Boot's property is unset) `[CITED: multiple community sources, cross-checked against Spring's async-request docs page]`. With `bulk-import.max-lines=5000` and `bulk-import.pacing-delay-ms=1000` `[VERIFIED: backend/src/main/resources/application.properties:62,65]`, a worst-case import can run **~83 minutes**. An `SseEmitter` created with the no-arg constructor will be silently timed out and closed by the container long before that.
**When to use:** Any SSE/async endpoint whose duration is not bounded to a few seconds.
**Example:**
```java
@GetMapping(value = "/bulk-import/{batchId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter progress(@PathVariable UUID batchId, Authentication auth) {
    assertOwnership(auth, batchId); // same IDOR pattern as WikiReloadController.assertOwnership
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // never let the container default (10-30s) kill a long-running import
    progressService.register(batchId, emitter, /* current processed count for reconnect */);
    return emitter;
}
```

### Pattern 3: Frontend SSE consumption with the existing Bearer-header pattern

**What:** Replace `new EventSource(url)` (which cannot set headers) with `fetchEventSource(url, { headers: authHeaders(), onmessage, onerror, signal })`, matching the exact `authHeaders()` helper already used by every other call in `useMovies.ts:27-31`.
**Example:**
```typescript
// frontend/composables/useBulkImport.ts
import { fetchEventSource } from '@microsoft/fetch-event-source'

function subscribeToProgress(batchId: string, onProgress: (p: { processed: number; total: number; complete: boolean }) => void) {
  const ctrl = new AbortController()
  fetchEventSource(`/api/movies/bulk-import/${batchId}/progress`, {
    headers: authHeaders(), // same helper as searchTmdb/saveMovie/uploadBulkImport
    signal: ctrl.signal,
    onmessage(ev) {
      if (ev.event === 'progress' || ev.event === 'complete') {
        onProgress(JSON.parse(ev.data))
      }
    },
    onerror(err) {
      throw err // do not let the library auto-retry indefinitely on a fatal (e.g. 403) error — see Pitfall 3
    },
  })
  return () => ctrl.abort() // call from onUnmounted, mirroring add.vue's existing pollingIntervals cleanup pattern
}
```

### Anti-Patterns to Avoid

- **Native `new EventSource(url)` for this app's SSE endpoint:** cannot attach the required `Authorization: Bearer` header — the request will hit `JwtAuthFilter` with no `Authorization` header and fall through to an unauthenticated `401`/redirect, not a working progress stream.
- **Recomputing "total" from `COUNT(bulk_import_line WHERE batch_id = X)`:** undercounts, because blank lines are parsed-and-skipped without ever being persisted as a row (Phase 10 D-02, `ImportLineParser.parse()` returns `null` for blank lines and the caller "skip it silently, nothing persisted" `[VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java:13-14]`, quote: `"Returns {@code null} for a blank (whitespace-only) line — the caller must skip it silently, no row persisted, no exception (D-02)."`). Use the `total_lines` captured synchronously from `rawLines.size()` in the controller instead.
- **Calling `emitter.complete()` from inside a `try { ... } catch (IOException e) { emitter.completeWithError(e) }` block after an `IOException` from `send()`:** per Spring's documented behavior, once `send()` throws `IOException` (client disconnected), the container's own `AsyncListener` machinery already handles the completion — a manual `completeWithError()` call afterward is redundant/risks double-completion errors. Just stop sending and let the container's listener path close it out. `[CITED: Spring javadoc for `ResponseBodyEmitter.send`]`

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SSE framing + reconnect over an authenticated `fetch()` stream | A hand-rolled `ReadableStream` reader that splits on `\n\n`, tracks `Last-Event-ID`, and implements retry/backoff | `@microsoft/fetch-event-source` | SSE framing has real edge cases (multi-line `data:` fields, `retry:` directive, reconnection semantics, Page Visibility API integration to pause when the tab is hidden) that a 30-line hand-rolled parser will get subtly wrong; the library is small, MIT-licensed, 3.2M weekly downloads, maintained by Microsoft |
| Multi-client broadcast of the same progress stream | A pub/sub message broker (Redis, RabbitMQ) | An in-memory `Map<UUID, List<SseEmitter>>` inside a single `@Service` | This app runs as a single backend instance (`AsyncConfig`'s executors are all in-process singletons — no existing queue infrastructure per CLAUDE.md's own "Async: `@Async` + `@Retryable` (no queue infrastructure)" line); a distributed broker would be new infra for a feature whose producer (the single `bulkImportExecutor` thread) and consumers (the same JVM's HTTP threads) already live in the same process |

**Key insight:** Both hand-roll temptations in this phase (SSE parsing, multi-client fanout) have a "just write it, it's simple" appearance — but the app's existing architectural constraints (single-instance backend, no queue infra, header-only JWT) already dictate the correct minimal solution once traced through; the risk is reaching for a heavier tool (WebSocket, a broker) that solves problems this app doesn't have, or a lighter hand-rolled one that quietly mishandles reconnection edge cases.

## Common Pitfalls

### Pitfall 1: Native `EventSource` silently fails auth (the D-01 blocker)
**What goes wrong:** A developer wires up `new EventSource('/api/movies/bulk-import/{id}/progress')` following the "SSE is simple" intuition, and it 401s or the auth cookie flow expects a cookie the app doesn't set (there is no access-token cookie — only the refresh token is an HttpOnly cookie `[VERIFIED: .claude/auth-flows.md:23]`, quote: `"Refresh Token: HttpOnly + Secure + SameSite=Strict cookie, ~7 days."` — the access token itself is header-only).
**Why it happens:** `EventSource` is the obvious/native API for SSE, and its header limitation is not discoverable until you try to pass one.
**How to avoid:** Use `@microsoft/fetch-event-source` from the start (Pattern 3 above) — never attempt native `EventSource` for this app's authenticated endpoints.
**Warning signs:** SSE connection immediately closes with no data, or the browser network tab shows a `401`/`403` on the `/progress` request with no `Authorization` header present.

### Pitfall 2: `SseEmitter` container timeout killing long imports
**What goes wrong:** Import silently stops updating in the UI partway through a large file; the connection was closed by the container's default async timeout, not by the import itself failing.
**Why it happens:** The default `SseEmitter()` constructor relies on the container's async-request timeout (Spring Boot default ~10s, embedded Tomcat's own default ~30s) `[CITED: community sources cross-checked against Spring async docs]`, but with `max-lines=5000` and `pacing-delay-ms=1000` a worst-case import runs ~83 minutes `[VERIFIED: backend/src/main/resources/application.properties:62,65]`.
**How to avoid:** Always construct `new SseEmitter(Long.MAX_VALUE)` (Pattern 2).
**Warning signs:** Progress bar freezes at some percentage before 100%, with no error shown; connection age at freeze time correlates with the container's default timeout, not with import size.

### Pitfall 3: `fetch-event-source`'s default retry-forever behavior on fatal errors
**What goes wrong:** If the SSE endpoint returns a genuine `403` (e.g., the batch doesn't belong to this user, or ownership check fails) the library's default behavior is to keep retrying with backoff, spamming the same failing request instead of surfacing an error to the user.
**Why it happens:** The library is built for the common LLM-streaming use case where transient network errors should retry; it does not know which non-2xx responses are fatal for this app's use case.
**How to avoid:** Implement `onerror` (and optionally `onopen`) callbacks that inspect the response and `throw` for any status the app considers terminal (401/403/404), which the library treats as "stop retrying, propagate error" — this is documented library behavior (throwing from `onerror` stops the retry loop).
**Warning signs:** Network tab shows repeated identical requests to the `/progress` endpoint every few seconds after a permanent failure.

### Pitfall 4: Undercounting "total" from persisted rows instead of `rawLines.size()`
**What goes wrong:** Progress bar never reaches 100%, or the batch-list page shows a lower "line count" than what the user actually uploaded.
**Why it happens:** Blank lines in the uploaded file are parsed and skipped without any DB row ever being created (`[VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java:13-14]`, quoted above in Anti-Patterns) — so `SELECT COUNT(*) FROM bulk_import_line WHERE batch_id = X` will always be less than or equal to the number of lines the user actually submitted whenever the file contains any blank lines.
**How to avoid:** Capture `rawLines.size()` synchronously in `BulkImportController.uploadBulkImport()` (it's already computed there at line 66 — `[VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:66]`, quote: `"List<String> rawLines = new String(file.getBytes(), StandardCharsets.UTF_8).lines().toList();"`) and persist it as `bulk_import_batch.total_lines` before the async job starts. Use that stored value as "total" everywhere, never a row count.
**Warning signs:** Off-by-N discrepancy between the progress bar's denominator and the batch-detail results list's row count, where N equals the number of blank lines in the uploaded file.

### Pitfall 5: Missing IDOR check on the new path-variable `batchId` endpoints
**What goes wrong:** Any authenticated user could view another user's bulk-import batch detail (title/poster/status of movies another user imported) by guessing/enumerating a `batchId`.
**Why it happens:** Unlike the existing `POST /movies/bulk-import` (which has no path-variable userId at all — user is resolved purely from the JWT subject, `[VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:26-28]`), the three new endpoints (`GET .../batches/{batchId}`, `GET .../{batchId}/progress`) DO take `batchId` as a path variable, which is exactly the shape `WikiReloadController` already had to defend against for `userId` — easy to forget to port that same check to a new endpoint shape.
**How to avoid:** Port `WikiReloadController.assertOwnership()`'s exact pattern (`[VERIFIED: backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:64-71]`, quote: `"if (!user.getId().equals(userId)) { throw new AccessDeniedException(\"Access denied.\"); }"`) — for these endpoints, load the batch by id, compare `batch.getUser().getId()` against the JWT-resolved user's id, throw `AccessDeniedException` (already mapped to `403` in the existing `@ExceptionHandler` pattern) on mismatch.
**Warning signs:** No 403 test case exists for the new endpoints; a plan-checker or code-reviewer should flag any `@PathVariable UUID batchId` endpoint with no accompanying ownership assertion.

## Code Examples

### Backend: batch creation synchronously in the controller (captures `total_lines` before 202)
```java
// BulkImportController.uploadBulkImport() — existing method, grounded in verified lines 51-91
// rawLines is already computed at line 66: new String(file.getBytes(), UTF_8).lines().toList()
BulkImportBatch batch = bulkImportService.createBatch(email, rawLines.size()); // NEW — synchronous insert
log.info("Bulk import requested email={} lines={} batchId={}", email, rawLines.size(), batch.getId());
bulkImportService.runImport(email, tmdbKey, rawLines, batch.getId()); // batchId now threaded through
return ResponseEntity.accepted().body(Map.of("status", "started", "batchId", batch.getId().toString()));
```

### Backend: `bulk_import_batch` migration (recommended shape, follows V9's exact style)
```sql
-- V10__create_bulk_import_batch.sql
-- Follows the exact style of V9__create_bulk_import_line.sql (verified this session):
-- UUID PK with gen_random_uuid() default, FK to users(id), TIMESTAMPTZ created_at.
CREATE TABLE bulk_import_batch (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    total_lines INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bulk_import_batch_user_id ON bulk_import_batch(user_id, created_at DESC);

ALTER TABLE bulk_import_line
    ADD COLUMN batch_id UUID REFERENCES bulk_import_batch(id),
    ADD COLUMN poster_path VARCHAR(500);

CREATE INDEX idx_bulk_import_line_batch_id ON bulk_import_line(batch_id);
-- batch_id is nullable: existing Phase-10-era rows (created before this migration) have no batch.
-- They are excluded from the new batch-list/detail views (D-03 scopes "past bulk-import batches",
-- and a NULL-batch row was never part of a trackable batch to begin with). A re-upload of the same
-- line later will assign it to whatever batch triggers the next upsertLine() call, per the existing
-- find-or-update-in-place dedup behavior (BulkImportService.upsertLine()) — no separate backfill
-- migration needed.
```

### Backend: poster_path capture at save time (D-04)
```java
// BulkImportService.saveAndUpsert() — CURRENT signature (verified, BulkImportService.java:180-184):
// private Optional<UUID> saveAndUpsert(User user, String email, ParsedLine parsed, int tmdbId) {
//     MovieInitiateResult result = movieService.initiate(email, tmdbId);
//     upsertLine(user, parsed, BulkImportLineStatus.SAVED, tmdbId);
//     return result.isNew() ? Optional.of(result.id()) : Optional.empty();
// }
//
// NEW: accept the whole TmdbSearchResultItem (already has posterPath — TmdbSearchResultItem.java:3-9,
// verified: "record TmdbSearchResultItem(int tmdbId, String title, String originalTitle, Integer year,
// String posterPath)") instead of just the int tmdbId, so no extra TMDB call is needed:
private Optional<UUID> saveAndUpsert(User user, String email, ParsedLine parsed, TmdbSearchResultItem match) {
    MovieInitiateResult result = movieService.initiate(email, match.tmdbId());
    upsertLine(user, parsed, BulkImportLineStatus.SAVED, match.tmdbId(), match.posterPath()); // NEW param
    return result.isNew() ? Optional.of(result.id()) : Optional.empty();
}
// Callers at BulkImportService.java:155 and :165 change from
// `saveAndUpsert(user, email, parsed, yearMatches.get(0).tmdbId())` to
// `saveAndUpsert(user, email, parsed, yearMatches.get(0))` (pass the whole item).
```

### Frontend: reusing the existing poster-grid/status-overlay vocabulary for the results list
```html
<!-- New batch-detail results list — reuses add.vue's card/overlay/icon vocabulary
     (verified: frontend/pages/add.vue:161-210), mapped to the 4 BulkImportLineStatus values
     instead of the 3 PosterState values used there. Text-only fallback for rows with no posterPath. -->
<div
  v-for="line in batch.lines"
  :key="line.title + line.year"
  class="relative overflow-hidden"
>
  <img
    v-if="line.posterPath"
    :src="posterUrl(line.posterPath)"
    :alt="line.title"
    class="w-full aspect-[2/3] object-cover bg-card border border-border"
  >
  <div v-else class="w-full aspect-[2/3] bg-card border border-border flex items-center justify-center p-2">
    <p class="text-xs text-muted-foreground text-center">{{ line.title }}</p>
  </div>

  <div class="absolute bottom-0 right-0 p-2 bg-background/70 flex items-center justify-center">
    <CheckCircle2 v-if="line.status === 'SAVED'" class="w-6 h-6 text-foreground" />
    <XCircle v-else class="w-6 h-6 text-foreground" />
  </div>

  <p class="pt-2 text-xs text-muted-foreground">{{ statusLabel(line.status) }}</p>
</div>
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Native `EventSource` for all SSE | `fetch()` + streaming parser for any SSE endpoint that needs custom headers/POST/auth | Long-standing (native `EventSource`'s header limitation has existed since the spec's inception; the WHATWG issue tracking a fix — `whatwg/html#2177` — has been open for years with no resolution) | Any app with non-cookie auth (like this one's header-only JWT) must use the fetch-based pattern for SSE, not the native API |

**Deprecated/outdated:** none specific to this phase's stack — `SseEmitter` in Spring 6/Boot 3 is current and unchanged from prior Spring Boot 2.x versions for this use case.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | Dedicated `bulk_import_batch` table (vs. CONTEXT.md's example of a bare `import_batch_id` column) is the right schema shape | Architecture Patterns, Code Examples | If the planner/user prefers the simpler bare-column approach, reconnect/multi-tab progress display would need a different mechanism to know "total lines" (e.g., re-deriving it, or accepting it can only be shown while the SSE connection is live) — moderate rework of the migration and the progress endpoint, not the whole feature |
| A2 | `bulk_import_line.batch_id` is nullable and legacy (pre-migration) rows are simply excluded from the new batch list, rather than backfilled into a synthetic "legacy" batch | Code Examples (migration) | If the user wants pre-Phase-11 rows visible in the new report UI, a backfill migration (INSERT one synthetic `bulk_import_batch` row + `UPDATE bulk_import_line SET batch_id = ... WHERE batch_id IS NULL`) would need to be added — low risk, additive change to the migration |
| A3 | Spring Boot's async-request-timeout default (~10s property default / ~30s Tomcat container default) — sourced from community discussion threads, not fetched from the pinned Spring Boot 3.5.0 reference docs directly | Common Pitfalls (Pitfall 2) | Low risk either way: the recommended fix (`new SseEmitter(Long.MAX_VALUE)`) is unconditionally correct regardless of the exact default value — the pitfall's *existence* is well-established even if the exact number is approximate |
| A4 | `fetch-event-source`'s `onerror` "throw to stop retrying" behavior, and its Page Visibility API auto-pause behavior | Common Pitfalls (Pitfall 3), Don't Hand-Roll | If the exact throw-to-stop semantics differ slightly, worst case is the planner needs to double-check the library's README during implementation — does not change the overall architecture recommendation |

## Open Questions

1. **Should the SSE endpoint also replay/include the currently-known progress state immediately on connect (for reconnect / multiple tabs), or only push forward from the moment of connection?**
   - What we know: The `bulkImportExecutor` pool is a single global run-slot (`core=1/max=1/queue=1`, `[VERIFIED: backend/src/main/java/de/moviearchive/config/AsyncConfig.java:49-58]`), so at most one batch is actively running at a time; a user reconnecting mid-import needs *some* way to see current progress, not just future pushes.
   - What's unclear: Whether the progress endpoint should query `COUNT(bulk_import_line WHERE batch_id = X AND status IS NOT NULL)` as an approximation on connect (imprecise per Pitfall 4) or whether the `BulkImportProgressService` registry should also track the last-known `(processed, total)` in memory per active batch and replay it to newly-registering emitters.
   - Recommendation: Track last-known `(processed, total)` in the in-memory registry (simple, avoids the undercounting pitfall entirely) — send it as the first event to any newly-registered emitter for a batch that's still in-flight; if the batch has already completed (registry has no entry), the frontend should just skip SSE entirely and go straight to the batch-detail GET endpoint.

2. **Does the batch-list page (D-03) need pagination?**
   - What we know: No existing REST endpoint in this codebase returns a full `Page<T>` response shape — the only precedent (`DashboardService`'s `findRecentlyIndexedByUserId`) uses a bare `Pageable`/`PageRequest.of(0, N)` "top N" limit, not cursor/offset pagination (`[VERIFIED: backend/src/main/java/de/moviearchive/movie/MovieRepository.java:52,56]`).
   - What's unclear: How many batches a real user is likely to accumulate — this is a personal single-user archive app (CLAUDE.md: "Single-user-first"), so batch count is likely small (tens, not thousands) over the app's lifetime.
   - Recommendation: Ship without pagination for this phase (plain `List<BulkImportBatchSummary>` ordered by `created_at DESC`); revisit only if real usage shows the list growing large.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Node.js / pnpm (for `@microsoft/fetch-event-source` install) | Frontend SSE client | ✓ (existing project toolchain, `npm view` succeeded this session) | — | — |
| Java 25 toolchain / Gradle | Backend `SseEmitter` (bundled with existing `spring-boot-starter-web`) | ✓ (existing project toolchain, `[VERIFIED: backend/build.gradle.kts]`) | Java 25 | — |
| PostgreSQL 16 | New `V10` migration | ✓ (already a project dependency, Testcontainers `postgres:16-alpine` in tests) | 16 | — |

No missing dependencies — this phase adds one frontend npm package and reuses backend capabilities already on the classpath.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 + Mockito + Testcontainers + WireMock, MockMvc (`AutoConfigureMockMvc`) — `[VERIFIED: backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java:1-66]` |
| Frontend framework | Vitest + Vue Test Utils + `@nuxt/test-utils` + MSW — `[VERIFIED: frontend/package.json]`, existing coverage at `frontend/test/unit/pages/add.spec.ts` |
| Backend config file | `backend/build.gradle.kts` (JUnit via `useJUnitPlatform()`) |
| Frontend config file | `frontend/package.json` (`"test": "vitest run"`) |
| Quick run command (backend) | `./gradlew test --tests "*BulkImport*"` |
| Quick run command (frontend) | `pnpm --filter frontend test -- add` (or `pnpm --filter frontend test -- imports` once new specs exist) |
| Full suite command (backend) | `./gradlew check` (includes JaCoCo coverage gate, 75% line minimum — `[VERIFIED: backend/build.gradle.kts]`) |
| Full suite command (frontend) | `pnpm --filter frontend test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|---------------------|--------------|
| IMPORT-05 | POST upload returns `batchId` in response body | integration (MockMvc) | `./gradlew test --tests BulkImportControllerTest` | ❌ Wave 0 — new assertion on existing test class |
| IMPORT-05 | SSE endpoint emits progress events with increasing `processed` count as `runImport()` advances | integration | needs a new `BulkImportProgressServiceTest`/controller test consuming the emitter synchronously in-test | ❌ Wave 0 |
| IMPORT-05 | SSE endpoint rejects a `batchId` that doesn't belong to the requesting user (403) | integration | new test case, mirrors `WikiReloadController`'s existing 403 ownership test pattern | ❌ Wave 0 |
| IMPORT-06 | `saveAndUpsert()` persists `poster_path` from the already-fetched TMDB match | unit (`BulkImportServiceTest`) | `./gradlew test --tests BulkImportServiceTest` | ❌ Wave 0 — extend existing test class |
| IMPORT-06 | `GET /movies/bulk-import/batches` returns batches ordered by `created_at DESC` with correct `statusCounts` | integration | new `BulkImportBatchControllerTest` or extend `BulkImportControllerTest` | ❌ Wave 0 |
| IMPORT-06 | `GET /movies/bulk-import/batches/{batchId}` returns per-line title/poster/status, 403s for another user's batch | integration | same as above | ❌ Wave 0 |
| IMPORT-06 | Frontend batch-list and batch-detail pages render title/poster/status correctly, including the no-poster fallback | component (Vitest + `@nuxt/test-utils` + MSW) | `pnpm --filter frontend test -- imports` | ❌ Wave 0 — new spec files |

### Sampling Rate
- **Per task commit:** targeted `--tests` filter (backend) / targeted spec file (frontend)
- **Per wave merge:** `./gradlew check` + `pnpm --filter frontend test`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportBatchControllerTest.java` (or extend existing) — covers IMPORT-05/IMPORT-06 endpoint behavior, including the 403 ownership case
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java` — covers the in-memory emitter registry (register/publish/complete/remove-on-timeout)
- [ ] `frontend/test/unit/pages/imports/index.spec.ts` — batch list page
- [ ] `frontend/test/unit/pages/imports/[batchId].spec.ts` — batch detail + progress page
- [ ] MSW handler additions for the new GET endpoints, and an SSE-mocking strategy for `@microsoft/fetch-event-source` in tests (the library's `fetch` calls can be intercepted the same way MSW already intercepts `$fetch` calls elsewhere in this test suite, since both go through the global `fetch`)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|-------------------|
| V2 Authentication | yes | Existing JWT Bearer-header scheme, unchanged — new SSE endpoint authenticates via the same `JwtAuthFilter`, reached via `@microsoft/fetch-event-source`'s custom-header support (not a new auth mechanism) |
| V4 Access Control | yes | New IDOR risk: 3 new endpoints take `batchId` as a path variable — MUST port `WikiReloadController.assertOwnership()`'s pattern (Pitfall 5) |
| V5 Input Validation | yes | `batchId` path variable parsed as `UUID` by Spring (throws `MethodArgumentTypeMismatchException` on malformed input, already the existing behavior for any `@PathVariable UUID` in this codebase — no new validation code needed) |
| V6 Cryptography | no | No new secrets, tokens, or encrypted data introduced by this phase |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| IDOR via guessable/enumerable `batchId` on new GET endpoints | Elevation of Privilege | Ownership check per request (Pitfall 5) — `batchId` is a random UUID (`gen_random_uuid()`), not sequential, but UUIDs are not a substitute for an authorization check |
| JWT-in-URL leakage if a future implementer "simplifies" the SSE auth to a query param | Information Disclosure | This research explicitly rejects that approach (see Alternatives Considered) — enforce via code review that the SSE endpoint has no `@RequestParam` token path |
| Resource exhaustion via many open SSE connections (e.g., a user opening many tabs) | Denial of Service | Low risk given single-user-first scope and the existing `bulkImportExecutor` bound (only one batch running globally at a time bounds how much progress-push work exists); no new mitigation needed for this phase, but the in-memory emitter registry should still `emitter.onTimeout(() -> registry.remove(...))` / `onCompletion(...)` to avoid unbounded growth of stale emitter references across app uptime |

## Sources

### Primary (HIGH confidence)
- `backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java` — read this session, confirms header-only JWT extraction
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`, `BulkImportController.java`, `BulkImportLine.java`, `BulkImportLineStatus.java`, `BulkImportLineRepository.java`, `ImportLineParser.java` — read this session, ground the entire schema/loop/dto design
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` — read this session, confirms executor sizing
- `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql` — read this session, migration style precedent
- `backend/src/main/resources/application.properties` — read this session, confirms `pacing-delay-ms`/`max-lines` values
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — read this session, ownership-check pattern precedent
- `frontend/pages/add.vue`, `frontend/composables/useMovies.ts` — read this session, existing UI/auth-header patterns
- `.claude/auth-flows.md` — read this session, confirms access token is never in a cookie
- npm registry (`npm view @microsoft/fetch-event-source`) — checked live this session

### Secondary (MEDIUM confidence)
- Spring Framework `SseEmitter` javadoc (`docs.spring.io`) — fetched this session, method signatures
- Caddy documentation (`caddyserver.com/docs/caddyfile/directives/reverse_proxy`) — fetched this session, confirms auto-flush for `text/event-stream`

### Tertiary (LOW confidence)
- WebSearch results on Spring Boot async-request-timeout default values (community sources, cross-checked but not pinned to Spring Boot 3.5.0's exact docs) — see Assumption A3
- WebSearch results on `@microsoft/fetch-event-source` retry/onerror semantics (GitHub issues, Medium articles) — see Assumption A4
- WebSearch results on `SseEmitter` concurrent-write behavior (GitHub issue discussions) — informed Pattern/Pitfall framing but not load-bearing for the recommended architecture (single-producer-thread design sidesteps the concurrency question entirely)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — `SseEmitter` is a zero-new-dependency built-in; `@microsoft/fetch-event-source` verified live against npm registry with a clean legitimacy verdict
- Architecture: HIGH for the auth-header-incompatibility finding (verified against actual source code, not assumed); MEDIUM for the batch-table-vs-column schema recommendation (sound reasoning, but deviates from CONTEXT.md's literal example — flagged in Assumptions Log)
- Pitfalls: HIGH for Pitfalls 1, 4, 5 (grounded in verified source code); MEDIUM for Pitfalls 2, 3 (grounded in cross-checked community sources, not a single pinned authoritative doc)

**Research date:** 2026-08-24
**Valid until:** 2026-09-23 (30 days — stable stack, no fast-moving dependencies)
