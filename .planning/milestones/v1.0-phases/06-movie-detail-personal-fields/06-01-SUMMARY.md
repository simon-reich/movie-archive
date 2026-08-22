---
phase: "06"
plan: "01"
subsystem: backend
tags: [flyway, jpa, rest-api, movie-detail, personal-fields, opensearch]
dependency_graph:
  requires:
    - "06-00"  # wave-0 test stubs (MovieDetailControllerTest skeleton)
  provides:
    - GET /movies/{id} endpoint
    - MovieDetailResponse DTO (35+ fields)
    - V7 Flyway migration (watched, personal_rating, personal_notes)
    - DocumentBuilder real personal field values
  affects:
    - SearchControllerTest (watched filter assertion updated for Phase 6)
    - "06-02"  # PATCH /movies/{id}/personal and DELETE /movies/{id} unblocked
tech_stack:
  added: []
  patterns:
    - resolveUserId via userRepository.findByEmail(auth.getName()) — same as SearchController
    - JsonNode accessed directly from Movie entity (JdbcTypeCode, not String parse)
    - ResponseStatusException(HttpStatus.NOT_FOUND) for cross-user access (IDOR protection)
key_files:
  created:
    - backend/src/main/resources/db/migration/V7__add_personal_fields_to_movies.sql
    - backend/src/main/java/de/moviearchive/movie/dto/CastMember.java
    - backend/src/main/java/de/moviearchive/movie/dto/CrewMember.java
    - backend/src/main/java/de/moviearchive/movie/dto/Rating.java
    - backend/src/main/java/de/moviearchive/movie/dto/MovieDetailResponse.java
    - backend/src/main/java/de/moviearchive/movie/MovieDetailService.java
    - backend/src/main/java/de/moviearchive/movie/MovieDetailController.java
  modified:
    - backend/src/main/java/de/moviearchive/movie/Movie.java
    - backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java
    - backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java
    - backend/src/test/java/de/moviearchive/search/SearchControllerTest.java
decisions:
  - "Read raw JSON from Movie entity as JsonNode directly (JdbcTypeCode) — no ObjectMapper.readTree() needed in service layer"
  - "MovieDetailController is a separate bean from MovieController — both map /movies, Spring resolves GET /movies/{id}/status vs GET /movies/{id} correctly"
  - "doubleOrNull and intOrNull return null for zero values (vote_average=0, vote_count=0 treated as absent)"
metrics:
  duration: ~15 min
  completed: "2026-05-18"
  tasks_completed: 2
  files_changed: 10
requirements:
  - DETAIL-01
  - DETAIL-02
---

# Phase 06 Plan 01: Backend Foundation — V7 Migration + GET /movies/{id} Summary

Backend foundation for the movie detail page: Flyway V7 migration adds personal columns to the movies table, Movie entity gets the 3 new fields, DocumentBuilder is fixed to index real values instead of null, and a new GET /movies/{id} endpoint returns all 35+ TMDB + OMDB + Wikipedia + personal fields with IDOR protection.

## What Was Built

### Task 1: V7 Migration, Movie Entity, DocumentBuilder Fix

- **V7__add_personal_fields_to_movies.sql** — Adds `watched BOOLEAN NOT NULL DEFAULT FALSE`, `personal_rating SMALLINT`, `personal_notes TEXT` to the movies table. Verified via Flyway in integration test run (7 migrations applied).
- **Movie.java** — Three new fields with correct JPA annotations: `Boolean watched = false`, `Short personalRating`, `String personalNotes` (uses `columnDefinition = "text"`).
- **DocumentBuilder.java** — Replaced the 3 hardcoded `doc.put("...", null)` lines with real entity reads: `movie.getWatched()`, `movie.getPersonalRating().doubleValue()` (OS field is float), `movie.getPersonalNotes()`.
- **MovieRepository.java** — `findByIdAndUserId` already existed; no change needed.

### Task 2: MovieDetailResponse DTO, MovieDetailService, MovieDetailController

- **CastMember.java / CrewMember.java / Rating.java** — Minimal record DTOs.
- **MovieDetailResponse.java** — 35-field record covering all TMDB scalars, extracted lists (genres, directors, writers, cast, crew, countries, languages), OMDB-sourced fields (nullable when raw_omdb_json is null), Wikipedia fields, and personal fields.
- **MovieDetailService.java** — `getDetail(userId, movieId)` uses `findByIdAndUserId` for IDOR protection, reads `JsonNode` directly from entity (no re-parsing), extracts all fields with null-safe helpers. OMDB fields return null when the entity's rawOmdbJson is null.
- **MovieDetailController.java** — `GET /movies/{id}` with `resolveUserId` pattern identical to SearchController. Separate controller bean from MovieController; Spring resolves `/{id}/status` vs `/{id}` correctly.
- **MovieDetailControllerTest.java** — 4 GET tests enabled: `returnsAllFields`, `omdbFieldsNullWhenNoOmdbData`, `returns404WhenMovieNotOwnedByUser`, `returns404WhenMovieDoesNotExist`. 9 PATCH/DELETE tests remain `@Disabled` for plan 06-02.

## Commits

| Hash | Message |
|------|---------|
| `69a7943` | feat(06-01): V7 migration, Movie entity personal fields, DocumentBuilder fix |
| `fdea106` | feat(06-01): MovieDetailResponse DTO, MovieDetailService, MovieDetailController GET /movies/{id} |
| `5aaeb22` | fix(06-01): update SearchControllerTest watched filter assertion for Phase 6 |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] SearchControllerTest watched filter assertion broke after DocumentBuilder fix**

- **Found during:** Full test suite run after Task 2
- **Issue:** `shouldReturnEmpty_whenWatchedFilterApplied` expected `total=0` because Phase 4/5 indexed `watched=null` (hardcoded in DocumentBuilder). After the Phase 6 fix, movies are indexed with `watched=false`. OpenSearch `term(watched=false)` now correctly matches newly saved movies — the test's expected total changed from 0 to 1.
- **Fix:** Updated assertion to `value(1)` and updated the comment from Phase 4/5 rationale to Phase 6 rationale.
- **Files modified:** `backend/src/test/java/de/moviearchive/search/SearchControllerTest.java`
- **Commit:** `5aaeb22`

## Test Results

Full backend suite: **121 tests, 0 failures, 9 skipped** (BUILD SUCCESSFUL)

MovieDetailControllerTest breakdown:
- 4 GET tests: PASSED
- 9 PATCH/DELETE tests: SKIPPED (`@Disabled` — plan 06-02)

## Known Stubs

None. All GET endpoint fields are wired from real entity data. PATCH/DELETE methods are absent (not stubbed) — they are implemented in plan 06-02.

## Threat Flags

No new threat surface beyond what is documented in the plan's threat model. The IDOR mitigation (T-06-01-01) is implemented via `findByIdAndUserId(movieId, userId)` and verified by the cross-user 404 integration test.

## Self-Check: PASSED
