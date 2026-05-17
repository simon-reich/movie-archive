---
phase: 05-search
plan: 01
subsystem: test-scaffolding
tags: [wave-0, tdd, msw, junit5, disabled-stubs, todo-stubs]
dependency_graph:
  requires: []
  provides: [SearchControllerTest-stubs, MSW-search-handlers, frontend-todo-specs]
  affects: [05-02-PLAN, 05-03-PLAN, 05-04-PLAN]
tech_stack:
  added: []
  patterns: [disabled-test-stub, it.todo-stub, MSW-handler-module-barrel]
key_files:
  created:
    - backend/src/test/java/de/moviearchive/search/SearchControllerTest.java
    - frontend/test/mocks/handlers/search.ts
    - frontend/test/unit/composables/useSearch.spec.ts
    - frontend/test/unit/pages/search.spec.ts
    - frontend/test/unit/pages/index.spec.ts
  modified:
    - frontend/test/mocks/handlers.ts
decisions:
  - SearchControllerTest extends AbstractOpenSearchTest (not AbstractIntegrationTest directly) for OS + Postgres singletons
  - 14 disabled stubs rather than 10 — plan specifies per-VALIDATION.md row for plans 02–04 (all Wave 1 methods)
  - it.todo() used (not it.skip()) so Vitest counts them as todo and does not mask them as skipped test definitions
metrics:
  duration: "~3 minutes"
  completed: "2026-05-17T21:03:35Z"
  tasks_completed: 3
  tasks_total: 3
  files_created: 5
  files_modified: 1
---

# Phase 05 Plan 01: Wave 0 Test Scaffolding Summary

Wave 0 test scaffolding for Phase 5 (Search): one backend disabled test class, one MSW handler module with registration, and three frontend todo spec files — all with zero production code.

## What Was Built

**Task 1 — Backend SearchControllerTest disabled stubs** (commit `2fd27a9`)

Created `backend/src/test/java/de/moviearchive/search/SearchControllerTest.java`. The class extends `AbstractOpenSearchTest`, is annotated `@AutoConfigureMockMvc`, and contains all helper methods copied verbatim from `ReindexControllerTest` (`cleanDb`, `createActiveUser`, `loginAndGetToken`, `persistMovie` with `tmdbIdSeq=1000`, `deleteIndexIfExists`, `refreshIndex`). 14 test methods are annotated `@Disabled("Wave 1: SearchController not yet implemented")` covering every search behavior in 05-VALIDATION.md: `shouldReturnAllFilms_whenQueryIsEmpty`, `shouldFindFilmByTitle`, `shouldFindFilmByOverview`, `shouldNormalizeAccentsInSearch`, `shouldFilterBySingleGenre`, `shouldFilterByMultipleGenresOR`, `shouldFilterByDirector`, `shouldFilterByYearRange`, `shouldFilterByImdbRating`, `shouldCombineGenreAndDirectorFilters`, `shouldReturnEmpty_whenWatchedFilterApplied`, `shouldSortByTitleAscending`, `shouldSortByImdbRatingDescending`, `shouldSortByPersonalRating_nullsLast`. `compileTestJava` exits 0; `./gradlew test --tests "de.moviearchive.search.SearchControllerTest"` is BUILD SUCCESSFUL with all tests skipped.

**Task 2 — Frontend MSW handlers + registration** (commit `136aa10`)

Created `frontend/test/mocks/handlers/search.ts` exporting `searchHandlers` array of 3 handlers:
- `http.post('/api/search', ...)` — returns `SearchResultItem[]` + pagination envelope matching the interface contract
- `http.get('/api/dashboard', ...)` — returns full dashboard shape (totalFilms, topGenres, languageBreakdown, imdbHistogram, movieOfTheDay, recentlyAdded)
- `http.get('/api/search/autocomplete', ...)` — returns `{ suggestions: ['Christopher Nolan'] }`

Registered `searchHandlers` in `frontend/test/mocks/handlers.ts` (import + spread after `moviesHandlers`). Frontend suite: 15 test files passed, 92 tests passed, 0 failures.

**Task 3 — Frontend todo spec stubs** (commit `3907920`)

Created three Vitest spec files using `it.todo(...)`:
- `frontend/test/unit/composables/useSearch.spec.ts` — 6 todo stubs (URL param reading, debounce, load-more, route change re-execution)
- `frontend/test/unit/pages/search.spec.ts` — 4 todo stubs (component export, mount search, genre chip navigation, grid/list view)
- `frontend/test/unit/pages/index.spec.ts` — 4 todo stubs (component export, mount fetch, movie of day + recently added, empty CTA)

Frontend suite with all three files: 18 test files (15 passed, 3 skipped-all-todo), 92 passed, 14 todo, 0 failures.

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | `2fd27a9` | test(05-01): add SearchControllerTest with 14 disabled Wave 1 stubs |
| 2 | `136aa10` | feat(05-01): add MSW search handlers and register in barrel |
| 3 | `3907920` | test(05-01): add Wave 2 todo spec stubs for useSearch, search page, and dashboard |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — this plan is intentionally stub-only (Wave 0 scaffolding). All stubs are tracked via `@Disabled` annotations (BE) and `it.todo()` calls (FE). Wave 1 (plan 05-02) and Wave 2 (plans 05-03, 05-04) convert them to real implementations.

## Threat Flags

None — no production code or runtime attack surface introduced. Test scaffolding only.

## Self-Check: PASSED

Files verified:
- FOUND: backend/src/test/java/de/moviearchive/search/SearchControllerTest.java
- FOUND: frontend/test/mocks/handlers/search.ts
- FOUND: frontend/test/unit/composables/useSearch.spec.ts
- FOUND: frontend/test/unit/pages/search.spec.ts
- FOUND: frontend/test/unit/pages/index.spec.ts
- FOUND: frontend/test/mocks/handlers.ts (modified)

Commits verified:
- FOUND: 2fd27a9 (SearchControllerTest)
- FOUND: 136aa10 (MSW handlers)
- FOUND: 3907920 (todo spec stubs)
