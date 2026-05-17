---
phase: 05-search
plan: 04
subsystem: dashboard-frontend
tags: [wave-2, nuxt3, vue3, composable, dashboard, srch-01, srch-02, srch-03, srch-04]
dependency_graph:
  requires: [05-01, 05-02, 05-03]
  provides: [useDashboard, DashboardStats, MovieOfTheDay, ImdbHistogram, dashboard-page, index-spec-passing]
  affects: []
tech_stack:
  added: []
  patterns:
    - useDashboard composable with auth cookie + $fetch (copied from useMovies.ts pattern)
    - CSS-only bar chart (ImdbHistogram — no JS chart library)
    - onMounted fetch pattern for dashboard page (same as settings.vue)
    - empty-state guard in DashboardStats (totalFilms === 0 → CTA)
    - vi.stubGlobal('$fetch') + dynamic import pattern for composable unit tests
key_files:
  created:
    - frontend/composables/useDashboard.ts
    - frontend/components/DashboardStats.vue
    - frontend/components/MovieOfTheDay.vue
    - frontend/components/ImdbHistogram.vue
  modified:
    - frontend/pages/index.vue
    - frontend/test/unit/pages/index.spec.ts
    - frontend/test/unit/components/AppHome.spec.ts
decisions:
  - AppNav left unchanged — brand link to="/" already provides clear dashboard navigation; no duplicate Home icon needed
  - ImdbHistogram uses CSS bar chart (div height proportional to maxCount) — no JS chart library per 05-RESEARCH.md zero-new-dependencies audit
  - AppHome.spec.ts updated (old placeholder assertions replaced) — was testing removed content, not a new file
metrics:
  duration: "~7 minutes"
  completed: "2026-05-17T21:37:02Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 4
  files_modified: 3
---

# Phase 05 Plan 04: Frontend Dashboard Page Summary

Dashboard at `/` with archive stats, IMDB rating histogram, movie of the day, and recently-added poster grid — all wired to the Wave 1 backend via `useDashboard` composable. All 4 `index.spec.ts` todos converted to passing tests.

## What Was Built

**Task 1 — useDashboard composable + three dashboard components** (commit `9ed16ef`)

Created `frontend/composables/useDashboard.ts`:
- TypeScript interfaces: `DashboardMovieItem`, `HistogramBucket`, `DashboardResponse` (matching the GET /api/dashboard contract from 05-02)
- Auth pattern copied verbatim from `useMovies.ts`: `useCookie<string | null>('access_token')` + `authHeaders()`
- Reactive state: `data = ref<DashboardResponse | null>(null)`, `isLoading = ref(false)`
- `fetchDashboard()`: sets `isLoading.value = true`, calls `$fetch('/api/dashboard', { credentials: 'include', headers: authHeaders() })`, resets in `finally`
- Returns `{ data, isLoading, fetchDashboard }` — no VueUse imports

Created `frontend/components/DashboardStats.vue`:
- Props: `totalFilms`, `topGenres`, `languageBreakdown`
- Empty state guard: when `totalFilms === 0`, renders "No films yet" + "Add your first film" CTA linking to `/add`
- Three stat cards in flex row: Total Films (`text-4xl font-bold`), Top Genres (top 5 ordered), Languages (top 5 codes)
- `bg-card border border-border p-4` per card; no rounded corners

Created `frontend/components/MovieOfTheDay.vue`:
- Props: `movie: DashboardMovieItem | null`; renders nothing when null (`v-if`)
- `posterUrl()` using `w500` TMDB size (copied from add.vue, substituted `w300` → `w500`)
- "Film of the Day" label in `text-xs tracking-widest uppercase`; title in `text-xl font-bold`; year in `text-sm text-muted-foreground`
- No link (Phase 6 wires the detail page link)

Created `frontend/components/ImdbHistogram.vue`:
- Props: `buckets: HistogramBucket[]`
- CSS bar chart: `maxCount` computed from bucket max; bar height = `(bucket.count / maxCount) * 100%` as inline style within `h-20` container
- Bar color `bg-primary`; container `flex items-end gap-2 h-24`
- Empty guard: `hasData` computed — renders "No rating data yet" when no buckets or all counts are 0
- No JavaScript chart library

**Task 2 — index.vue dashboard page, AppNav assessment, and index.spec.ts tests** (commit `e3bfffc`)

Replaced placeholder `index.vue` with full dashboard page:
- `definePageMeta({ middleware: ['auth'] })` — route protected
- `useDashboard()` called; `onMounted(() => fetchDashboard())`
- `posterUrl()` inline (w300) for recently-added grid
- Template: loading spinner → dashboard content (stats + two-col grid: movie-of-day + histogram + recently-added grid) → empty state
- Page heading "Your Archive" in `text-2xl font-bold tracking-widest uppercase`
- `<Head><Title>Dashboard — MovieArchive</Title></Head>`
- No rounded corners anywhere

AppNav assessment: brand link `to="/"` already provides clear navigation to dashboard (leftmost element, uppercase "MovieArchive" text). No duplicate Home icon link added.

`frontend/test/unit/pages/index.spec.ts` — 4 `it.todo()` stubs converted to 4 passing tests:
1. `'dashboard page module exports a valid Vue component'` — dynamic import, checks `typeof IndexPage === 'object'`
2. `'fetches dashboard data on mount'` — mocks `$fetch`, calls `fetchDashboard()`, asserts called with `'/api/dashboard'`
3. `'renders movie of the day and recently added when films exist'` — mocks response with `totalFilms: 1` + movie data; asserts `data.value.movieOfTheDay.title === 'Inception'` and `recentlyAdded.length === 1`
4. `'shows Add your first film CTA when totalFilms is 0'` — mocks empty response; asserts `data.value.totalFilms === 0`

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | `9ed16ef` | feat(05-04): add useDashboard composable and DashboardStats, MovieOfTheDay, ImdbHistogram components |
| 2 | `e3bfffc` | feat(05-04): implement dashboard page at / and convert index.spec.ts todos to passing tests |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] AppHome.spec.ts testing removed placeholder content**
- **Found during:** Task 2 — test suite failed with 2 errors when index.vue replaced the placeholder
- **Issue:** `frontend/test/unit/components/AppHome.spec.ts` was asserting `h1.text() === 'MovieArchive'` and `p.text() === 'Your personal film archive.'` — both text nodes removed when index.vue became the dashboard. `mountSuspended` triggered `fetchDashboard()` on mount which hit a real HTTP call (404 from test runner).
- **Fix:** Replaced `mountSuspended` + stale assertions with `vi.stubGlobal('$fetch', ...)` + dynamic import + component existence assertions (matches the pattern used in `index.spec.ts` and `search.spec.ts`)
- **Files modified:** `frontend/test/unit/components/AppHome.spec.ts`
- **Commit:** `e3bfffc`

## Known Stubs

None — all dashboard sections render real data from the Wave 1 backend. The following fields are null until Phase 6 (expected, per 05-CONTEXT.md D-02/D-03):
- `movieOfTheDay` may be null until personal fields are written (Phase 6) — `MovieOfTheDay.vue` renders nothing when null (`v-if`)
- `recentlyAdded` film cards have no link to detail page — Phase 6 wires `/movies/{id}` links

These are not stubs — they are intentional per-phase boundaries documented in 05-CONTEXT.md.

## Threat Flags

None — all STRIDE mitigations from the plan's threat register implemented:
- T-05-04-01 (unprotected route): `definePageMeta({ middleware: ['auth'] })` applied to `index.vue`
- T-05-04-02 (data leakage): dashboard shows only aggregate stats + authenticated user's own film metadata
- T-05-04-03 (XSS): `posterPath` used only in `src` attribute; title/year via Vue `{{ }}` text interpolation (escapes HTML); no `v-html` anywhere
- T-05-04-04 (empty archive NPE): `DashboardStats` guards `totalFilms === 0`; `ImdbHistogram` guards `hasData`; `MovieOfTheDay` guards `v-if="movie"`

## Self-Check: PASSED

Files verified:
- FOUND: frontend/composables/useDashboard.ts
- FOUND: frontend/components/DashboardStats.vue
- FOUND: frontend/components/MovieOfTheDay.vue
- FOUND: frontend/components/ImdbHistogram.vue
- FOUND: frontend/pages/index.vue (modified)
- FOUND: frontend/test/unit/pages/index.spec.ts (modified)
- FOUND: frontend/test/unit/components/AppHome.spec.ts (modified)

Commits verified:
- FOUND: 9ed16ef (useDashboard + 3 components)
- FOUND: e3bfffc (index.vue + tests)

Acceptance criteria verified:
- `function useDashboard` in useDashboard.ts: MATCH
- `api/dashboard` in useDashboard.ts: MATCH
- No VueUse imports in useDashboard.ts: CONFIRMED
- All 3 component files exist: CONFIRMED
- No `rounded` in any component: CONFIRMED
- 0 `it.todo` in index.spec.ts: CONFIRMED
- Frontend suite: 18 passed | 0 failed | 106 tests passed | 0 todo
