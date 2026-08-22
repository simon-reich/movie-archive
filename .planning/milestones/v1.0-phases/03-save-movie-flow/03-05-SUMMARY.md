---
plan: 03-05
phase: 03-save-movie-flow
status: complete
completed: 2026-05-16
commits:
  - 6107b0a: feat(03-05): useMovies composable with searchTmdb, saveMovie, getStatus + tests
  - f527daa: feat(03-05): /add page, AppNav 'Add Film' link, poster grid with spinner/success/error states + tests
  - aafa78c: fix(03-05): remove invalid definePageMeta (auth is global), add required id prop to InputText
  - b2ecfcd: fix(03-05): UX fixes — search layout, dark icons, hover zoom, API key delete
self_check: PASSED
human_verified: approved
---

## What Was Built

Complete frontend save-movie experience: `useMovies` composable, `/add` page with TMDB search, poster grid, and inline spinner/success/error status UX. AppNav "Add Film" link. API key delete functionality in Settings.

## Key Files Created/Modified

- `frontend/composables/useMovies.ts` — searchTmdb, saveMovie, getStatus with authHeaders pattern
- `frontend/pages/add.vue` — search form, poster grid, polling state machine, onUnmounted cleanup
- `frontend/components/AppNav.vue` — "Add Film" NuxtLink added
- `frontend/public/placeholder-poster.svg` — fallback for missing posters
- `frontend/composables/useSettings.ts` — added deleteApiKey
- `frontend/pages/settings.vue` — Delete button per API key field
- `backend/.../SettingsController.java` — DELETE /api-keys/{provider} endpoint
- `backend/.../SettingsService.java` — deleteApiKey service method
- `frontend/test/unit/composables/useMovies.spec.ts` — 5 real tests (replaced .todo)
- `frontend/test/unit/pages/add.spec.ts` — 7 real tests (replaced .todo)

## Verification

- Frontend test suite: 15 files, 86 tests — all green
- Backend: compileJava clean
- Human UAT: approved — search, poster click, spinner, success state all verified
- UX fixes applied: proportional search bar, dark icons, poster hover zoom, key delete

## Deviations

- Added DELETE /api-keys/{provider} backend endpoint (not in original plan) — user needed ability to clear keys from Settings UI
- `definePageMeta({ middleware: 'auth' })` removed — auth is enforced globally via `auth.global.ts`
