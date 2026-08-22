---
phase: 06-movie-detail-personal-fields
plan: "04"
subsystem: frontend
tags: [page, detail, cinematic-layout, vitest, vue3, typescript]
dependency_graph:
  requires:
    - 06-03 (useMovieDetail composable — movie ref, isLoading, error, updatePersonal, deleteMovie)
  provides:
    - /movies/[id].vue detail page (cinematic hero + two-column layout)
    - StarRating.vue stub component (full impl in 06-05)
    - TrailerEmbed.vue stub component (full impl in 06-05)
  affects:
    - 06-05 (StarRating and TrailerEmbed components replace stubs created here)
tech_stack:
  added: []
  patterns:
    - Nuxt page with useMovieDetail composable (auto-imported useRoute/useRouter)
    - v-if null-guards for optional OMDB fields (D-10)
    - watch(movie, ...) to sync local personal fields on load
    - Manual 1s debounce for notes auto-save (setTimeout)
    - Delete confirmation modal (plain div overlay — no external dialog lib)
    - vi.spyOn(useRouter(), 'push') for navigation assertions in Vitest
key_files:
  created:
    - frontend/pages/movies/[id].vue
    - frontend/components/StarRating.vue
    - frontend/components/TrailerEmbed.vue
  modified:
    - frontend/test/unit/pages/movies-id.spec.ts
decisions:
  - Used vi.spyOn(useRouter(), 'push') instead of vi.stubGlobal('useRouter') — Nuxt test environment provides its own real router, stubGlobal is bypassed
  - StarRating and TrailerEmbed created as stub components (empty render) so page compiles and tests pass before plan 06-05 implements them
  - Director buttons use hover:text-primary class (inline style) while actor/genre use bg-card border chips — allows test to distinguish director from cast buttons
metrics:
  duration: ~30min
  completed: 2026-05-18
  tasks_completed: 1
  files_changed: 4
---

# Phase 6 Plan 04: /movies/[id].vue Detail Page Summary

Full cinematic detail page at `/movies/[id]` with w1280 backdrop hero, two-column editorial layout, individually-null-guarded OMDB fields, conditional Wikipedia sections, clickable actor/director/genre navigation, delete confirmation modal, and full cast & crew credits — 12 Vitest tests green.

## What Was Built

### frontend/pages/movies/[id].vue

Complete detail page implementing all D-02 through D-13 decisions:

**Hero (D-02):**
- Full-width `h-72` backdrop at `https://image.tmdb.org/t/p/w1280{backdropPath}`
- Dark gradient overlay (`from-black/80 via-black/50 to-transparent`)
- Poster overlaid bottom-left at `https://image.tmdb.org/t/p/w342{posterPath}` (`w-32 aspect-[2/3]`)
- Fallback: `bg-card` div when backdrop/poster path is null
- Delete button top-right (`TrashIcon` + "Remove"), opens `deleteModalOpen = true`

**Two-column body (D-03, `grid-cols-3`):**
- Left (`col-span-2`): genres, directors, writers, cast chips, country/language, ratings, synopsis, Wikipedia plot + critics
- Right (`col-span-1`): `<TrailerEmbed>` + personal fields panel (watched checkbox, `<StarRating>`, notes textarea)

**OMDB null-guarding (D-10):**
- `v-if="movie.imdbRating !== null"` — IMDB rating block
- `v-if="movie.contentRating"` — content rating badge in hero
- `v-if="movie.boxOffice !== null"` — box office block
- `v-if="movie.ratingList?.length"` — RT/Metacritic block

**Wikipedia conditional (DETAIL-02):**
- `v-if="movie.wikipediaPlot"` — Plot section
- `v-if="movie.wikipediaCritics"` — Critical Response section

**Clickable navigation (D-12):**
- Actor chips: `router.push({ path: '/search', query: { actors: name } })`
- Director buttons: `router.push({ path: '/search', query: { director: name } })`
- Genre chips: `router.push({ path: '/search', query: { genre } })`

**Personal fields (D-06, D-07):**
- `watch(movie, ...)` syncs local state (`localWatched`, `localRating`, `localNotes`) on load
- `onWatchedChange()` → immediate `updatePersonal({ watched })`
- `onRatingChange(rating)` → immediate `updatePersonal({ personalRating })`
- `onNotesInput()` → 1s debounce then `updatePersonal({ personalNotes })`

**Delete modal (D-13):**
- Text: "Remove from archive?" / "This cannot be undone."
- Confirm: calls `deleteMovie()` (composable handles DELETE + `router.push('/search')`)
- Cancel: closes modal

**Full cast & crew (D-05):**
- `grid-cols-3` section at page bottom, full width
- Cast column: fullCast sorted by `order`, each clickable to `/search?actors=`
- Crew columns: fullCrew grouped by `department` via `crewByDepartment` computed

### Stub components

- `StarRating.vue` — empty template, accepts `modelValue`/`update:modelValue`, replaced in plan 06-05
- `TrailerEmbed.vue` — empty `aspect-video bg-card` div, accepts `trailerKey`, replaced in plan 06-05

### frontend/test/unit/pages/movies-id.spec.ts

12 tests replacing all `it.todo` stubs:

| # | Test | Result |
|---|------|--------|
| 1 | renders film title in hero section | PASS |
| 2 | renders backdrop image with correct TMDB w1280 URL | PASS |
| 3 | renders Wikipedia plot section when wikipediaPlot is non-null | PASS |
| 4 | hides Wikipedia plot section when wikipediaPlot is null | PASS |
| 5 | hides OMDB fields when null (D-10) | PASS |
| 6 | renders OMDB fields when present | PASS |
| 7 | navigates to /search?actors=X when actor chip clicked (D-12) | PASS |
| 8 | navigates to /search?director=X when director chip clicked | PASS |
| 9 | navigates to /search?genre=X when genre chip clicked | PASS |
| 10 | shows delete confirmation modal on delete button click | PASS |
| 11 | calls deleteMovie and redirects to /search on modal confirm | PASS |
| 12 | hides Wikipedia critics section when null | PASS |

Full suite: **19 test files, 118 tests — all green.**

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Navigation tests used vi.stubGlobal instead of vi.spyOn on real Nuxt router**
- **Found during:** GREEN phase (3 navigation tests failed)
- **Issue:** `vi.stubGlobal('useRouter', ...)` does not override the Nuxt test environment's auto-imported `useRouter`. The component gets the real Nuxt router, not the stub, so `mockRouterPush` was never called.
- **Fix:** Replaced `vi.stubGlobal('useRouter', ...)` with `vi.spyOn(useRouter(), 'push')` inside each navigation test — same pattern used in `search.spec.ts`.
- **Files modified:** `frontend/test/unit/pages/movies-id.spec.ts`
- **Commit:** 91bd6e8

**2. [Rule 3 - Blocking] reset --soft left working tree diverged from target commit**
- **Found during:** Pre-execution (worktree base check)
- **Issue:** `reset --soft 7aee1a4` moved HEAD but left the working tree and index reflecting the pre-merge state (29f2ca2). Files present in 7aee1a4 (from plan 06-03 and earlier) were missing from disk.
- **Fix:** Ran `git checkout 7aee1a4 -- <files>` to restore all files that belonged to the target commit before staging and committing only plan 06-04 changes.
- **Files affected:** backend files, planning summaries, and frontend composable/test files from prior plans
- **Commit:** 91bd6e8 (only plan 06-04 files staged)

## Threat Flags

None. All threats from the plan's threat model are mitigated:
- T-06-04-02: No `v-html` used anywhere — all content rendered via `{{ }}` interpolation (Vue auto-escapes)
- T-06-04-03: `imdbLink` rendered as `<a :href="movie.imdbLink">` — Vue does not render `javascript:` URLs in `:href` bindings
- T-06-04-04: Global auth middleware protects `/movies/:id` route automatically

## Known Stubs

| Stub | File | Reason |
|------|------|--------|
| `StarRating.vue` | `frontend/components/StarRating.vue` | Empty stub — full 10-star interactive rating widget implemented in plan 06-05 |
| `TrailerEmbed.vue` | `frontend/components/TrailerEmbed.vue` | Empty stub — YouTube thumbnail + lazy iframe implemented in plan 06-05 |

Both stubs render valid (but empty) HTML. The detail page compiles and all 12 tests pass with the stubs in place. Plan 06-05 replaces these with full implementations.

## Test Results

```
Test Files  19 passed (19)
Tests       118 passed (118)
```

All 12 new page tests green. Full suite passes.

## Self-Check: PASSED

- `frontend/pages/movies/[id].vue` — FOUND
- `frontend/components/StarRating.vue` — FOUND
- `frontend/components/TrailerEmbed.vue` — FOUND
- `frontend/test/unit/pages/movies-id.spec.ts` — FOUND (0 it.todo)
- Commit 91bd6e8 — FOUND
- `grep "useMovieDetail"` — 2 matches (import + call)
- `grep "navigateToActor|navigateToDirector|navigateToGenre"` — 7 matches
- `grep "v-if.*wikipediaPlot|v-if.*wikipediaCritics"` — 2 matches
- `grep "v-if.*imdbRating|v-if.*contentRating|v-if.*boxOffice|v-if.*ratingList"` — 4 matches
- `grep "w1280|w342"` — 2 matches
- `grep "TrailerEmbed|StarRating"` — 5 matches
- `grep "it.todo"` — 0 matches
- Vitest: 12/12 page tests green, 118/118 full suite green
