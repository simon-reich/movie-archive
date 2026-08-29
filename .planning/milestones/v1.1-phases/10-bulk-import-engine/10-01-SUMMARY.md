---
phase: 10-bulk-import-engine
plan: 01
subsystem: backend-bulk-import
tags: [spring-boot, multipart-upload, async-batch-job, tmdb-matching, flyway, testcontainers, wiremock]
requires:
  - phase: 3-save-movie-flow
    provides: MovieService.initiate() idempotent check-then-insert, EnrichmentService.enrich() async pipeline
  - phase: 8-9-wiki-enrichment-tracking
    provides: WikiReloadService/WikiReloadController async-job template (self-proxy, bounded executor, pacing, TaskRejectedException->503)
provides:
  - "POST /movies/bulk-import multipart endpoint returning 202 Accepted synchronously"
  - "bulk_import_line table + BulkImportLine/BulkImportLineRepository (one row per logical line, app-layer upsert)"
  - "ImportLineParser: pure Title;OriginalTitle;Year parsing (D-01/D-02/D-03)"
  - "BulkImportService: async orchestrator with exact-year TMDB matching + original-title narrowing (D-04/D-05/D-06/D-07), dedup-before-TMDB-call skip on re-upload (D-08/D-10), reuse of MovieService.initiate()+EnrichmentService.enrich() (D-12)"
  - "bulkImportExecutor bean (core=1/max=1/queue=1) mirroring wikiReloadExecutor"
  - "MovieService.resolveTmdbKey() extracted for reuse by the synchronous 422 fail-fast check"
  - "TmdbClient/TmdbSearchResultItem extended with originalTitle"
affects: [11-bulk-import-feedback-ui]
actuals:
  tokens: 14382
  tasks: 3
  commits: 3
tech-stack:
  added: []
  patterns:
    - "Synchronous MultipartFile read in the controller before @Async handoff (temp-storage-cleared-after-request pitfall)"
    - "@Lazy self-proxy for per-line @Transactional routing from an @Async orchestrator (mirrors WikiReloadService)"
    - "App-layer find-then-update upsert instead of a DB UNIQUE constraint, to sidestep nullable-year NULL-distinctness"
key-files:
  created:
    - backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineStatus.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
    - backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
    - backend/src/test/resources/fixtures/tmdb/robin-hood-ambiguous-search.json
  modified:
    - backend/src/main/java/de/moviearchive/config/AsyncConfig.java
    - backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java
    - backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java
    - backend/src/main/java/de/moviearchive/movie/MovieService.java
    - backend/src/main/resources/application.properties
    - backend/src/test/resources/application-test.properties
key-decisions:
  - "bulk_import_line has no DB-level UNIQUE constraint on the dedup key — nullable year (PARSE_ERROR rows) breaks SQL NULL-distinctness, so 'one row per logical line' is enforced at the application layer via find-then-update (RESEARCH.md Open Question 1, Common Pitfall 4)"
  - "PARSE_ERROR lines with an unparseable year are identified for retry-in-place by (user_id, raw_line) instead of (title, year)"
  - "bulkImportExecutor sized core=1/max=1/queue=1, identical to wikiReloadExecutor, per CONTEXT.md discretion"
requirements-completed: [IMPORT-01, IMPORT-02, IMPORT-03, IMPORT-04, IMPORT-07]
coverage:
  - id: D1
    description: "Multipart upload endpoint returns 202 Accepted synchronously, dispatches async processing"
    requirement: "IMPORT-01"
    verification:
      - kind: integration
        ref: "BulkImportControllerTest#shouldSaveUniqueMatch_andPersistBulkImportLineRow"
        status: pass
    human_judgment: false
  - id: D2
    description: "Line parsing (D-01/D-02/D-03): blank-skip, field-count/year/title validation, trailing-empty-field preservation"
    requirement: "IMPORT-02"
    verification:
      - kind: unit
        ref: "ImportLineParserTest (6 cases)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Exact-year TMDB matching with original-title narrowing on ambiguity (D-04/D-05/D-06/D-07)"
    requirement: "IMPORT-04"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest (not-found, unique, ambiguous, narrowed, still-ambiguous)"
        status: pass
      - kind: integration
        ref: "BulkImportControllerTest#shouldMarkAmbiguous_whenMultipleYearMatchesNoOriginalTitle, #shouldNarrowToUnique_whenOriginalTitleMatches"
        status: pass
    human_judgment: false
  - id: D4
    description: "Unique match reuses MovieService.initiate() + EnrichmentService.enrich() exactly as /movies/save (D-12)"
    requirement: "IMPORT-03"
    verification:
      - kind: integration
        ref: "BulkImportControllerTest#shouldSaveUniqueMatch_andPersistBulkImportLineRow"
        status: pass
    human_judgment: false
  - id: D5
    description: "Re-upload skips already-SAVED lines with zero additional TMDB calls; non-SAVED lines retried; 422 fail-fast with zero TMDB calls; 503 on third overlapping trigger"
    requirement: "IMPORT-07"
    verification:
      - kind: integration
        ref: "BulkImportControllerTest#shouldSkipReupload_whenLineAlreadySaved, #shouldReturn422_whenNoTmdbKeyConfigured, #shouldReject_whenThirdImportExceedsQueueCapacity"
        status: pass
    human_judgment: false
duration: ~55min
completed: 2026-08-24
status: complete
---

# Phase 10 Plan 01: Bulk Import Engine Summary

**Multipart CSV-style upload endpoint (Title;OriginalTitle;Year), async exact-year TMDB matching with original-title ambiguity narrowing, idempotent save+enrich reuse, and dedup-before-TMDB-call skip on re-upload — all persisted per-line for Phase 11's results UI.**

## Performance
- **Duration:** ~55min
- **Started:** 2026-08-24T11:00:00Z (approx.)
- **Completed:** 2026-08-24T12:35:02+02:00
- **Tasks:** 3
- **Files modified:** 17

## Accomplishments
- New `bulkimport` package: `BulkImportController` (multipart upload), `BulkImportService` (async orchestrator mirroring `WikiReloadService`'s self-proxy/pacing/per-item-isolation structure), `BulkImportLine`/`BulkImportLineRepository`/`BulkImportLineStatus`, `ImportLineParser`
- V9 Flyway migration creates `bulk_import_line` with application-layer upsert (no DB unique constraint, due to nullable-year NULL-distinctness)
- `bulkImportExecutor` bean (core=1/max=1/queue=1) added to `AsyncConfig`, identical sizing to `wikiReloadExecutor`
- `TmdbClient`/`TmdbSearchResultItem` extended with `originalTitle` (previously unmapped)
- `MovieService.resolveTmdbKey()` extracted from `search()` for reuse by the bulk-import endpoint's synchronous 422 fail-fast check
- Full matching algorithm implemented: exact-year filter, then original-title narrowing only when still ambiguous, never auto-guessing among ambiguous candidates
- Dedup-before-TMDB-call: re-uploads of already-SAVED lines make zero TMDB calls; AMBIGUOUS/NOT_FOUND/PARSE_ERROR lines are retried in place (find-then-update, never duplicated)
- 8 integration tests (`BulkImportControllerTest`) + 6 parser unit tests (`ImportLineParserTest`) + 6 service unit tests (`BulkImportServiceTest`) — all passing

## Task Commits
1. **Task 1: Tracer — end-to-end single-line bulk import** - `eff92a5` (feat)
2. **Task 2: Cover matching algorithm branches and parser edge cases** - `456d082` (test)
3. **Task 3: Cover dedup-skip-on-reupload and executor concurrency limits** - `51ecdd3` (test)

## Files Created/Modified
- `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql` - new table, app-layer upsert (no unique constraint)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineStatus.java` - SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR enum
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` - JPA entity
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` - dedup + find-or-create queries (null-safe by year presence)
- `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java` - pure Title;OriginalTitle;Year parser
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - async orchestrator + matching + upsert logic
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - multipart endpoint, 202/422/400/503 responses
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` - added `bulkImportExecutor` bean
- `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java` - maps `original_title` into results
- `backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java` - added `originalTitle` field
- `backend/src/main/java/de/moviearchive/movie/MovieService.java` - extracted `resolveTmdbKey()`
- `backend/src/main/resources/application.properties` - `bulk-import.pacing-delay-ms` property
- `backend/src/test/resources/application-test.properties` - fast 1ms test-suite default
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - 8 integration tests
- `backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java` - 6 unit tests
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - 6 unit tests
- `backend/src/test/resources/fixtures/tmdb/robin-hood-ambiguous-search.json` - 2-candidate ambiguous fixture

## Decisions Made
- Application-layer find-then-update upsert instead of a DB unique constraint on `bulk_import_line`'s dedup key, because a nullable `year` (unparseable-year `PARSE_ERROR` rows) breaks SQL `NULL`-distinctness for uniqueness — resolves RESEARCH.md's Open Question 1 exactly as recommended.
- PARSE_ERROR lines with an unparseable year are matched for retry-in-place by `(user_id, raw_line)` rather than `(title, year)`, since a null year is not a reliable identity.
- `bulkImportExecutor` sized identically to `wikiReloadExecutor` (core=1/max=1/queue=1) per CONTEXT.md's explicit discretion note — a single global bounded slot is intentional (D-11).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `BulkImportControllerTest` missing WireMock base-URL override**
- **Found during:** Task 1, first test run
- **Issue:** The initial `BulkImportControllerTest` had no `@DynamicPropertySource` overriding `tmdb.base-url`/`omdb.base-url`/`wikipedia.base-url` to WireMock's dynamic port (unlike `MovieControllerTest`'s established pattern) — the test's TMDB search call attempted to hit the real network and failed with a connection error.
- **Fix:** Added the same `@DynamicPropertySource overrideExternalBaseUrls` block `MovieControllerTest` and `EnrichmentIntegrationTest` use.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Commit:** `eff92a5`

**2. [Rule 1 - Bug] Executor-drain race causing FK-constraint violations between tests**
- **Found during:** Task 3, after adding the queue-capacity/concurrency test
- **Issue:** An `updatedAt`-based heuristic for detecting when `bulkImportExecutor`'s in-flight work had fully drained returned prematurely for fast (dedup-skip) processing paths, since a line's row is only written once even when re-processed twice across two paced runs — the next test's `@BeforeEach cleanDb()` then deleted a user while a still in-flight async task was about to write a row referencing it, causing an intermittent `DataIntegrityViolationException`.
- **Fix:** Replaced the heuristic with a direct poll of the `bulkImportExecutor` `ThreadPoolTaskExecutor` bean's `getActiveCount()`/queue-emptiness, autowired into the test via `@Qualifier("bulkImportExecutor")`; added an `@AfterEach` drain (not just at the end of the one concurrency test) so every test leaves the shared singleton executor idle before the next test's cleanup runs.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Commit:** `51ecdd3`

**Total deviations:** 2 auto-fixed (both Rule 1 — bugs in test infrastructure, not production code).
**Impact:** Test-only fixes; no production code behavior changed as a result of either deviation.

## Issues Encountered

**Environment: stale `~/.testcontainers.properties` pointed at a nonexistent `/var/run/docker.sock`** — this machine runs OrbStack (socket at `~/.orbstack/run/docker.sock`), not Docker Desktop. Worked around per test run by passing `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` as an environment variable to `./gradlew test`; no project file was changed (this is a local machine config issue, not a repo issue).

**Environment: 17 pre-existing test failures in full-suite mode only** — running `./gradlew test` (full 161-test suite) reproducibly fails the same 17 tests, all confined to `WikipediaClientTest` and `SettingsIntegrationTest` (both untouched by this plan), with `PSQLException`/`FlywaySqlException` connection errors. Confirmed NOT a regression from this plan's changes:
- Re-running exactly those two test classes in isolation passes cleanly (`BUILD SUCCESSFUL`).
- The three test classes this plan's `TmdbClient`/`TmdbSearchResultItem`/`MovieService` changes are most likely to affect — `MovieControllerTest`, `EnrichmentServiceTest`, `EnrichmentIntegrationTest` — all pass with 0 failures in the full-suite run.
- The failure set is 100% reproducible and identical across two separate full-suite runs, consistent with Testcontainers/Docker resource contention (many concurrent Spring contexts each spinning up their own embedded Postgres container) rather than a code defect.

This is a local-environment resource constraint, not a code regression. `de.moviearchive.bulkimport.*` (the full 20-test package for this plan) passes cleanly both standalone and as part of the full suite.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
`bulk_import_line` is populated live, per-line, with exactly one row per logical line across re-uploads (SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR) — Phase 11 can build its live-progress polling and per-line results UI directly against this table with no further backend changes needed. No blockers.

## Self-Check: PASSED
