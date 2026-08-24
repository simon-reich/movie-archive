---
phase: 11-bulk-import-feedback-ui
plan: 01
subsystem: api
tags: [spring-boot, jpa, flyway, postgresql, bulk-import]

# Dependency graph
requires:
  - phase: 10-bulk-import-engine
    provides: BulkImportLine/BulkImportService/BulkImportController (upload → parse → match → save pipeline)
provides:
  - "bulk_import_batch table + BulkImportBatch entity/repository (durable batch identity per upload)"
  - "batch_id FK on bulk_import_line, threaded through the entire async pipeline"
  - "poster_path column on bulk_import_line, captured at save time with zero extra TMDB calls"
  - "POST /movies/bulk-import 202 response now returns batchId"
affects: [11-02-live-progress-sse, 11-03-batch-list-detail-endpoints, 11-04-frontend-results-page, 11-05-frontend-batch-list]

# Actuals (#2632)
actuals:
  tokens: 5570
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Batch created synchronously in the controller (before 202) so total_lines is captured from rawLines.size() and never undercounted by later row-count queries"
    - "Lazy JPA reference (getReferenceById) resolved once per line inside processLine() to thread the batch into every upsertLine()/saveAndUpsert() call, avoiding an extra query per line"

key-files:
  created:
    - backend/src/main/resources/db/migration/V10__create_bulk_import_batch.sql
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatch.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatchRepository.java
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java

key-decisions:
  - "Dedicated bulk_import_batch table (not a bare column) per RESEARCH.md — total_lines is captured synchronously from rawLines.size() before the async job starts, avoiding undercounting from blank lines that are never persisted as rows"
  - "batch_id is nullable; pre-migration rows are excluded from future batch-list/detail views rather than backfilled — no separate backfill migration needed"

patterns-established:
  - "Batch identity created synchronously in the controller, threaded via UUID batchId through @Async runImport()/processLine(), resolved to a lazy JPA reference once per line"

requirements-completed: [IMPORT-06]

coverage:
  - id: D1
    description: "Every bulk-import upload creates a durable batch record (bulk_import_batch) capturing total_lines, and every persisted bulk_import_line row is tagged with its batch_id"
    requirement: IMPORT-06
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldSaveUniqueMatch_andPersistBulkImportLineRow"
        status: pass
    human_judgment: false
  - id: D2
    description: "A SAVED bulk-import line's poster_path is captured at save time from the already-fetched TMDB match, with zero additional TMDB calls"
    requirement: IMPORT-06
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java#shouldSave_whenExactlyOneYearMatchingCandidate"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldSaveUniqueMatch_andPersistBulkImportLineRow"
        status: pass
    human_judgment: false
  - id: D3
    description: "POST /movies/bulk-import's 202 response includes a non-blank batchId matching the persisted lines' batch_id"
    requirement: IMPORT-06
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldSaveUniqueMatch_andPersistBulkImportLineRow"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-08-24
status: complete
---

# Phase 11 Plan 1: Backend Batch Persistence + Poster Capture Summary

**New `bulk_import_batch` table + `batch_id`/`poster_path` columns on `bulk_import_line`, with `batchId` threaded end-to-end through the async import pipeline and returned in the upload response, giving Phase 11's later plans (SSE progress, batch list/detail endpoints, frontend results UI) real persisted data to read.**

## Performance

- **Duration:** ~20 min
- **Tasks:** 2
- **Files modified:** 8 (3 created, 5 modified)

## Accomplishments
- `bulk_import_batch` table (id, user_id, total_lines, created_at) via Flyway `V10__create_bulk_import_batch.sql`, following V9's exact style (UUID PK, FK to users, indexed by user_id + created_at DESC)
- `BulkImportBatch` JPA entity + `BulkImportBatchRepository`
- `bulk_import_line` gains a nullable `batch_id` FK and a `poster_path` column, added in the same migration
- `BulkImportService.createBatch()` creates the batch record synchronously in the controller (before the 202 response), capturing `total_lines` from `rawLines.size()` — never undercounted by blank-line skipping later
- `runImport()`/`processLine()`/`saveAndUpsert()`/`upsertLine()` all thread `batchId`/`BulkImportBatch batch` through every branch (PARSE_ERROR, both NOT_FOUND paths, AMBIGUOUS, and both SAVED paths)
- `POST /movies/bulk-import`'s 202 response body now includes `batchId`
- `saveAndUpsert()` now accepts the full `TmdbSearchResultItem match` (not just `tmdbId`) so `posterPath` is captured with zero additional TMDB calls
- Every SAVED line's `poster_path` is persisted from the TMDB match already fetched during search/matching; all non-SAVED lines have `poster_path = null`

## Task Commits

Each task was committed atomically:

1. **Task 1 (tracer): Batch schema + end-to-end batchId threading (D-02)** - `1da7595` (feat)
2. **Task 2: Poster capture at save time (D-04) + test updates** - `eb841a6` (feat)

_Note: no separate plan-metadata commit — worktree execution mode excludes STATE.md/ROADMAP.md; this SUMMARY.md commit follows._

## Files Created/Modified
- `backend/src/main/resources/db/migration/V10__create_bulk_import_batch.sql` - new `bulk_import_batch` table + `batch_id`/`poster_path` columns on `bulk_import_line`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatch.java` - new JPA entity (id, user, totalLines, createdAt)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatchRepository.java` - new `JpaRepository<BulkImportBatch, UUID>`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` - added `batch` (`@ManyToOne`) and `posterPath` (`@Column`) fields
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - `createBatch()`, batchId-threaded `runImport()`/`processLine()`, posterPath-capturing `saveAndUpsert()`/`upsertLine()`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - creates the batch synchronously, logs `batchId`, returns it in the 202 response
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - added `BulkImportBatchRepository` mock, updated constructor/`processLine()` call sites, added `posterPath` assertion
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - added `BulkImportBatchRepository` autowire + `deleteAll()` in `cleanDb()`, asserted `batchId`/`getBatch()`/`getPosterPath()` on the save-flow test

## Decisions Made
- Followed RESEARCH.md's recommendation of a dedicated `bulk_import_batch` table over a bare `import_batch_id` column, since `total_lines` must be captured synchronously (blank lines are parsed-and-skipped, never persisted as rows, so a row-count query would always undercount)
- `batch_id` is nullable and pre-migration rows are simply excluded from future batch-list/detail views — no backfill migration, per RESEARCH.md's Assumption A2

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed test cleanup FK violation introduced by the new schema**
- **Found during:** Task 1 (Batch schema + end-to-end batchId threading)
- **Issue:** `BulkImportControllerTest.cleanDb()` deleted `users` after `bulk_import_line` but before the newly-added `bulk_import_batch` table (which also FKs to `users`), causing `DataIntegrityViolationException: update or delete on table "users" violates foreign key constraint "bulk_import_batch_user_id_fkey"` in all 7 controller tests.
- **Fix:** Added `BulkImportBatchRepository` autowire and `bulkImportBatchRepository.deleteAll()` in `cleanDb()`, positioned after `bulkImportLineRepository.deleteAll()` and before `movieRepository.deleteAll()`.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.bulkimport.*"` — all 7 previously-failing tests now pass.
- **Committed in:** `1da7595` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Necessary to keep the existing test suite green after the new FK relationship was introduced. No scope creep.

## Issues Encountered
None beyond the deviation above.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- `batchId` is now a stable, durable identifier threaded through the entire async pipeline and returned from the upload endpoint — Plan 11-02's SSE progress endpoint can key its emitter registry off it directly.
- `bulk_import_batch.total_lines` and `bulk_import_line.batch_id`/`poster_path` are real persisted columns — Plan 11-03's batch list/detail GET endpoints have real data to query, no stub/mock needed.
- No blockers.

---
*Phase: 11-bulk-import-feedback-ui*
*Completed: 2026-08-24*
