---
phase: 03-save-movie-flow
plan: 06
subsystem: api
tags: [spring-boot, nuxt, vue, java-records, jwt, msw, vitest]

requires:
  - phase: 03-save-movie-flow/03-01
    provides: Movie entity, MovieRepository, MovieService.initiate(), MovieController

provides:
  - MovieInitiateResult record (UUID id, boolean isNew)
  - Conditional enrichment guard in MovieController (isNew check)
  - GET /movies/saved-ids endpoint returning saved TMDB IDs for the authenticated user
  - getSavedTmdbIds() in useMovies composable
  - 'saved' PosterState — already-archived films show checkmark badge in search results

affects: [phase-04-opensearch-indexing, phase-05-search, phase-06-movie-detail]

tech-stack:
  added: []
  patterns: [idempotency-guard-via-result-record, client-side-state-hydration-on-mount]

key-files:
  created:
    - backend/src/main/java/de/moviearchive/movie/dto/MovieInitiateResult.java
  modified:
    - backend/src/main/java/de/moviearchive/movie/MovieService.java
    - backend/src/main/java/de/moviearchive/movie/MovieController.java
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java
    - frontend/composables/useMovies.ts
    - frontend/pages/add.vue
    - frontend/test/mocks/handlers/movies.ts
    - frontend/test/unit/composables/useMovies.spec.ts
    - frontend/test/unit/pages/add.spec.ts

key-decisions:
  - "Return MovieInitiateResult record instead of plain UUID so the controller can branch without a second DB query"
  - "savedTmdbIds loaded on onMounted (non-critical: failure degrades gracefully to session-only guard)"
  - "Saved films remain visible in search results with checkmark badge rather than being filtered out — user can see what is already archived"

patterns-established:
  - "Idempotency via result record: service returns (id, isNew) so controller skips side effects for duplicates without extra queries"
  - "Client-side state hydration: onMounted fetches saved IDs once; subsequent saves update local Set so badge appears without re-fetching"

requirements-completed: [SAVE-01]

duration: 25min
completed: 2026-05-17
---

# Phase 03-06: Duplicate Save Idempotency + Saved-State Badge Summary

**Conditional enrichment guard via MovieInitiateResult record closes UAT gap 7 — duplicate saves are fully idempotent and already-archived films show a checkmark badge in the add-film search results.**

## Performance

- **Duration:** 25 min
- **Started:** 2026-05-17T14:00:00Z
- **Completed:** 2026-05-17T14:25:00Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments
- `MovieInitiateResult(UUID id, boolean isNew)` record introduced; `MovieService.initiate()` returns it instead of a plain UUID
- `MovieController.saveMovie()` now calls `enrichmentService.enrich()` only when `isNew == true` — no duplicate async pipeline runs
- `GET /movies/saved-ids` endpoint added — returns `{ tmdbIds: [int, ...] }` scoped to the authenticated user via `findTmdbIdsByUserId`
- `useMovies` composable extended with `getSavedTmdbIds()` and `PosterState` union extended with `'saved'`
- `add.vue` loads saved IDs on mount; every search result whose tmdbId is in the saved set renders with a non-clickable checkmark badge

## Task Commits

1. **Task 1: Backend — MovieInitiateResult + conditional enrichment + saved-ids endpoint** — `2d98bc3` (combined commit with Task 2)
2. **Task 2: Frontend — getSavedTmdbIds + filter saved posters + unit tests** — `2d98bc3`

## Files Created/Modified
- `backend/.../dto/MovieInitiateResult.java` — new record (UUID id, boolean isNew)
- `backend/.../MovieService.java` — initiate() returns MovieInitiateResult; getSavedTmdbIds() added
- `backend/.../MovieController.java` — enrichment guarded by result.isNew(); GET /movies/saved-ids added
- `backend/.../MovieRepository.java` — findTmdbIdsByUserId(@Query) added
- `backend/.../MovieControllerTest.java` — 3 new tests: row-count assertion, saved-ids after save, empty saved-ids
- `frontend/composables/useMovies.ts` — PosterState gets 'saved'; getSavedTmdbIds() exported
- `frontend/pages/add.vue` — onMounted hydration, savedTmdbIds Set, handleSearch state mapping, checkmark badge template
- `frontend/test/mocks/handlers/movies.ts` — GET /api/movies/saved-ids handler added
- `frontend/test/unit/composables/useMovies.spec.ts` — 2 new tests for getSavedTmdbIds
- `frontend/test/unit/pages/add.spec.ts` — 1 new test for saved-state mapping logic

## Decisions Made
- Returned `MovieInitiateResult` record (not a boolean flag parameter) so the controller gets both UUID and isNew in one call without an extra `existsByUserIdAndTmdbId` query
- `savedTmdbIds` loaded on `onMounted` with a silent catch — failure degrades to the existing same-session duplicate guard, never breaks the page
- Already-saved films remain in search results (visible with badge) rather than being filtered out, so users can see what's already archived

## Deviations from Plan
None — plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- Phase 03 gap closure complete; UAT test 7 passes
- Phase 04 (OpenSearch indexing) can proceed — movie persistence layer is stable and idempotent

---
*Phase: 03-save-movie-flow*
*Completed: 2026-05-17*
