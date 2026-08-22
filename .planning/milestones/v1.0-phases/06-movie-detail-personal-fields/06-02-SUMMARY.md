---
phase: "06"
plan: "02"
subsystem: backend
tags: [patch-endpoint, delete-endpoint, opensearch-sync, idor-protection, integration-tests]
dependency_graph:
  requires:
    - "06-01"  # MovieDetailService, MovieDetailController GET, Movie entity personal fields
  provides:
    - PATCH /movies/{id}/personal — partial personal field update with OS re-index
    - DELETE /movies/{id} — OS-first removal then Postgres delete
  affects:
    - IndexingService — new deleteDocument() method
    - MovieDetailService — updatePersonal() and deleteMovie() added
    - MovieDetailController — PATCH and DELETE endpoints added
tech_stack:
  added: []
  patterns:
    - Map<String, Object> PATCH body for null-vs-absent distinction (containsKey sentinel)
    - OS deleteDocument() with swallowed document-missing exception
    - Full re-index (IndexingService.index()) after personal update — avoids UpdateRequest API uncertainty
    - RateLimitService.resetAll() in @BeforeEach — established pattern for tests that call /auth/login
key_files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/indexing/IndexingService.java
    - backend/src/main/java/de/moviearchive/movie/MovieDetailService.java
    - backend/src/main/java/de/moviearchive/movie/MovieDetailController.java
    - backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java
decisions:
  - Map<String, Object> request body chosen over UpdatePersonalRequest DTO — eliminates null-vs-absent ambiguity cleanly via containsKey()
  - Full re-index via IndexingService.index() chosen over OS UpdateRequest — reuses proven code, avoids untested API
  - deleteDocument() swallows all exceptions — movie may never have been indexed (indexedAt=null)
  - OS delete happens before Postgres delete — ensures no orphaned OS documents
metrics:
  duration_minutes: 15
  completed_date: "2026-05-18"
  tasks_completed: 2
  tasks_total: 2
  files_changed: 4
---

# Phase 6 Plan 02: PATCH/DELETE Backend Endpoints Summary

PATCH /movies/{id}/personal and DELETE /movies/{id} with IDOR protection, OS sync via full re-index, and all 13 MovieDetailControllerTest integration tests green.

## What Was Built

### IndexingService — deleteDocument()

New method added to `IndexingService.java`. Uses `DeleteRequest` to remove the OS document for a given movieId from a given index. Swallows all exceptions (document-missing, index-not-found) with a warn log — ensures DELETE flow is never blocked by an unindexed movie.

### MovieDetailService — updatePersonal() and deleteMovie()

`updatePersonal(UUID userId, UUID movieId, Map<String, Object> fields)`:
- Loads movie via `findByIdAndUserId` (ownership check — 404 if wrong user)
- Uses `fields.containsKey("watched")` / `"personalRating"` / `"personalNotes"` to detect which fields were sent
- `personalRating: null` in JSON clears the rating (Short wrapper type set to null)
- Saves to Postgres, then re-indexes full document if `movie.getIndexedAt() != null`
- OS IOException on sync is warn-logged and swallowed — Postgres is source of truth

`deleteMovie(UUID userId, UUID movieId)`:
- Ownership check first (404 if wrong user)
- Calls `indexingService.deleteDocument()` before Postgres delete
- Calls `movieRepository.deleteById()` as final step

### MovieDetailController — PATCH and DELETE

Two new endpoints added after existing `GET /{id}`:
- `PATCH /{id}/personal` — `@RequestBody Map<String, Object> fields`, returns 204
- `DELETE /{id}` — returns 204

Both use the existing `resolveUserId(auth)` pattern (email → userId).

### MovieDetailControllerTest — 9 previously-@Disabled tests enabled

All 13 tests now run and pass:

PATCH tests:
- `updatePersonal_updatesWatchedInPostgres` — watched=true in Postgres
- `updatePersonal_updatesPersonalRatingInPostgres` — personalRating=8 in Postgres
- `updatePersonal_clearsPersonalRatingWhenNull` — personalRating null after null sent
- `updatePersonal_updatesPersonalNotesInPostgres` — notes string in Postgres
- `updatePersonal_syncsToOpenSearch` — watched=true reflected in OS document after PATCH
- `updatePersonal_returns404WhenMovieNotOwnedByUser` — IDOR protection

DELETE tests:
- `deleteMovie_removesFromPostgresAndOpenSearch` — row gone from Postgres, document not-found in OS
- `deleteMovie_returns404WhenMovieNotOwnedByUser` — IDOR protection, movie still in Postgres
- `deleteMovie_returns404WhenMovieDoesNotExist` — random UUID → 404

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] 429 Too Many Requests on login in 3 tests**
- **Found during:** Task 2 first test run
- **Issue:** Tests calling `/auth/login` multiple times within a single test class run triggered the Bucket4j rate limiter. 3 tests failed with `status expected:<200> but was:<429>`.
- **Fix:** Added `@Autowired RateLimitService rateLimitService` and `rateLimitService.resetAll()` in `@BeforeEach` — exact same pattern used by `MovieControllerTest`, `SearchControllerTest`, `DashboardControllerTest`, and `SettingsIntegrationTest`.
- **Files modified:** `MovieDetailControllerTest.java`
- **Commit:** 1bc295c (included in Task 2 commit)

## Threat Model Compliance

All mitigations from the plan's threat register are implemented:

| Threat ID | Mitigation | Implemented |
|-----------|-----------|-------------|
| T-06-02-01 | PATCH IDOR: `findByIdAndUserId` | `updatePersonal()` line 1 |
| T-06-02-02 | DELETE IDOR: ownership check before delete | `deleteMovie()` line 1 |
| T-06-02-03 | Mass assignment: only 3 keys read via containsKey | `updatePersonal()` — no other fields touched |

## Self-Check

### Files Exist
- IndexingService.java: modified — FOUND
- MovieDetailService.java: modified — FOUND
- MovieDetailController.java: modified — FOUND
- MovieDetailControllerTest.java: modified — FOUND

### Commits Exist
- 91f781c (Task 1: service methods + IndexingService)
- 1bc295c (Task 2: controller endpoints + integration tests)

## Self-Check: PASSED
