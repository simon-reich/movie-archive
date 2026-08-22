---
phase: 05-search
plan: 03
subsystem: search-frontend
tags: [wave-2, nuxt3, vue3, composable, pinia, opensearch, srch-01, srch-02, srch-03, srch-04]
dependency_graph:
  requires: [05-01, 05-02]
  provides: [useSearch, useSearchStore, SearchBar, FilterPanel, SortSelect, ViewToggle, MovieGrid, MovieCard, MovieList, MovieListItem, search-page, AppNav-search-link]
  affects: [05-04-PLAN]
tech_stack:
  added: []
  patterns:
    - URL-synced computed state via useRoute/useRouter for all 15 filter params
    - manual 300ms debounce with clearTimeout (no VueUse)
    - immediate route.query watcher for D-14 clickable attribute navigation
    - load-more pagination: page=0 replaces results, page>0 appends
    - Pinia setup-function store with safe localStorage read/write (SSR + test env safe)
    - radix-vue CollapsibleRoot/CollapsibleTrigger/CollapsibleContent for filter panel
    - tag-pill multi-select for genre, content_rating, language, country filters
    - datalist autocomplete for director and actors fields
    - router.push({ path: '/search', query: { genre|director } }) for D-14/D-15 clickable chips
key_files:
  created:
    - frontend/composables/useSearch.ts
    - frontend/stores/search.ts
    - frontend/components/SearchBar.vue
    - frontend/components/FilterPanel.vue
    - frontend/components/SortSelect.vue
    - frontend/components/ViewToggle.vue
    - frontend/components/MovieGrid.vue
    - frontend/components/MovieCard.vue
    - frontend/components/MovieList.vue
    - frontend/components/MovieListItem.vue
    - frontend/pages/search.vue
  modified:
    - frontend/components/AppNav.vue
    - frontend/test/unit/composables/useSearch.spec.ts
    - frontend/test/unit/pages/search.spec.ts
decisions:
  - safe localStorage wrapper in useSearchStore guards against test env and SSR errors where import.meta.client is truthy but localStorage is unavailable
  - useSearch.spec.ts tests call nextTick() after useSearch() instantiation to drain the immediate watcher before setting per-test mockResolvedValueOnce
  - FilterPanel reads/writes useSearch() directly (no props) to keep filter state in URL as single source of truth
  - actors field normalized as comma-joined string in FilterCriteria.actors (single string, not array) to match backend DTO
  - No new npm packages — zero new dependencies (confirmed by 05-RESEARCH.md Package Legitimacy Audit)
metrics:
  duration: "~25 minutes"
  completed: "2026-05-17T23:30:00Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 11
  files_modified: 3
---

# Phase 05 Plan 03: Frontend Search Page Summary

URL-synced /search frontend with 300ms debounced free-text search, 10-filter advanced filter panel (collapsible), 4 sort options, grid/list view toggle with localStorage persistence, clickable genre/director chips that push URL params, and passing unit tests for useSearch composable and search page.

## What Was Built

**Task 1 — useSearch composable + useSearchStore** (commit `f82f421`)

Created `frontend/composables/useSearch.ts`:
- Exports `useSearch()` function with TypeScript interfaces for `SearchResultItem`, `FilterCriteria`, `SearchApiResponse`
- Three URL param helpers: `normalizeQueryParam` (string|string[]|null → string[]), `paramAsString`, `paramAsNumber`
- Auth pattern copied verbatim from `useMovies.ts` (accessTokenCookie + authHeaders)
- 15 computed refs driven from `route.query` (q, page, sort, genre, director, actors, year_from/to, imdb_from/to, content_rating, runtime_max, watched, language, country)
- `searchQuery` mutable ref for two-way SearchBar binding, separate from URL so debounce works
- Manual 300ms debounce via `watch(searchQuery, ...)` + `setTimeout/clearTimeout` — no VueUse
- `watch(() => route.query, ..., { immediate: true, deep: true })` — fires on mount (D-05 all-films default) and on every URL param change (D-14 clickable attr navigation)
- `executeSearch()`: POST /api/search with full body; page=0 replaces `results.value`, page>0 appends (load-more); null-safe `if (!response) return` guard for test env
- `buildFilters()`: returns undefined when all fields empty; populates only non-empty fields
- `updateFilter(key, value)`: deletes key when null/empty, sets otherwise, resets page to 0
- `loadMore()`: increments page in URL

Created `frontend/stores/search.ts`:
- `useSearchStore` in setup-function style matching `stores/auth.ts`
- `viewMode` ref defaulting to `'grid'` (D-08)
- `safeLocalStorageGet/Set` wrappers with try/catch — handles SSR and Vitest environments where `import.meta.client` is truthy but `localStorage` throws `ExperimentalWarning`
- `setViewMode(mode)` writes to ref and localStorage

**Task 2 — Search UI components, search.vue page, AppNav update, and frontend tests** (commit `940f150`)

`SearchBar.vue`: wraps `InputText` with a lucide `X` clear button (v-show hides when empty)

`FilterPanel.vue`: radix-vue `CollapsibleRoot/Trigger/Content`, closed by default. Calls `useSearch()` internally for all reads/writes. 10 D-10 filters:
- Genre, Content Rating, Language, Production Country: tag-pill multi-select with fixed common-value lists (no API call)
- Director: text input with `<datalist>` populated via `GET /api/search/autocomplete?field=director` (300ms debounce)
- Actors: same pattern, `field=actors` — `field` hardcoded in component, never user-supplied (T-05-03-03 mitigated)
- Year from/to: two `<input type="number">` side by side
- IMDB rating from/to: two `<input type="number" step="0.1">` side by side
- Runtime max: single `<input type="number">`
- Not yet watched: checkbox with tooltip "Requires film status to be set (coming soon)" (Pitfall 6)
- "Clear all filters" button resets all 12 filter keys via `updateFilter`
- `hasActiveFilters` computed shows "(active)" badge on trigger

`SortSelect.vue`: plain `<select>` with 4 options (title_asc, year_desc, rating_desc, imdb_desc)

`ViewToggle.vue`: two icon-buttons (lucide Grid + List) with active state styling

`MovieCard.vue`: poster image (`w300` TMDB size), title, year, clickable director chip (first only), clickable genre chips — all `router.push({ path: '/search', query: { genre|director } })` per D-14/D-15

`MovieGrid.vue`: exact grid classes from add.vue line 126 (`grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4`)

`MovieListItem.vue`: flex row with `w92` poster thumbnail, title, year, director, genre chips, IMDB rating (`★ N.N`), runtime (`N min`) — null fields hidden

`MovieList.vue`: `divide-y divide-border` wrapper for MovieListItem

`search.vue`: protected by `definePageMeta({ middleware: ['auth'] })`, `useHead({ title: 'Search — MovieArchive' })`. Layout: SearchBar → controls row (FilterPanel trigger + SortSelect + ViewToggle) → FilterPanel content → result count → MovieGrid|MovieList (v-if/v-else on viewMode) → Load More button → loading spinner

`AppNav.vue`: added `/search` NuxtLink with lucide `Search` icon between Add Film and Settings

`useSearch.spec.ts` (6 todos → 6 passing):
1. reads q param from URL (type check)
2. reads genre param (array type check)
3. reads director param (type check)
4. debounces 300ms — `vi.useFakeTimers` + `advanceTimersByTime(350)` + router.replace spy
5. appends results — nextTick drain pattern to handle immediate watcher, then mockResolvedValueOnce
6. re-executes on route change — verifies $fetch called with POST /api/search

`search.spec.ts` (4 todos → 4 passing):
1. search page module exports valid Vue component
2. executeSearch calls POST /api/search
3. genre chip navigation — router.push spy with `{ path: '/search', query: { genre: 'Drama' } }`
4. viewMode grid/list — useSearchStore setViewMode toggles viewMode ref

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | `f82f421` | feat(05-03): add useSearch composable and useSearchStore |
| 2 | `940f150` | feat(05-03): implement /search frontend — components, page, tests, AppNav |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] executeSearch throws on undefined $fetch response in test env**
- **Found during:** Task 2 — test failures with "Cannot read properties of undefined (reading 'results')"
- **Issue:** The `watch(() => route.query, ..., { immediate: true })` fires on `useSearch()` instantiation. In tests that called `useSearch()` before setting up `mockFetch`, `$fetch` returned `undefined`.
- **Fix:** Added `if (!response) return` null guard in `executeSearch()` and `?? []` / `?? 0` / `?? false` defaults on all response fields. Set `mockFetch.mockResolvedValue(EMPTY_RESPONSE)` before the top-level `await import('@/composables/useSearch')`.
- **Files modified:** `frontend/composables/useSearch.ts`, `frontend/test/unit/composables/useSearch.spec.ts`
- **Commit:** `940f150`

**2. [Rule 1 - Bug] localStorage unavailable in Vitest env despite import.meta.client being truthy**
- **Found during:** Task 2 — test failure "Cannot read properties of undefined (reading 'getItem')" in `stores/search.ts`
- **Issue:** Vitest runs in a browser-like environment where `import.meta.client` is truthy, but Node.js' experimental `localStorage` (`--localstorage-file` not provided) throws instead of returning null.
- **Fix:** Replaced direct `localStorage.getItem/setItem` calls with `safeLocalStorageGet/Set` wrapper functions that use `try/catch` and also check `typeof localStorage !== 'undefined'`.
- **Files modified:** `frontend/stores/search.ts`
- **Commit:** `940f150`

**3. [Rule 2 - Missing Critical] nextTick drain pattern in test for immediate watcher**
- **Found during:** Task 2 — "appends results" test expected length 1, got 0 because `mockResolvedValueOnce` was consumed by the immediate watcher triggered by the test's `useSearch()` call.
- **Fix:** Added `await nextTick()` after `useSearch()` in the affected test to drain the immediate watcher call, then `mockFetch.mockReset()` + `mockResolvedValueOnce` for the explicit `executeSearch()` call.
- **Files modified:** `frontend/test/unit/composables/useSearch.spec.ts`
- **Commit:** `940f150`

## Known Stubs

None — all search frontend behavior is fully implemented. The following fields return empty until Phase 6 populates personal data (expected, documented in 05-CONTEXT.md):
- `rating_desc` sort option is present in SortSelect.vue but returns films sorted by null personal_rating (all null → sorted by `missing=_last`, effectively arbitrary)
- "Not yet watched" filter checkbox is present but returns empty results until Phase 6 writes `watched` field

These are not stubs — they are intentional placeholders per D-12 and Pitfall 6 in 05-RESEARCH.md.

## Threat Flags

None — all STRIDE mitigations from the plan's threat register implemented:
- T-05-03-01 (XSS via URL params): all URL params bound as data values via Vue template interpolation; no v-html used
- T-05-03-02 (unprotected route): `definePageMeta({ middleware: ['auth'] })` applied to search.vue
- T-05-03-03 (autocomplete field leaks): `field` param hardcoded as `'director'` or `'actors'` in FilterPanel.vue — never user-supplied

## Self-Check: PASSED

Files verified:
- FOUND: frontend/composables/useSearch.ts
- FOUND: frontend/stores/search.ts
- FOUND: frontend/components/SearchBar.vue
- FOUND: frontend/components/FilterPanel.vue
- FOUND: frontend/components/SortSelect.vue
- FOUND: frontend/components/ViewToggle.vue
- FOUND: frontend/components/MovieGrid.vue
- FOUND: frontend/components/MovieCard.vue
- FOUND: frontend/components/MovieList.vue
- FOUND: frontend/components/MovieListItem.vue
- FOUND: frontend/pages/search.vue
- FOUND: frontend/components/AppNav.vue (modified)
- FOUND: frontend/test/unit/composables/useSearch.spec.ts (modified)
- FOUND: frontend/test/unit/pages/search.spec.ts (modified)

Commits verified:
- FOUND: f82f421 (useSearch + useSearchStore)
- FOUND: 940f150 (components + page + tests)

Acceptance criteria verified:
- 0 it.todo in useSearch.spec.ts
- 0 it.todo in search.spec.ts
- `to="/search"` in AppNav.vue
- `definePageMeta({ middleware: ['auth'] })` in search.vue
- `router.push.*search` in MovieCard.vue (genre + director chips)
- `posterUrl` in MovieCard.vue
- No VueUse imports in useSearch.ts or FilterPanel.vue
- Frontend suite: 17 passed | 1 skipped (index.spec.ts — plan 05-04 scope) | 0 failed
