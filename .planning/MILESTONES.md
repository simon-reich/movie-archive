# Milestones

## v1.1 Wiki Retry & Bulk Import (Shipped: 2026-08-29)

**Phases completed:** 9 phases, 28 plans, 53 tasks

**Key accomplishments:**

- Wikipedia enrichment attempts (both save-flow and new retry path) are now timestamped, and `POST /admin/wiki-reload/{userId}` synchronously retries films missing wiki data end-to-end (WikipediaClient → Postgres → OpenSearch), unpaced by design pending Plan 08-02
- Batch-reload is now fire-and-forget `@Async` on a dedicated `core=1/max=1/queue=1` executor — cooldown-window + status-filtered eligibility, `Thread.sleep(pacingDelayMs)`-paced between Wikipedia calls, and a saturated queue degrades to 503 instead of an unbounded backlog
- POST /movies/{id}/retry-wiki endpoint reusing Phase 8's WikiReloadService.retryWikipedia(Movie), plus a Retry button on the movie detail page with spinner and "Still no page found." feedback
- Added `GET /users/me` (first controller in `de.moviearchive.user`) and wired a "Reload missing Wikipedia data" button on Settings that triggers Phase 8's existing batch-reload endpoint, with full 202/503/generic-failure feedback.
- Multipart CSV-style upload endpoint (Title;OriginalTitle;Year), async exact-year TMDB matching with original-title ambiguity narrowing, idempotent save+enrich reuse, and dedup-before-TMDB-call skip on re-upload — all persisted per-line for Phase 11's results UI.
- Bulk import upload trigger added to Add Film page via new uploadBulkImport composable — file input, Import button, and inline success/error feedback, no progress UI (Phase 11 scope).
- Pre-flight parse-validity gate rejects wholly-unparseable bulk import batches with a synchronous 400, and add.vue now always shows the required format and surfaces that backend message instead of a generic fallback.
- New `bulk_import_batch` table + `batch_id`/`poster_path` columns on `bulk_import_line`, with `batchId` threaded end-to-end through the async import pipeline and returned in the upload response, giving Phase 11's later plans (SSE progress, batch list/detail endpoints, frontend results UI) real persisted data to read.
- In-memory `BulkImportProgressService` SSE emitter registry pushed directly from `runImport()`'s existing loop, exposed via an ownership-checked `GET /movies/bulk-import/{batchId}/progress` endpoint (`text/event-stream`, `SseEmitter(Long.MAX_VALUE)`), with last-known-state replay for reconnects and a synthesized "complete" event for already-finished batches.
- Two ownership-checked read-only GET endpoints — `GET /movies/bulk-import/batches` (newest-first list with per-status counts) and `GET /movies/bulk-import/batches/{batchId}` (per-line title/poster/status detail) — backed by two new repository queries and three response DTO records, giving Phase 11's frontend plans (11-04/11-05) real persisted data to render instead of a stub.
- New `useBulkImport.ts` composable (SSE progress subscription via `@microsoft/fetch-event-source` + batch-detail fetch) backing a new `/imports/{batchId}` page that shows a live processed/total indicator while an import runs and a per-line poster/status results grid once it completes, reachable from the Add Film page immediately after a successful upload.
- New `/imports` batch list page (reusing `useDashboard.ts`'s loading/error/empty pattern and `useBulkImport.ts`'s `getBatches()`) plus an "Imports" nav entry in both `AppNav.vue` link lists, closing the loop on D-03's core requirement — users can now find their way back to a past bulk-import's results from anywhere in the app, not just via a link that only exists for a few clicks right after upload.
- WikipediaClient.fetch() now resolves Wikipedia articles via a direct Wikidata IMDb-ID (P345) cross-reference before falling back to the existing 6/10-step candidate-URL cascade, plus a temporary human-readable resolution log so the user can see which path each lookup took.
- Replaced WikipediaClient's per-movie two-call REST Wikidata lookup (CirrusSearch search + `www.wikidata.org` REST sitelinks) with a single batched SPARQL query against `query.wikidata.org` that resolves up to 50 IMDb IDs to enwiki article titles per request, and deleted the temporary Phase-12 dev-visibility resolution log entirely.
- Restructured `WikiReloadService.batchReload()` to resolve Wikidata for its entire cooldown-eligible movie set in one SPARQL call before its per-movie loop starts, instead of resolving per movie inside the loop (D-02).
- Restructured `BulkImportService.runImport()` into an explicit two-pass shape — match+save+resolve-imdbId for every line, one batched SPARQL call for the whole run, then per-line enrichment threading the shared resolved-title map — replacing the old per-line TMDB-detail-then-enrich dispatch that carried the same one-movie-at-a-time exposure as the original ~630-movie rate-limit incident.
- Whole-card movie links and PARSE_ERROR raw-line display on the bulk-import batch-detail page, plus a localStorage-persisted grid/list view toggle
- New POST resolve endpoint (ownership-scoped on both batchId and lineId) plus an inline TMDB search-and-pick widget on AMBIGUOUS/NOT_FOUND bulk-import lines, updating that specific line's row to SAVED and refetching the batch
- Comma-delimited CSV as a second supported bulk-import format (quoted fields, optional header row), fully additive alongside the existing strict semicolon format
- Fixed SAVED-card navigation via `resolveComponent('NuxtLink')`, grouped bulk-import results into four ordered status sections with an always-row PARSE_ERROR treatment, and broke the inline resolve widget's candidate picker out to full container width.
- Each TMDB resolve-candidate now shows a visible "Title (Year)" text label under its poster (title-only when year is unknown), in both grid and list view — closing UAT gap G-15-4.
- Dropped Tailwind's `truncate` utility from both resolve-candidate-label elements in `[batchId].vue` so long candidate titles wrap onto multiple lines instead of being clipped, keeping the trailing year always visible (closes UAT gap G-15-5).
- Batch-scoped `BulkImportLineRepository`/`BulkImportService` dedup queries fix a pre-existing cross-batch line-reassignment bug (CR-01), and `processLine()`'s TMDB matching is reworked into a 4-branch algorithm that trusts a unique title hit over year.
- Fixed `WikiReloadProgressService.complete()`'s always-100%-looking terminal state by adding a `stopped` field read before the stop-flag is cleared, and threaded it through `settings.vue` so a stopped run shows "Stopped at X / Y" instead of silently vanishing or looking fully done, while the per-movie history now distinguishes SUCCESS/NOT_FOUND/FAILED instead of collapsing NOT_FOUND into a generic failure icon.
- Fixed a one-line duplicate-row bug in `settings.vue`'s wiki-reload per-movie history: the terminal `complete` SSE event, which deliberately echoes the last `progress` event's `lastMovieTitle`/`lastMovieStatus`, was being pushed into history a second time — now suppressed via a `!p.complete` guard, verified by two new regression tests covering both the Stop path and the genuine-finish path.
- Keyed `MovieRepository.findEligibleForWikiReload` on `wiki_url IS NULL` instead of `wiki_plot`/`wiki_critics`, so a movie whose page was already found is never re-selected for retry again, closing gap G-16-3.

---

## v1.0 MVP — Shipped 2026-05-21

**Phases:** 0–7 (28 plans)
**Timeline:** 2026-05-13 → 2026-05-21 (8 days)
**Commits:** 258 | **Files:** 369 | **Lines:** 61,839 insertions
**Stack:** Spring Boot 3 + Java 25 / Nuxt 3 + Vue 3 + TypeScript / PostgreSQL 16 / OpenSearch 2.x

### What Shipped

1. **JWT auth stack** — registration, email verification, login/logout, 7-day refresh token rotation, password reset with enumeration protection
2. **AES-256-GCM API key management** — TMDB (required) and OMDB (optional) keys encrypted at rest; account password + email change
3. **Async film enrichment pipeline** — TMDB → OMDB (graceful degradation) → Wikipedia (6-step fallback) → Postgres → OpenSearch; 202 Accepted UX with status polling
4. **OpenSearch per-user index** — custom analyzer (asciifolding, lowercase, elision, stop_english, kstem), 40-field mapping, admin full-reindex endpoint
5. **Full-text + advanced faceted search** — free text, genre/director/year/rating/content-rating/watched filters, sorting, click-through attribute navigation, dashboard with stats
6. **Cinematic film detail page** — TMDB + OMDB metadata, Wikipedia plot/critics, personal fields (rating, notes, watched), lazy YouTube trailer embed, delete with confirmation
7. **Playwright E2E + GitHub Actions CI** — Desktop Chrome + Mobile Chrome happy-path spec, full Docker Compose CI stack, README setup documentation; mobile-responsive app (hamburger nav, single-column reflow)

### Archive

- [v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md) — Full phase details
- [v1.0-REQUIREMENTS.md](milestones/v1.0-REQUIREMENTS.md) — All requirements with final status

---

*Next milestone: `/gsd-new-milestone` to define v1.1 or v2.0*
