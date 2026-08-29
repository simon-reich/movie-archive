---
phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
plan: 01
subsystem: wiki-reload-backend-and-progress-ui
tags: [sse, spring-boot, vue, wikipedia, batch-reload, cooldown]
status: complete

dependency-graph:
  requires: []
  provides:
    - WikiReloadProgressService (SSE registry + stop-flag component)
    - WikiReloadService.WikiRetryOutcome (SUCCESS/NOT_FOUND/FAILED classification)
    - GET /admin/wiki-reload/{userId}/progress
    - POST /admin/wiki-reload/{userId}/stop
    - useSettings().subscribeToWikiReloadProgress / stopWikiReload
  affects:
    - frontend/pages/settings.vue (#wikipedia-data section)

tech-stack:
  added: []
  patterns:
    - "In-memory SSE emitter registry (Map<UUID, List<SseEmitter>> + last-known-state), cloned from BulkImportProgressService"
    - "AtomicBoolean-keyed stop-flag map, reset at the top of every batchReload() run"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
    - backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
  modified:
    - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
    - backend/src/main/resources/application.properties
    - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
    - backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java
    - frontend/composables/useSettings.ts
    - frontend/pages/settings.vue
    - frontend/test/unit/composables/useSettings.spec.ts
    - frontend/test/unit/pages/settings.spec.ts

decisions:
  - "wiki.retry.pacing-delay-ms default raised 1000ms -> 30000ms (D-14-01); wikipedia.request-pacing-ms and wikidata.request-pacing-ms left untouched"
  - "wikiLastAttemptedAt now only set on a genuine attempt (SUCCESS or confirmed WikipediaNotFoundException) — a technical/rate-limit failure no longer cooldown-blocks the movie (D-14-02)"
  - "New progress registry keyed on userId, not a newly-invented runId — matches the single-global-run-slot invariant of wikiReloadExecutor (core=1/max=1/queue=1)"
  - "Stop flag is a net-new AtomicBoolean-per-userId map with resetRun() called unconditionally at the top of every batchReload() invocation, preventing the stale-flag self-inflicted-DoS regression (RESEARCH.md Pitfall 4)"

metrics:
  duration: ~50m
  completed: 2026-08-27

actuals:
  tokens: 12177
  tasks: 3
  commits: 3
---

# Phase 14 Plan 01: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI (Backend + Frontend) Summary

Raised the batch-reload between-movie pacing default to 30s, fixed the cooldown-marking bug so only genuine Wikipedia attempts (success or confirmed not-found) advance `wikiLastAttemptedAt`, and shipped a live SSE progress stream with a Stop control on the Settings page — closing the long-deferred progress-UI todo and both follow-on gaps Phase 13's live verification surfaced.

## What Was Built

**Task 1 (backend core, tracer):**
- `WikiReloadProgressService` (new, `de.moviearchive.admin`) — an in-memory SSE emitter registry structurally cloned from `BulkImportProgressService`, keyed on `userId` (no persisted batch entity exists for wiki-reload; matches `wikiReloadExecutor`'s single-global-run-slot invariant). `register()`/`publish()`/`complete()` mirror the bulk-import lifecycle; `resetRun()`/`requestStop()`/`isStopRequested()` are the net-new Stop-flag mechanism (`Map<UUID, AtomicBoolean>`), with no prior precedent in this codebase.
- `WikiReloadService.WikiRetryOutcome` (new nested enum: `SUCCESS`, `NOT_FOUND`, `FAILED`) — `doRetryWikipedia()` now returns this instead of `void`. The unconditional `movie.setWikiLastAttemptedAt(Instant.now())` that used to fire before every attempt was deleted; the timestamp is now set only in the success path and the `WikipediaNotFoundException` catch block — a generic technical/rate-limit exception leaves it untouched.
- `batchReload()`: calls `progressService.resetRun(userId)` at the very top (before the per-movie loop), checks `isStopRequested()` at both loop-boundary points (before each movie and before the pacing sleep), publishes one progress event per processed movie with an accurate `SUCCESS`/`NOT_FOUND`/`FAILED` status, and calls `progressService.complete(userId)` on every exit path (normal completion, early Stop-triggered break, and the pre-existing `InterruptedException` early return).
- `application.properties`: `wiki.retry.pacing-delay-ms` default raised `1000` → `30000`; `wikipedia.request-pacing-ms` and `wikidata.request-pacing-ms` untouched.

**Task 2 (HTTP layer):**
- `GET /admin/wiki-reload/{userId}/progress` (SSE, `SseEmitter(Long.MAX_VALUE)`) and `POST /admin/wiki-reload/{userId}/stop` added to `WikiReloadController`, both reusing the existing `assertOwnership()` IDOR check.
- New 403 regression tests for both endpoints, plus an integration test proving a Stop request issued mid-run halts the batch before every eligible movie is indexed.

**Task 3 (frontend):**
- `useSettings.ts`: `subscribeToWikiReloadProgress()` (via `@microsoft/fetch-event-source`, header-based auth — mirrors `useBulkImport.ts`) and `stopWikiReload()`.
- `settings.vue` `#wikipedia-data` section: a processed/total progress bar, a growing per-movie title+status list (client-accumulated from each SSE event's single most-recent movie, per RESEARCH.md's Open Question 1 recommendation), and a Stop button gated on an active (non-complete) run.

## Deviations from Plan

None — plan executed exactly as written. All acceptance criteria greps and behavior checks passed as specified.

## Verification

- `./gradlew test --tests WikiReloadServiceTest --tests WikiReloadProgressServiceTest` — 12/12 pass (Task 1).
- `./gradlew test --tests WikiReloadControllerTest` — 7/7 pass (Task 2, including the new Stop-mid-run integration test).
- `pnpm test` (full frontend suite) — 180/180 pass (Task 3, includes 16 useSettings.spec.ts + 12 settings.spec.ts tests).
- `pnpm lint` on the 4 files this plan touched — 0 errors (a pre-existing unrelated lint error in `frontend/pages/movies/[id].vue` was left untouched, out of scope for this plan).
- Full backend suite (`./gradlew test`) run once with all changes present: 27 of 195 tests failed, entirely within 3 unrelated classes (`EnrichmentIntegrationTest`, `WikipediaClientTest`, `SettingsIntegrationTest` — 100% failure rate within each, none touched by this plan). Re-running each of those 3 classes in isolation (not as part of the full concurrent suite run) produced 27/27 passing, confirming the full-run failures were Testcontainers/Postgres resource contention under this sandboxed environment's concurrent-container load, not a regression introduced by this plan. All `WikiReload*`-named test classes (the ones this plan actually touches) passed in every run, including inside the full-suite run.

## Self-Check: PASSED

Verified all created/modified files exist and all 3 task commits exist in git log.
