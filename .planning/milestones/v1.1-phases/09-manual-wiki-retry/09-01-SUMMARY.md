---
phase: 09-manual-wiki-retry
plan: 01
subsystem: enrichment
tags: [spring-boot, vue, nuxt, wiremock, msw, wikipedia, retry]

# Dependency graph
requires:
  - phase: 08-wiki-enrichment-tracking-batch-reload
    provides: WikiReloadService.retryWikipedia(Movie), wiki_last_attempted_at column, cooldown data model
provides:
  - "POST /movies/{id}/retry-wiki backend endpoint (ownership-scoped, synchronous)"
  - "useMovieDetail().retryWiki() + wikiRetrying frontend composable function"
  - "Retry button UI on /movies/[id] detail page with spinner and 'Still no page found.' feedback"
affects: [movie-detail-page, wiki-enrichment]

actuals:
  tokens: 6519
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Manual retry endpoint reuses Phase 8's WikiReloadService.retryWikipedia(Movie) unmodified — no new Wikipedia fetch logic"
    - "Full movie.value replace on retry response, matching fetchDetail()'s existing pattern (never a partial merge)"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/movie/MovieDetailService.java
    - backend/src/main/java/de/moviearchive/movie/MovieDetailController.java
    - backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java
    - frontend/composables/useMovieDetail.ts
    - "frontend/pages/movies/[id].vue"
    - frontend/test/mocks/handlers/movieDetail.ts
    - frontend/test/unit/pages/movies-id.spec.ts
    - frontend/test/unit/composables/useMovieDetail.spec.ts

key-decisions:
  - "POST (not PATCH) for /movies/{id}/retry-wiki — a command with side effects, distinct from the existing PATCH /personal idempotent-update endpoint"
  - "No cooldown check and no coordination with the batch-reload executor — manual retry always bypasses cooldown per D-01/D-02, overlap risk explicitly accepted"
  - "'Still no page found.' tracked via a page-local wikiRetryAttempted ref, not a new backend field — client-side-only per D-05's discretion resolution"

patterns-established:
  - "WireMockExtension + @DynamicPropertySource registration duplicated locally in MovieDetailControllerTest (mirrors WikiReloadControllerTest) since Java forbids extending two base test classes (AbstractOpenSearchTest + AbstractWireMockTest)"

requirements-completed: [ENRICH-04, ENRICH-05]

coverage:
  - id: D1
    description: "POST /movies/{id}/retry-wiki backend endpoint, ownership-scoped, calls WikiReloadService.retryWikipedia(Movie) synchronously, returns full MovieDetailResponse"
    requirement: "ENRICH-04"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java#retryWiki_returnsUpdatedDetail_onWikipediaSuccess"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java#retryWiki_returnsNoWikiFields_whenWikipediaNotFound"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java#retryWiki_returns404WhenMovieNotOwnedByUser"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java#retryWiki_returns404WhenMovieDoesNotExist"
        status: pass
    human_judgment: false
  - id: D2
    description: "Detail page shows a Retry button in place of the hidden Wikipedia section when both wiki fields are null, with spinner + disabled state while in flight, and 'Still no page found.' after a failed retry"
    requirement: "ENRICH-05"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/movies-id.spec.ts#shows Retry button when both wikipediaPlot and wikipediaCritics are null"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/movies-id.spec.ts#hides Retry button when either wiki field is populated"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/movies-id.spec.ts#clicking Retry invokes retryWiki from the composable"
        status: pass
      - kind: unit
        ref: 'frontend/test/unit/pages/movies-id.spec.ts#shows "Still no page found." after a failed retry and re-enables the button'
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/movies-id.spec.ts#shows spinner and disables the button while wikiRetrying is true"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/composables/useMovieDetail.spec.ts#retryWiki sends POST to /api/movies/:id/retry-wiki and replaces movie.value on success"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/composables/useMovieDetail.spec.ts#retryWiki does not throw when the request rejects"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-08-23
status: complete
---

# Phase 9 Plan 1: Manual Wiki Retry — Backend Endpoint + Frontend Retry Button Summary

**POST /movies/{id}/retry-wiki endpoint reusing Phase 8's WikiReloadService.retryWikipedia(Movie), plus a Retry button on the movie detail page with spinner and "Still no page found." feedback**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-08-23T14:30:00Z (approx.)
- **Completed:** 2026-08-23T14:48:55Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments
- New `MovieDetailService.retryWiki(userId, movieId)` — ownership-scoped 404 lookup via `findByIdAndUserId`, delegates to the existing (unmodified) `WikiReloadService.retryWikipedia(Movie)` from Phase 8, returns a fresh `getDetail()` response
- New `POST /movies/{id}/retry-wiki` on `MovieDetailController`, resolving `userId` from the JWT exactly like the sibling GET/PATCH/DELETE endpoints
- `useMovieDetail().retryWiki()` composable function with a `wikiRetrying` ref, doing a full `movie.value` replace on success and silently swallowing failures (the caller tracks "attempt happened" separately)
- New `v-else` block on `/movies/[id]` sibling to the existing Wikipedia `v-if`, showing a Retry button (SpinnerIcon + disabled while in flight) and a "Still no page found." note after a failed attempt
- Backend tests cover the happy path, the Wikipedia-not-found path (all 6 fallback candidates exhausted via WireMock), and both 404/IDOR cases (not-owned, non-existent)
- Frontend tests cover button visibility, click behavior, spinner/disabled state, and the composable's POST request shape + error resilience

## Task Commits

Each task was committed atomically:

1. **Task 1 (tracer): Wire POST /movies/{id}/retry-wiki end-to-end** - `daac5a8` (feat)
2. **Task 2: Failure-path, ownership, and "still not found" hardening** - `b998fd3` (test)

_Note: Task 1 is tagged `tracer` in the plan; its own `<verify>` (backend test) was run and passed before commit, satisfying the tracer feedback gate before Task 2's expansion work began._

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` - Added `retryWiki(userId, movieId)` orchestration method + `WikiReloadService` field
- `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` - Added `POST /{id}/retry-wiki` endpoint
- `backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java` - Added WireMock registration (mirrors `WikiReloadControllerTest`), `saveMovieMissingWikiForUser` helper, and 4 new retry-wiki tests
- `frontend/composables/useMovieDetail.ts` - Added `wikiRetrying` ref + `retryWiki()` function, exported from the composable
- `frontend/pages/movies/[id].vue` - Added `wikiRetryAttempted` ref, `onRetryWiki()` handler, new `v-else` Retry-prompt block
- `frontend/test/mocks/handlers/movieDetail.ts` - Added `POST /api/movies/:id/retry-wiki` MSW handler
- `frontend/test/unit/pages/movies-id.spec.ts` - Added `retryWiki`/`wikiRetrying` to the composable mock + 5 new tests
- `frontend/test/unit/composables/useMovieDetail.spec.ts` - Added 2 new `retryWiki()` tests

## Decisions Made
- **POST, not PATCH:** `retry-wiki` is a command with side effects (triggers an external API call and mutates enrichment fields), not an idempotent resource update — kept distinct from the existing `PATCH /{id}/personal` endpoint per the plan's explicit instruction.
- **No cooldown check, no batch-reload coordination:** manual retry is always callable regardless of the 30-day cooldown (D-01); no locking against Phase 8's `wikiReloadExecutor`-backed batch is added, the overlap risk is accepted per D-02.
- **Client-side-only "attempted" tracking:** `wikiRetryAttempted` is a page-local Vue ref, not a new backend field — the backend has no way to distinguish "never tried" from "tried and failed" beyond `wiki_last_attempted_at`, and the UI only needs this distinction for the current page view (D-05).

## Deviations from Plan

None - plan executed exactly as written. Both tasks matched their `<action>` and `<acceptance_criteria>` blocks precisely; no Rule 1-4 auto-fixes were needed.

## Issues Encountered

- **Stale `~/.testcontainers.properties`:** the local dev machine's global Testcontainers config file pinned `docker.client.strategy=UnixSocketClientProviderStrategy` against `/var/run/docker.sock`, which doesn't exist under the current OrbStack Docker context (`~/.orbstack/run/docker.sock`). Worked around by passing `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` as an environment variable for the `./gradlew test` invocation, without touching the global properties file (outside repo scope, machine-level config, not part of this plan's `files_modified`).
- **`pnpm vitest` vs `pnpm exec vitest`:** running `pnpm vitest run ...` directly triggered pnpm's recursive-workspace resolution and failed with `ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL`; `pnpm exec vitest run ...` worked correctly. Frontend `node_modules` was also not yet installed in this worktree — ran `pnpm install` first (lockfile was already up to date, no `pnpm-lock.yaml` changes).
- **Java `get` static-import collision:** adding `import static com.github.tomakehurst.wiremock.client.WireMock.get;` alongside the existing `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;` wildcard caused every `mockMvc.perform(get(...))` call in the test class to resolve to WireMock's `get(...)` (a `MappingBuilder`, no `.header()` method), breaking compilation of 4 pre-existing tests. Fixed by importing `com.github.tomakehurst.wiremock.client.WireMock` as a class and fully qualifying all WireMock DSL calls (`WireMock.get(...)`, `WireMock.aResponse(...)`, etc.) inside the new `stubWikipediaFound()` helper and the new not-found test, leaving the wildcard `MockMvcRequestBuilders.*` import untouched.

## Next Phase Readiness

- Manual retry is fully wired end-to-end and independently testable; no blockers for plan 09-02 (parallel wave, disjoint files).
- `WikiReloadService.retryWikipedia(Movie)` remains unmodified per the plan's constraint — both the batch-reload endpoint (Phase 8) and this manual endpoint (Phase 9) now share the identical enrichment logic with zero duplication.

---
*Phase: 09-manual-wiki-retry*
*Completed: 2026-08-23*

## Self-Check: PASSED

- FOUND: backend/src/main/java/de/moviearchive/movie/MovieDetailService.java
- FOUND: backend/src/main/java/de/moviearchive/movie/MovieDetailController.java
- FOUND: frontend/pages/movies/[id].vue
- FOUND: frontend/composables/useMovieDetail.ts
- FOUND commit daac5a8 (feat(09-01): wire POST /movies/{id}/retry-wiki end-to-end)
- FOUND commit b998fd3 (test(09-01): failure-path, ownership, and "still not found" hardening)
