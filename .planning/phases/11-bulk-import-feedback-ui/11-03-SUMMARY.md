---
phase: 11-bulk-import-feedback-ui
plan: 03
subsystem: api
tags: [spring-boot, jpa, bulk-import, rest]

# Dependency graph
requires:
  - phase: 11-01
    provides: "bulk_import_batch table + BulkImportBatch entity/repository, batchId/posterPath threaded through the async pipeline"
  - phase: 11-02
    provides: "BulkImportController.loadOwnedBatch() ownership-check helper, reused unchanged by getBatchDetail()"
provides:
  - "GET /movies/bulk-import/batches — the authenticated user's past batches, newest-first, with per-status counts"
  - "GET /movies/bulk-import/batches/{batchId} — full per-line title/originalTitle/year/status/posterPath, ownership-checked"
  - "BulkImportBatchSummary/BulkImportBatchDetail/BulkImportLineResult DTOs"
  - "BulkImportBatchRepository.findByUserIdOrderByCreatedAtDesc, BulkImportLineRepository.findByBatchIdOrderByTitle/countByBatchIdGroupByStatus"
affects: [11-04-frontend-results-page, 11-05-frontend-batch-list]

# Actuals (#2632)
actuals:
  tokens: 5337
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Object[] rows from a @Query GROUP BY converted to Map<String, Long> in the controller — no interface-projection convention introduced (RESEARCH.md's stated non-goal)"
    - "Both new {batchId}-path-variable endpoints route through the existing loadOwnedBatch() helper (Plan 11-02), keeping the IDOR mitigation pattern in one place"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportBatchSummary.java
    - backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportBatchDetail.java
    - backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatchRepository.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java

key-decisions:
  - "countByBatchIdGroupByStatus returns plain List<Object[]> (element 0 = BulkImportLineStatus, element 1 = Long) per the plan's explicit instruction not to introduce an interface-projection convention that doesn't already exist in this codebase"
  - "No pagination on GET /movies/bulk-import/batches — plain List ordered by created_at DESC, per RESEARCH.md Open Question 2 (single-user-first scope)"

patterns-established:
  - "Read-only batch report endpoints (D-05 boundary) — no mutation/pick-a-candidate action added, matching the plan's explicit non-goal"

requirements-completed: [IMPORT-06]

coverage:
  - id: D1
    description: "GET /movies/bulk-import/batches returns the authenticated user's batches newest-first, each with per-status counts summing to the batch's persisted line count"
    requirement: IMPORT-06
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldListBatches_newestFirst_withStatusCounts"
        status: pass
    human_judgment: false
  - id: D2
    description: "GET /movies/bulk-import/batches/{batchId} returns per-line title/originalTitle/year/status/posterPath for the owner, with posterPath null (not fabricated) for AMBIGUOUS/NOT_FOUND lines"
    requirement: IMPORT-06
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldGetBatchDetail_forOwner_withLinesAndPosterPath"
        status: pass
    human_judgment: false
  - id: D3
    description: "GET /movies/bulk-import/batches/{batchId} returns 403 for a batch owned by a different user and 404 for an unknown batchId (IDOR protection)"
    requirement: IMPORT-06
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn403_whenDifferentUserRequestsBatchDetail"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn404_whenBatchDetailNotFound"
        status: pass
    human_judgment: false

duration: ~15min
completed: 2026-08-24
status: complete
---

# Phase 11 Plan 3: Batch List + Detail GET Endpoints Summary

**Two ownership-checked read-only GET endpoints — `GET /movies/bulk-import/batches` (newest-first list with per-status counts) and `GET /movies/bulk-import/batches/{batchId}` (per-line title/poster/status detail) — backed by two new repository queries and three response DTO records, giving Phase 11's frontend plans (11-04/11-05) real persisted data to render instead of a stub.**

## Performance

- **Duration:** ~15 min
- **Tasks:** 2
- **Files modified:** 7 (3 created, 4 modified)

## Accomplishments
- `BulkImportBatchRepository.findByUserIdOrderByCreatedAtDesc(UUID)` — derived query, batch list newest-first
- `BulkImportLineRepository.findByBatchIdOrderByTitle(UUID)` — derived query, batch detail lines
- `BulkImportLineRepository.countByBatchIdGroupByStatus(UUID)` — `@Query` GROUP BY, plain `Object[]` rows
- Three new DTO records: `BulkImportBatchSummary`, `BulkImportBatchDetail`, `BulkImportLineResult` in a new `bulkimport/dto/` package
- `GET /movies/bulk-import/batches` — resolves the user exclusively from the JWT subject (never a client-supplied filter), maps each batch to a summary with a `statusCounts` map built from the new GROUP BY query
- `GET /movies/bulk-import/batches/{batchId}` — reuses Plan 11-02's `loadOwnedBatch()` unchanged (403 on ownership mismatch, 404 on unknown id), maps every line to a `BulkImportLineResult`
- 4 new integration tests: batch-list ordering + status-count sums, batch-detail with a SAVED line (poster present) and an AMBIGUOUS line (poster null, never fabricated), 403 for a different user, 404 for a nonexistent batch

## Task Commits

Each task was committed atomically:

1. **Task 1: Repository queries + response DTOs** - `1649fa1` (feat)
2. **Task 2: Batch list + detail GET endpoints (ownership-checked)** - `6f05829` (feat)

_Note: no separate plan-metadata commit — worktree execution mode excludes STATE.md/ROADMAP.md; this SUMMARY.md commit follows._

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportBatchSummary.java` - new record `{batchId, createdAt, totalLines, statusCounts}`
- `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportBatchDetail.java` - new record `{batchId, createdAt, totalLines, lines}`
- `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java` - new record `{title, originalTitle, year, status, posterPath}`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatchRepository.java` - added `findByUserIdOrderByCreatedAtDesc`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` - added `findByBatchIdOrderByTitle`, `countByBatchIdGroupByStatus`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - new `getBatches()`/`getBatchDetail()` endpoints, `statusCounts()` helper, new `BulkImportLineRepository` constructor dependency
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - 4 new integration tests (list, detail, 403, 404)

## Decisions Made
- `countByBatchIdGroupByStatus` returns plain `List<Object[]>`, converted to `Map<String, Long>` in the controller — per the plan's explicit instruction not to introduce an interface-projection convention that has no existing precedent in this codebase
- No pagination on the batch-list endpoint — plain `List<BulkImportBatchSummary>` ordered by `created_at DESC`, matching RESEARCH.md's Open Question 2 recommendation for this single-user-first app

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Both read GET endpoints are live, tested (403/404 IDOR coverage), and return real persisted data (no stub) — Plan 11-04's results page and Plan 11-05's batch-list page can consume them directly.
- No inline mutation endpoints were added (D-05 boundary respected).
- No blockers.

## Self-Check: PASSED

All created files verified present on disk (`BulkImportBatchSummary.java`, `BulkImportBatchDetail.java`, `BulkImportLineResult.java`); both task commits (`1649fa1`, `6f05829`) verified in `git log`; full `de.moviearchive.bulkimport.*` test suite (30 tests across 4 classes) verified green via a synchronous foreground `./gradlew test` run.

---
*Phase: 11-bulk-import-feedback-ui*
*Completed: 2026-08-24*
