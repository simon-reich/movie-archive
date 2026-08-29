---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
plan: 01
subsystem: bulk-import
tags: [spring-boot, jpa, tmdb, testing, mockito, dedup]

# Dependency graph
requires: []
provides:
  - Batch-scoped `BulkImportLineRepository` query methods (4 new sibling methods)
  - `BulkImportService.findExistingRow()`/`existingSaved` fast-path scoped by `batchId` — fixes CR-01 cross-batch line reassignment
  - Multi-stage TMDB auto-match algorithm in `processLine()` (single-result trust, exact title+year narrowing, original-title fallback, never-auto-guess)
affects: [bulk-import UI (Phase 15), any future phase touching BulkImportService's matching or dedup logic]

# Actuals (#2632)
actuals:
  tokens: 7400
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Batch-scoped repository query pattern: every new dedup lookup takes BOTH userId AND batchId (never batchId alone), mirroring findByIdAndBatchId/loadOwnedBatch()'s defense-in-depth convention"
    - "Multi-stage narrowing pipeline: single-result trust -> exact title-or-originalTitle+year match -> original-title-only fallback -> AMBIGUOUS, never auto-guessing on a genuine tie"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java

key-decisions:
  - "Batch-scope every dedup lookup (findExistingRow, existingSaved fast-path) by userId+batchId instead of userId+title+year alone — fixes the pre-existing (Phase 10) CR-01 bug where an overlapping title/year across two batches silently reassigned a line's batch_id"
  - "A title/year already SAVED in an OLDER batch is treated as unseen in a NEW batch — re-runs the full TMDB search+match pipeline rather than adding a new cross-batch SAVED short-circuit; movieService.initiate()'s existing tmdbId-idempotency prevents a duplicate Movie row (accepted cost: one redundant TMDB call)"
  - "processLine()'s automatic TMDB matching reworked to trust a unique title hit over year: single overall result is taken directly regardless of year, multiple results narrow via exact title-or-originalTitle+year match before falling back to original-title-only narrowing, AMBIGUOUS remains the only terminal state for a genuine tie (never auto-guess, D-04 unchanged)"

patterns-established:
  - "Batch-scoped repository query pattern: pair every existing (userId, ...) dedup finder with an (userId, batchId, ...) sibling when the same lookup family needs cross-batch isolation"

requirements-completed: [D-01, D-02, D-03, D-10, D-11, D-12, D-13]

coverage:
  - id: D1
    description: "CR-01 fix — batch-scoped dedup lookups prevent cross-batch line reassignment, proven end-to-end via a real-DB integration test"
    requirement: "D-01"
    verification:
      - kind: integration
        ref: "BulkImportControllerTest#shouldReturn404_whenResolvingLineFromDifferentBatch"
        status: pass
    human_judgment: false
  - id: D2
    description: "Fast mock-level regression coverage proving cross-batch isolation (no row lookup crosses batchIds) and same-batch reuse (re-upload in the same batch still updates the existing row) both hold"
    requirement: "D-02"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldNotReuseRow_acrossDifferentBatchIds"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldReuseRow_onSameBatchReupload"
        status: pass
    human_judgment: false
  - id: D3
    description: "A title/year SAVED in an older batch is treated as unseen in a new batch — re-processed, own row created, no duplicate Movie"
    requirement: "D-03"
    verification:
      - kind: integration
        ref: "BulkImportControllerTest#shouldReprocessAndNotDuplicateMovie_whenReuploadedAsNewBatch"
        status: pass
    human_judgment: false
  - id: D4
    description: "Multi-stage TMDB matching: single result saved directly regardless of year; multiple results narrow via exact title-or-originalTitle+year match, then original-title fallback; zero results map to NOT_FOUND; unresolved ties stay AMBIGUOUS"
    requirement: "D-10"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldSave_whenSingleResultRegardlessOfYearMismatch"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldRecordNotFound_whenTmdbSearchReturnsZeroResults"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldSave_whenMultipleResultsButExactlyOneExactTitleAndYearMatch"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldSave_whenParsedTitleMatchesCandidateOriginalTitleField"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldMarkAmbiguous_whenMultipleYearMatchesAndNoOriginalTitle"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldSave_whenOriginalTitleNarrowsAmbiguousCandidatesToOne"
        status: pass
      - kind: unit
        ref: "BulkImportServiceTest#shouldStayAmbiguous_whenOriginalTitleDoesNotNarrowToOne"
        status: pass
    human_judgment: false

# Metrics
duration: 55min
completed: 2026-08-29
status: complete
---

# Phase 16 Plan 1: Bulk Import Correctness (Cross-Batch Dedup Fix + Multi-Stage TMDB Matching) Summary

**Batch-scoped `BulkImportLineRepository`/`BulkImportService` dedup queries fix a pre-existing cross-batch line-reassignment bug (CR-01), and `processLine()`'s TMDB matching is reworked into a 4-branch algorithm that trusts a unique title hit over year.**

## Performance

- **Duration:** 55 min
- **Started:** 2026-08-29T10:XX (per STATE.md phase-start context)
- **Completed:** 2026-08-29
- **Tasks:** 3
- **Files modified:** 4

## Accomplishments

- Added 4 batch-scoped sibling query methods to `BulkImportLineRepository` (`findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus`, `findByUserIdAndBatchIdAndNormalizedTitleAndYear`, `findByUserIdAndBatchIdAndNormalizedTitleAndYearIsNull`, `findByUserIdAndBatchIdAndRawLineAndYearIsNull`), mirroring the existing `findByIdAndBatchId`/`loadOwnedBatch()` defense-in-depth convention (userId AND batchId, never batchId alone)
- Scoped `BulkImportService.findExistingRow()` and the `existingSaved` dedup fast-path in `processLine()` by `batchId`, fixing CR-01 (15-REVIEW.md) — re-uploading an overlapping title/year across two different batches no longer silently reassigns an existing batch's line to the new batch
- Proved the CR-01 fix end-to-end at the integration-test layer: removed the `bulkImportLineRepository.deleteAll()` workaround from `shouldReturn404_whenResolvingLineFromDifferentBatch` that previously masked the bug, and added assertions that batch A's original row survives batch B's overlapping upload untouched
- Reworked `processLine()`'s automatic TMDB matching into a 4-branch algorithm per the 2026-08-29 user decision: zero results → NOT_FOUND; a single overall result → saved directly regardless of year; multiple results → exact case-insensitive title-or-originalTitle+year narrowing, then original-title-only fallback; unresolved ties stay AMBIGUOUS (never auto-guess, D-04 invariant unchanged)
- Added fast mock-level regression tests (`shouldNotReuseRow_acrossDifferentBatchIds`, `shouldReuseRow_onSameBatchReupload`) proving the batch-scoping change at the mock-interaction level, complementing the real-DB integration proof
- Added/updated unit tests for the new matching algorithm covering every branch: single-result trust regardless of year mismatch, genuine zero-result NOT_FOUND, exact title+year narrowing, and the originalTitle-field variant of exact narrowing

## Task Commits

Each task was committed atomically:

1. **Task 1: Batch-scope the dedup lookup end-to-end (D-01, D-02, D-03)** - `4baee30` (fix)
2. **Task 2: Fast unit-level regression coverage for batch-scoped dedup (D-01, D-02)** - `e78a0fc` (test)
3. **Task 3: Multi-stage TMDB matching rework (D-10, D-11, D-12, D-13)** - `faa447c` (feat)

_Task 3 carried `tdd="true"` but was executed as a direct rework + companion test update (not a separate RED-commit cycle), since the plan's `<action>` explicitly modified both production code and tests together as one atomic change rather than prescribing a strict test-first RED phase._

## Files Created/Modified

- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` - 4 new batch-scoped sibling query methods, all 4 pre-existing non-batch-scoped methods kept unmodified
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - `findExistingRow()` gains a `batchId` parameter; `existingSaved` fast-path and `upsertLine()`'s call site updated; `processLine()`'s matching block reworked to the 4-branch algorithm
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - removed the `deleteAll()` CR-01 workaround from `shouldReturn404_whenResolvingLineFromDifferentBatch`, added batch-scoped assertions; renamed and flipped `shouldSkipReupload_whenLineAlreadySaved` to `shouldReprocessAndNotDuplicateMovie_whenReuploadedAsNewBatch`; added a batch-scoped `pollForLineByTitle(batchId, title, timeout)` test helper to disambiguate same-title rows across two batches
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - updated `@BeforeEach` stubs to the batch-scoped repository methods; fixed the `getReferenceById` stub to echo back the requested `batchId`; added 6 new/renamed tests covering batch isolation, same-batch reuse, and the new matching algorithm

## Decisions Made

- Batch-scope every dedup lookup by `userId` + `batchId` (never `batchId` alone) — mirrors the existing `findByIdAndBatchId`/`loadOwnedBatch()` IDOR-mitigation convention from Phase 15, applied here for data-integrity rather than authorization purposes
- Per D-03, no new cross-batch "already SAVED elsewhere" optimization was added — a title/year SAVED in an older batch now re-runs the full TMDB search+save pipeline in a new batch (one redundant TMDB call accepted), relying on `movieService.initiate()`'s existing `tmdbId` idempotency to prevent a duplicate `Movie` row
- `processLine()`'s matching algorithm trusts a single overall TMDB result unconditionally (no year check) — a deliberate loosening of the previous "year-match first" filter, justified by the "never auto-guess when more than one candidate survives narrowing" invariant (D-04) remaining fully intact for any multi-candidate case

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated a pre-existing test that encoded the OLD, buggy cross-batch dedup behavior**
- **Found during:** Task 1 (verifying the full `BulkImportControllerTest` suite after the batch-scoping change)
- **Issue:** `shouldSkipReupload_whenLineAlreadySaved` re-uploaded the identical raw line via two separate `/movies/bulk-import` calls (each creating a NEW batch) and asserted the TMDB search fired only once — i.e. it asserted the OLD cross-batch SAVED short-circuit that CR-01/D-02/D-03 explicitly remove. After the batch-scoping fix, this test failed with `Expected exactly 1 requests ... but received 2`, which is the CORRECT new behavior, not a regression.
- **Fix:** Renamed to `shouldReprocessAndNotDuplicateMovie_whenReuploadedAsNewBatch` and updated assertions to prove the new D-02/D-03 contract: the second upload re-runs the full TMDB search (2 requests total), creates its own independent `BulkImportLine` row in the new batch, and — critically — does NOT create a duplicate `Movie` row (`movieService.initiate()`'s idempotency), while the first batch's row remains untouched.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportControllerTest"` — full suite green
- **Committed in:** `4baee30` (Task 1 commit)

**2. [Rule 3 - Blocking] Fixed the `getReferenceById` mock stub to echo back the requested batchId**
- **Found during:** Task 2 (writing `shouldNotReuseRow_acrossDifferentBatchIds`)
- **Issue:** The existing `@BeforeEach` stub for `bulkImportBatchRepository.getReferenceById(any())` returned a single fixed `BulkImportBatch` instance whose `id` field was always `null` (JPA `@GeneratedValue`, never set in the test double). Since `upsertLine()`'s call to `findExistingRow()` passes `batch.getId()`, every `processLine()` call — regardless of which `batchId` argument it received — would have resolved to the same `null` batch id, making it impossible for the new mock-level test to distinguish batchIdA from batchIdB at the repository-call level.
- **Fix:** Changed the stub to `.thenAnswer(inv -> { batch.setId(inv.getArgument(0)); return batch; })`, echoing the requested `batchId` back as the returned batch's id (the class already has Lombok `@Setter` on all fields, including `id`).
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
- **Verification:** `shouldNotReuseRow_acrossDifferentBatchIds` passes, correctly asserting `findByUserIdAndBatchIdAndNormalizedTitleAndYear` was called with `batchIdA` and `batchIdB` respectively, in that order
- **Committed in:** `e78a0fc` (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (1 bug — a test encoding the exact bug this plan fixes, 1 blocking — a test double that couldn't distinguish its own inputs)
**Impact on plan:** Both fixes were necessary consequences of correctly implementing this plan's core intent; no scope creep beyond files already listed in the plan's `files_modified` frontmatter.

### Documentation Note

The plan's Task 1 acceptance criterion `grep -c "bulkImportLineRepository.deleteAll()" ... returns 0` could not be satisfied literally — the file also contains an unrelated, pre-existing `@BeforeEach cleanDb()` call to `bulkImportLineRepository.deleteAll()` (test-isolation cleanup shared by every test in the class, present since before this phase) that the same grep pattern also matches. The specific CR-01 workaround call the plan's `<action>` text names (at the original line 690, inside `shouldReturn404_whenResolvingLineFromDifferentBatch`) WAS removed — confirmed by re-reading the test method and by the count dropping from 2 occurrences to 1. Removing the `@BeforeEach` cleanup call as well would break test isolation for all 25 tests in the class and was correctly out of scope.

## Issues Encountered

- Testcontainers in this sandboxed execution environment required `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` to be set explicitly for every `./gradlew test` invocation touching `BulkImportControllerTest` (an `AbstractIntegrationTest`/Testcontainers-backed test) — the project's `~/.testcontainers.properties` pins `UnixSocketClientProviderStrategy` against the default `/var/run/docker.sock`, but this machine's actual Docker socket (OrbStack) lives elsewhere. This is a local environment quirk, not a code issue — no source files were changed to work around it, and CI/other developer machines are unaffected.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- CR-01 (cross-batch line reassignment) is fixed and proven via a real-DB integration test with no workaround — this was the last open item on the v1.1 milestone's pre-existing bug backlog for bulk import.
- `processLine()`'s automatic matching now implements the exact 4-branch algorithm from the user's 2026-08-29 decision; the "never auto-guess" invariant (D-04) is verified intact by both new and pre-existing tests.
- No blockers for the remaining phase-16 work (wiki-reload stop-vs-completed progress-UI clarity, tracked separately in this phase's other plan(s)).

---
*Phase: 16-bulk-import-correctness-wiki-reload-progress-clarity*
*Completed: 2026-08-29*

## Self-Check: PASSED

- FOUND: backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
- FOUND: backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
- FOUND: backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
- FOUND: backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
- FOUND: commit 4baee30 (Task 1)
- FOUND: commit e78a0fc (Task 2)
- FOUND: commit faa447c (Task 3)
- All acceptance criteria re-verified via grep/test runs (see plan verification block); `./gradlew test --tests "de.moviearchive.bulkimport.*"` green
