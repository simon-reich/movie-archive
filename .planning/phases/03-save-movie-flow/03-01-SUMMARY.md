---
plan: 03-01
phase: 03-save-movie-flow
status: complete
completed: 2026-05-16
commits:
  - e0552a2: feat(03-01): backend test stubs and WireMock fixtures for phase 3
  - a6d5a14: feat(03-01): MSW movie handlers and frontend test stubs
self_check: PASSED
---

## What Was Built

Test scaffolding for Phase 3: all stub backend test classes, WireMock JSON fixtures, MSW handlers, and frontend test stubs. No production code — test contracts established for Wave 2.

## Key Files Created

### Backend Test Stubs
- `backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java` — @Disabled stubs for SAVE-01, SAVE-05
- `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java` — @Disabled stubs for SAVE-02, SAVE-04, SAVE-05
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` — @Disabled stubs for SAVE-03, SAVE-04
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` — @Disabled stubs for SAVE-04

### WireMock Fixtures
- `backend/src/test/resources/fixtures/tmdb/inception-search.json` — TMDB search response (Inception, id=27205)
- `backend/src/test/resources/fixtures/tmdb/inception-detail.json` — TMDB detail with credits, external_ids (imdb_id=tt1375666), videos
- `backend/src/test/resources/fixtures/omdb/inception.json` — OMDB response for tt1375666
- `backend/src/test/resources/fixtures/wikipedia/inception-plot.json` — Wikipedia action=parse intro
- `backend/src/test/resources/fixtures/wikipedia/inception-sections.json` — Wikipedia sections listing
- `backend/src/test/resources/fixtures/wikipedia/inception-plot-section.json` — Wikipedia Plot section (section 1)
- `backend/src/test/resources/fixtures/wikipedia/inception-critics-section.json` — Wikipedia Critical response section

### Frontend MSW + Test Stubs
- `frontend/test/mocks/handlers/movies.ts` — MSW handlers for GET /api/movies/search, POST /api/movies/save, GET /api/movies/:id/status
- `frontend/test/mocks/handlers.ts` — updated to spread moviesHandlers
- `frontend/test/unit/composables/useMovies.spec.ts` — 6 .todo stubs for useMovies composable
- `frontend/test/unit/pages/add.spec.ts` — 7 .todo stubs for /add page

## Verification

- Backend test suite: all @Disabled stubs pass (counted as skipped, not failed)
- Frontend test suite: 13 files, 74 tests passed — all .todo stubs are skipped

## Deviations

None. All files created per plan spec.
