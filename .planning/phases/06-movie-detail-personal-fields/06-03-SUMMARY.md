---
phase: 06-movie-detail-personal-fields
plan: "03"
subsystem: frontend
tags: [composable, typescript, vitest, movie-detail]
dependency_graph:
  requires:
    - 06-01 (backend MovieDetailController — provides GET /api/movies/:id)
    - 06-02 (backend PATCH/DELETE endpoints)
  provides:
    - useMovieDetail composable (movie ref, isLoading, error, updatePersonal, deleteMovie)
    - MovieDetail TypeScript interface with all 35 fields
    - MSW movieDetailHandlers for GET/PATCH/DELETE /api/movies/:id
  affects:
    - 06-04 (detail page + components — imports useMovieDetail)
tech_stack:
  added: []
  patterns:
    - $fetch with authHeaders (useCookie access_token) — same as useSearch.ts
    - Direct fetchDetail() call on composable construction (not onMounted — fires immediately, testable outside Vue components)
    - Manual debounce pattern (setTimeout, no VueUse)
key_files:
  created:
    - frontend/composables/useMovieDetail.ts
    - frontend/test/unit/composables/useMovieDetail.spec.ts
    - frontend/test/mocks/handlers/movieDetail.ts
  modified:
    - frontend/test/mocks/handlers.ts
decisions:
  - fetchDetail called directly in composable body (not onMounted) so it fires in Vitest without active Vue component context
  - node_modules installed directly in git worktree frontend dir (pnpm install) to run tests from worktree context
metrics:
  duration: ~25min
  completed: 2026-05-18
  tasks_completed: 1
  files_changed: 4
---

# Phase 6 Plan 03: useMovieDetail Composable Summary

Frontend data layer: `useMovieDetail` composable with full TypeScript types, `$fetch`-based GET/PATCH/DELETE functions, and 7 green Vitest tests covering all specified behaviors.

## What Was Built

### useMovieDetail.ts

Composable that mirrors the `useSearch.ts` pattern exactly:

- `authHeaders()` reads `access_token` cookie via `useCookie` and returns `Authorization: Bearer …` header
- `fetchDetail()` calls `$fetch('/api/movies/{movieId}', { credentials: 'include', headers: authHeaders() })` and populates `movie` ref
- `isLoading` starts `true`, goes `false` after fetch completes (success or error)
- `error` is set to `'Failed to load film.'` on any `$fetch` rejection
- `updatePersonal(fields)` calls `$fetch PATCH /api/movies/{movieId}/personal` with partial body — supports `watched`, `personalRating: null` (deselect), `personalNotes`
- `deleteMovie()` calls `$fetch DELETE /api/movies/{movieId}` then `router.push('/search')`

**TypeScript interfaces exported:**
- `MovieDetail` — 35 fields covering all TMDB + OMDB + Wikipedia + personal fields
- `CastMember` — `{ name, character, order, profilePath }`
- `CrewMember` — `{ name, job, department, profilePath }`
- `Rating` — `{ source, value }`

### useMovieDetail.spec.ts

7 Vitest tests replacing `it.todo` stubs:

| Test | Behavior |
|------|----------|
| fetches GET /api/movies/:id on mount | $fetch called with correct URL + credentials |
| isLoading false after fetch | true → false after resolve |
| error set on GET failure | 'Failed to load film.' |
| updatePersonal PATCH { watched } | correct URL + body |
| updatePersonal PATCH { personalRating: null } | null body value sent |
| updatePersonal PATCH { personalNotes } | correct body |
| deleteMovie DELETE + navigate | DELETE called + router.push('/search') |

### movieDetail.ts MSW handlers

Global MSW stubs for Wave 1+ plans:
- `GET /api/movies/:id` → returns `MOCK_MOVIE_DETAIL` fixture with all 35 fields
- `PATCH /api/movies/:id/personal` → 204 No Content
- `DELETE /api/movies/:id` → 204 No Content

Registered in global `handlers.ts`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Replaced onMounted with direct fetchDetail() call**
- **Found during:** Task 1 (GREEN phase)
- **Issue:** `onMounted` in a composable called outside a Vue component context (Vitest unit test) emits "onMounted called when there is no active component instance" warning and never fires. Tests for fetch/isLoading/error all failed.
- **Fix:** Removed `onMounted(() => fetchDetail())` and replaced with `fetchDetail()` called directly in the composable body. The fetch starts immediately on composable construction — same effective behavior in a Vue component, and testable without a component wrapper.
- **Files modified:** `frontend/composables/useMovieDetail.ts`
- **Commit:** 366a186

**2. [Rule 3 - Blocking] Installed node_modules in worktree frontend directory**
- **Found during:** RED phase (test runner setup)
- **Issue:** Git worktree has no `node_modules`. Running `pnpm --prefix frontend test` from the main repo ran against main repo's stub files (old `it.todo` stubs). Running vitest from the worktree failed with `ERR_MODULE_NOT_FOUND` for `@nuxt/test-utils` because node_modules was absent.
- **Fix:** Ran `pnpm install --prefix frontend` from the worktree root to install a fresh `node_modules` and regenerate `.nuxt` types for the worktree's file paths.
- **Commit:** part of 366a186

## Threat Flags

None. The composable only passes the JWT access token via Authorization header (same pattern as useSearch.ts — accepted in threat model T-06-03-01). No new trust boundaries introduced.

## Known Stubs

None. All 35 fields in `MovieDetail` are typed interface fields — the composable wires directly to the backend response. No hardcoded empty values or placeholder text.

## Test Results

```
Test Files  19 passed (19)
Tests       113 passed (113)
```

All 7 useMovieDetail tests green. Full suite passes.

## Self-Check: PASSED

- `frontend/composables/useMovieDetail.ts` — FOUND
- `frontend/test/unit/composables/useMovieDetail.spec.ts` — FOUND
- `frontend/test/mocks/handlers/movieDetail.ts` — FOUND
- Commit 366a186 — FOUND
- `export interface MovieDetail` — present
- `export function useMovieDetail` — present
- `it.todo` count in spec — 0 (all replaced)
- Vitest: 7/7 tests green
