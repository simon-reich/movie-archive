---
phase: 03-save-movie-flow
plan: "03"
subsystem: backend-movie-http-layer
tags: [spring-boot, rest-api, movie-service, movie-controller, enrichment-stub, tdd]
dependency_graph:
  requires: [03-02]
  provides: [POST /movies/save, GET /movies/search, GET /movies/{id}/status, EnrichmentService stub]
  affects: [03-04, 03-05]
tech_stack:
  added: []
  patterns:
    - MovieService check-then-insert for idempotent duplicate save (avoids JPA flush-time DataIntegrityViolationException)
    - EnrichmentService @Async stub in enrichment package — filled in by Plan 03-04
    - WireMock import qualification to avoid clash with MockMvcRequestBuilders.get()
key_files:
  created:
    - backend/src/main/java/de/moviearchive/movie/MovieService.java
    - backend/src/main/java/de/moviearchive/movie/MovieController.java
    - backend/src/main/java/de/moviearchive/movie/NoTmdbKeyException.java
    - backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java
    - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
  modified:
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java
decisions:
  - Check-then-insert for idempotent duplicate save instead of DataIntegrityViolationException catch — JPA flushes at transaction commit, making the catch unreachable within the @Transactional method
  - TmdbClient stub in enrichment package returns empty List — Plan 03-04 replaces with real WebClient call
  - EnrichmentService stub with @Async("enrichmentExecutor") — Plan 03-04 fills in TMDB->OMDB->Wikipedia->Postgres pipeline
  - WireMock.get() must be fully qualified in tests alongside MockMvcRequestBuilders.get() static import to avoid ambiguity
metrics:
  duration: ~25 min
  completed: 2026-05-17
  tasks_completed: 2
  files_created: 5
  files_modified: 2
---

# Phase 03 Plan 03: MovieService HTTP Layer Summary

MovieController and MovieService delivering three working endpoints — POST /movies/save (202), GET /movies/search (200/422), GET /movies/{id}/status (200/403) — with full integration test coverage and stub wiring for Plan 03-04 enrichment.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | MovieService and TmdbClient stub | 234a1ad | MovieService.java, TmdbClient.java, NoTmdbKeyException.java, MovieRepository.java |
| 2 | MovieController and integration tests | fd5f7a3 | MovieController.java, EnrichmentService.java, MovieControllerTest.java |

## What Was Built

**MovieService** (`de.moviearchive.movie.MovieService`):
- `initiate(email, tmdbId)`: Finds or creates Movie with status=PENDING; idempotent via check-then-insert using `findByUserIdAndTmdbId`
- `getStatus(movieId, userId)`: Ownership-scoped query via `findByIdAndUserId`; throws `AccessDeniedException` if not found
- `getStatusByEmail(email, movieId)`: Convenience wrapper resolving email → userId for controller use
- `search(email, query)`: Gets decrypted TMDB key from SettingsService; throws `NoTmdbKeyException` if null; delegates to TmdbClient stub

**MovieController** (`de.moviearchive.movie.MovieController`):
- `POST /movies/save`: Calls `movieService.initiate()` + `enrichmentService.enrich()` async; returns 202 with `{ "id": uuid }`
- `GET /movies/search?q=`: Returns TMDB search results; 422 when no key configured
- `GET /movies/{id}/status`: Returns `{ id, status, title }`; 403 for cross-user access
- Exception handlers: `NoTmdbKeyException` → 422, `AccessDeniedException` → 403, `MethodArgumentNotValidException` → 400

**TmdbClient stub** (`de.moviearchive.enrichment.TmdbClient`):
- `search()` returns empty List for now; `@Retryable` annotations in place; Plan 03-04 fills in real WebClient call

**EnrichmentService stub** (`de.moviearchive.enrichment.EnrichmentService`):
- `@Async("enrichmentExecutor")` method with log statement; Plan 03-04 implements TMDB→OMDB→Wikipedia→Postgres pipeline

**MovieControllerTest** — 6 tests, all passing:
- `shouldReturn202_whenSaveInitiated`
- `shouldReturn202_withSameUuid_onDuplicateSave`
- `shouldReturnSearchResults_whenTmdbKeyValid`
- `shouldReturn422_whenNoTmdbKey`
- `shouldReturnPendingStatus_immediately`
- `shouldReturn403_whenAccessingOtherUsersStatus`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed JPA DataIntegrityViolationException catch unreachable in @Transactional**
- **Found during:** Task 1 → Task 2 test execution
- **Issue:** Plan specified catching `DataIntegrityViolationException` within the `@Transactional` `initiate()` method to handle duplicate saves. JPA flushes at transaction commit, so the exception is thrown after the `try/catch` block exits — catch is never reached.
- **Fix:** Replaced with check-then-insert pattern using `movieRepository.findByUserIdAndTmdbId(userId, tmdbId)`. This is also more predictable under concurrent load (though for single-user v1 it is sufficient).
- **Files modified:** `MovieService.java`, `MovieRepository.java` (added `findByUserIdAndTmdbId`)
- **Commit:** 234a1ad, fd5f7a3

**2. [Rule 3 - Blocking] Fixed WireMock static import clash with MockMvcRequestBuilders**
- **Found during:** Task 2 test compilation
- **Issue:** `import static com.github.tomakehurst.wiremock.client.WireMock.*` imports `get(UrlPattern)` which clashes with `MockMvcRequestBuilders.get(String)` — compiler error: "method not applicable".
- **Fix:** Replaced wildcard WireMock import with specific imports (`aResponse`, `urlPathMatching`) and used fully qualified `WireMock.get()` in the WireMock stub call.
- **Files modified:** `MovieControllerTest.java`
- **Commit:** fd5f7a3

## Known Stubs

| Stub | File | Reason |
|------|------|--------|
| `TmdbClient.search()` returns `List.of()` | `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java:28` | Plan 03-04 implements real WebClient call; search test verifies HTTP 200 but not result content |
| `EnrichmentService.enrich()` is a no-op log | `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:18` | Plan 03-04 implements full TMDB→OMDB→Wikipedia→Postgres pipeline |

These stubs are intentional per plan design — Plan 03-04 replaces them with real implementations.

## Threat Model Verification

All mitigations from the plan's threat register were implemented:

| Threat ID | Mitigation | Verified |
|-----------|------------|---------|
| T-03-03-01 | `findByIdAndUserId` — ownership-scoped query returning 403 | Yes — `shouldReturn403_whenAccessingOtherUsersStatus` passes |
| T-03-03-02 | `@Valid @Positive` on `SaveMovieRequest.tmdbId` | Yes — `@Positive` constraint in DTO, `@Valid` in controller |
| T-03-03-03 | Idempotent duplicate save via check-then-insert | Yes — `shouldReturn202_withSameUuid_onDuplicateSave` passes |
| T-03-03-04 | TMDB key not logged at INFO level | Yes — key value only accessed internally, never logged |

## Self-Check

**Files created:**
- backend/src/main/java/de/moviearchive/movie/MovieService.java — FOUND
- backend/src/main/java/de/moviearchive/movie/MovieController.java — FOUND
- backend/src/main/java/de/moviearchive/movie/NoTmdbKeyException.java — FOUND
- backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java — FOUND
- backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java — FOUND

**Commits:**
- 234a1ad — feat(03-03): MovieService, TmdbClient stub, NoTmdbKeyException — FOUND
- fd5f7a3 — feat(03-03): MovieController, EnrichmentService stub, MovieControllerTest — FOUND

**Tests:** 66 tests, 0 failures, 8 skipped (pre-existing stubs from Plan 03-01) — PASSED

## Self-Check: PASSED
