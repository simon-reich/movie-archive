---
phase: "06"
plan: "00"
subsystem: test-scaffolding
tags: [tests, stubs, msw, vitest, backend-integration]
dependency_graph:
  requires: []
  provides:
    - MovieDetailControllerTest (13 @Disabled backend integration test stubs)
    - movieDetailHandlers (MSW stubs for GET/PATCH/DELETE)
    - useMovieDetail.spec.ts (7 it.todo composable stubs)
    - movies-id.spec.ts (12 it.todo page stubs)
    - TrailerEmbed.spec.ts (5 it.todo component stubs)
    - useMovieDetail.ts (stub composable for import resolution)
  affects:
    - frontend/test/mocks/handlers.ts (movieDetailHandlers registered)
tech_stack:
  added: []
  patterns:
    - "@Disabled class-level annotation to skip all 13 backend stubs in one declaration"
    - "Stub composable created to satisfy Vite import resolution for Wave 0 test scaffolding"
key_files:
  created:
    - backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java
    - frontend/test/mocks/handlers/movieDetail.ts
    - frontend/test/unit/composables/useMovieDetail.spec.ts
    - frontend/test/unit/pages/movies-id.spec.ts
    - frontend/test/unit/components/TrailerEmbed.spec.ts
    - frontend/composables/useMovieDetail.ts
  modified:
    - frontend/test/mocks/handlers.ts
decisions:
  - "Stub composable useMovieDetail.ts created in Wave 0 to unblock import resolution (Vite fails fast on missing modules even for it.todo tests)"
metrics:
  duration: "~10 minutes"
  completed_date: "2026-05-18"
  tasks_completed: 2
  tasks_total: 2
  files_created: 6
  files_modified: 1
---

# Phase 06 Plan 00: Wave 0 Test Scaffolding Summary

Wave 0 Nyquist contract: 13 backend @Disabled stubs + 3 frontend spec files (24 it.todo tests) + MSW handlers for GET/PATCH/DELETE movie detail endpoints.

## What Was Built

Created all Wave 0 test scaffolding for Phase 6 movie detail and personal fields. This establishes the verification contract that plans 06-01 through 06-05 will satisfy by implementing real code and un-disabling the tests.

### Task 1: Backend @Disabled Integration Test Stubs

`MovieDetailControllerTest` extends `AbstractOpenSearchTest` (Postgres + OpenSearch containers). All 13 tests are `@Disabled` at the class level:

- **GET /movies/{id}** (4 stubs): full fields, OMDB null when absent, 404 wrong user, 404 unknown UUID
- **PATCH /movies/{id}/personal** (6 stubs): watched, personalRating, clear rating, personalNotes, OS sync, 404 wrong user
- **DELETE /movies/{id}** (3 stubs): removes from Postgres + OS, 404 wrong user, 404 unknown UUID

Gradle exits 0 with all tests skipped.

### Task 2: MSW Handlers and Frontend Vitest Stubs

- **`frontend/test/mocks/handlers/movieDetail.ts`**: `MOCK_MOVIE_DETAIL` fixture (all 35+ fields) + `movieDetailHandlers` array with GET/PATCH/DELETE stubs
- **`frontend/test/mocks/handlers.ts`**: `movieDetailHandlers` imported and spread into main handlers array
- **`useMovieDetail.spec.ts`**: 7 `it.todo` stubs for composable behavior
- **`movies-id.spec.ts`**: 12 `it.todo` stubs for page rendering and navigation
- **`TrailerEmbed.spec.ts`**: 5 `it.todo` stubs for lazy embed toggle

Frontend test suite exits 0 with 24 new pending/todo tests.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created stub composable useMovieDetail.ts**
- **Found during:** Task 2 verification
- **Issue:** Vite's import analysis resolves module paths at compile time — `await import('@/composables/useMovieDetail')` in the spec file caused a build-time error even though all tests are `it.todo`. The plan's spec template included the import at module level.
- **Fix:** Created `frontend/composables/useMovieDetail.ts` as a minimal stub returning the expected shape. This satisfies Vite's module resolution without implementing real behavior.
- **Files modified:** `frontend/composables/useMovieDetail.ts` (created)
- **Commit:** a9d404c

## Self-Check: PASSED

Files exist:
- FOUND: backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java
- FOUND: frontend/test/mocks/handlers/movieDetail.ts
- FOUND: frontend/test/unit/composables/useMovieDetail.spec.ts
- FOUND: frontend/test/unit/pages/movies-id.spec.ts
- FOUND: frontend/test/unit/components/TrailerEmbed.spec.ts
- FOUND: frontend/composables/useMovieDetail.ts

Commits exist:
- FOUND: f2e9765 (Task 1 — backend stubs)
- FOUND: a9d404c (Task 2 — frontend stubs)
