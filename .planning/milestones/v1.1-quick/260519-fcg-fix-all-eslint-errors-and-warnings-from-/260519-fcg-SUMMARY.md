---
status: complete
phase: quick
plan: 260519-fcg
subsystem: frontend
tags: [eslint, lint, code-quality, typescript, vue]
dependency_graph:
  requires: []
  provides: [clean-lint-ci]
  affects: [CI lint step]
tech_stack:
  added: []
  patterns: [eslint-disable-next-line for intentional test-only dynamic delete]
key_files:
  modified:
    - frontend/composables/useAuth.ts
    - frontend/composables/useSearch.ts
    - frontend/composables/useSettings.ts
    - frontend/pages/add.vue
    - frontend/pages/index.vue
    - frontend/pages/forgot-password.vue
    - frontend/pages/login.vue
    - frontend/pages/reset-password.vue
    - frontend/pages/settings.vue
    - frontend/pages/signup.vue
    - frontend/components/FilterPanel.vue
    - frontend/components/InputText.vue
    - frontend/components/MovieCard.vue
    - frontend/components/MovieListItem.vue
    - frontend/components/MovieOfTheDay.vue
    - frontend/components/TrailerEmbed.vue
    - frontend/pages/movies/[id].vue
    - frontend/test/unit/components/MovieCard.spec.ts
    - frontend/test/unit/pages/add.spec.ts
    - frontend/test/unit/pages/login.spec.ts
decisions:
  - Use eslint-disable-next-line for dynamic delete in login.spec.ts test cleanup (intentional test scaffolding, no clean alternative without restructuring vi.mock)
  - Type MOCK_MOVIE as SearchResultItem in MovieCard.spec.ts to eliminate all as any casts with proper types
  - Use Object.entries rebuild in useSearch.ts updateFilter instead of delete to avoid no-dynamic-delete rule
metrics:
  duration: 12min
  completed: 2026-05-19
  tasks_completed: 2
  files_modified: 20
---

# Quick Task 260519-fcg: Fix All ESLint Errors and Warnings

**One-liner:** Resolved all 16 ESLint errors and 29 warnings across 20 frontend files — `pnpm lint` now exits 0.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Fix all 11 ESLint errors | b944dc0 | useAuth.ts, useSearch.ts, useSettings.ts, add.vue, index.vue, add.spec.ts, login.spec.ts, MovieCard.spec.ts |
| 2 | Fix all 29 ESLint warnings | b944dc0 | FilterPanel.vue, InputText.vue, MovieCard.vue, MovieListItem.vue, MovieOfTheDay.vue, TrailerEmbed.vue, add.vue, [id].vue, settings.vue, index.vue, forgot-password.vue, login.vue, reset-password.vue, signup.vue |

## Fixes Applied

### Task 1: Errors

| File | Rule | Fix |
|------|------|-----|
| `useAuth.ts:5` | no-unused-vars | Removed `const router = useRouter()` — dead code, composable uses `navigateTo()` directly |
| `useSearch.ts:168` | no-dynamic-delete | Replaced `delete q[key]` with Object.entries rebuild pattern |
| `useSettings.ts:13,29,37,49` | no-invalid-void-type | Removed `<void>` generic from all four `$fetch<void>()` calls |
| `add.vue:5` | no-unused-vars | Removed unused `ButtonPrimary` import |
| `add.vue:40` | no-explicit-any | Changed `catch (e: any)` to `catch (e: unknown)` with explicit narrowing |
| `index.vue:23` | vue/no-multiple-template-root | Wrapped `<Head>` + `<div>` siblings in a single outer `<div>` |
| `add.spec.ts:16` | no-unused-vars | Removed the `const template = ...` assignment that was never read |
| `login.spec.ts:28` | no-dynamic-delete | Added `// eslint-disable-next-line` — intentional test cleanup, const ref cannot be reassigned |
| `MovieCard.spec.ts:26,37,51,65,85` | no-explicit-any | Imported `SearchResultItem` type; typed `MOCK_MOVIE` and `movieWithoutPoster` properly — all `as any` removed |

### Task 2: Warnings (auto-fixed via `eslint --fix`)

- **vue/html-self-closing** — Removed trailing `/` from void HTML elements (`<input>`, `<img>`, `<hr>`) in 14 files. Vue SFCs require non-self-closing for HTML void elements.
- **vue/attributes-order** — Moved `novalidate` before `@submit.prevent` on form elements in 5 files.
- **vue/attribute-hyphenation** — Renamed `:totalFilms` → `:total-films`, `:topGenres` → `:top-genres`, `:languageBreakdown` → `:language-breakdown` on `<DashboardStats>` in index.vue.

## Verification

```
pnpm lint → Exit: 0, 0 errors, 0 warnings
pnpm test  → 136 tests passed across 22 test files, 0 failures
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing] MovieCard.spec.ts had 5 additional `no-explicit-any` errors not listed in the plan**
- **Found during:** Task 1 (ESLint run revealed 5 extra errors in MovieCard.spec.ts)
- **Fix:** Imported `SearchResultItem` type and typed `MOCK_MOVIE` + `movieWithoutPoster` with proper types — no `as any` needed
- **Files modified:** `frontend/test/unit/components/MovieCard.spec.ts`
- **Commit:** b944dc0

## Self-Check: PASSED

- Commit b944dc0 exists: confirmed via `git log`
- All 20 modified files staged and committed
- `pnpm lint` exits 0: confirmed
- 136 unit tests pass: confirmed
