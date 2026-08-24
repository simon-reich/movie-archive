---
phase: 10-bulk-import-engine
plan: 03
subsystem: bulk-import
tags: [bulk-import, validation, ux, spring-boot, vue]

# Dependency graph
requires:
  - phase: 10-bulk-import-engine
    provides: ImportLineParser, BulkImportController, BulkImportService (from 10-01/10-02)
provides:
  - Synchronous pre-flight "no lines parseable" gate on POST /movies/bulk-import
  - Bulk Import format hint always visible on add.vue before upload
  - Backend 400 message surfacing in add.vue's FormErrorBanner
affects: [11-bulk-import-feedback-ui]

# Actuals (#2632)
actuals:
  tokens: 2213
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pre-flight fail-fast validation before async dispatch (mirrors existing maxLines-exceeded check)"
    - "Frontend catch-block branches on HTTP status to select error message source (hardcoded vs backend-provided)"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
    - frontend/pages/add.vue
    - frontend/test/unit/pages/add.spec.ts

key-decisions:
  - "Reused the existing IllegalArgumentException -> handleIllegalArgument -> 400 exception handler for the new pre-flight check, no new exception type needed"
  - "Renamed shouldReturn202_notCrash_forNonUtf8Bytes to shouldReturn400_notCrash_forNonUtf8Bytes since garbled non-UTF-8 bytes now correctly trip the pre-flight gate instead of dispatching a no-op async job"

patterns-established:
  - "Whole-batch pre-flight validation gate pattern: compute a stream-based anyMatch over all parsed lines before touching async infrastructure, throw IllegalArgumentException on failure — same shape as the pre-existing maxLines check"

requirements-completed: [IMPORT-01]

coverage:
  - id: D1
    description: "Uploading a file where no lines match Title;OriginalTitle;Year is rejected synchronously with 400 and a specific message; no bulk_import_line row created, no TMDB call fires"
    requirement: "IMPORT-01"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn400_whenAllLinesFailToParse"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn400_notCrash_forNonUtf8Bytes"
        status: pass
    human_judgment: false
  - id: D2
    description: "Partial-failure batches (some parseable lines, some not) are unaffected — still processed per-line and still return 202"
    requirement: "IMPORT-01"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldSaveUniqueMatch_andPersistBulkImportLineRow"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldMarkAmbiguous_whenMultipleYearMatchesNoOriginalTitle"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldNarrowToUnique_whenOriginalTitleMatches"
        status: pass
    human_judgment: false
  - id: D3
    description: "Bulk Import section on add.vue always shows a Title;OriginalTitle;Year format hint before any upload"
    requirement: "IMPORT-01"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/add.spec.ts (component-module smoke tests confirm add.vue still exports a valid component after the template change)"
        status: pass
    human_judgment: true
    rationale: "Visual placement/always-visible rendering of the hint paragraph is asserted by source inspection, not a DOM-mounted test (this test file uses composable-level assertions only, no DOM mount, per its established convention) — a human should visually confirm the hint renders in the running app."
  - id: D4
    description: "A 400 rejection with a message body sets bulkImportError to that exact backend message instead of the generic fallback"
    requirement: "IMPORT-01"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/add.spec.ts#propagates 400 message when all bulk import lines fail to parse"
        status: pass
    human_judgment: false

# Metrics
duration: 15min
completed: 2026-08-24
status: complete
---

# Phase 10 Plan 03: Bulk Import Gap Closure (G-10-1) Summary

**Pre-flight parse-validity gate rejects wholly-unparseable bulk import batches with a synchronous 400, and add.vue now always shows the required format and surfaces that backend message instead of a generic fallback.**

## Performance

- **Duration:** 15 min
- **Started:** 2026-08-24T14:06:00Z (approx.)
- **Completed:** 2026-08-24T14:21:56Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- `BulkImportController.uploadBulkImport()` now checks, before async dispatch, whether any uploaded line parses via `ImportLineParser`; if none do, it throws `IllegalArgumentException("No lines could be parsed. Expected format: Title;OriginalTitle;Year per line (Original Title may be left empty), e.g. \"Inception;;2010\". Check your file and try again.")`, reusing the existing `handleIllegalArgument` 400 handler — no `bulk_import_line` row created, no TMDB call fires
- Renamed `shouldReturn202_notCrash_forNonUtf8Bytes` to `shouldReturn400_notCrash_forNonUtf8Bytes` (garbled bytes now correctly trip the pre-flight gate) and added `shouldReturn400_whenAllLinesFailToParse`, both green alongside all pre-existing tests
- `add.vue`'s Bulk Import section always renders a `Title;OriginalTitle;Year` format hint (`text-sm text-muted-foreground`, matching settings.vue's convention) directly under the section heading, before any file is ever selected
- `handleBulkImport()`'s catch block now reads `err.data?.message` for a `status === 400` response and surfaces it verbatim via the existing `FormErrorBanner`, before falling back to the generic "Import failed. Please try again." message; the `422` branch is unchanged

## Task Commits

Each task was committed atomically:

1. **Task 1: Pre-flight "no lines parseable" gate in BulkImportController** - `60c6777` (fix)
2. **Task 2: Format hint + backend-message surfacing on add.vue** - `bcbab89` (feat)

**Plan metadata:** committed alongside this SUMMARY (worktree mode — orchestrator handles the metadata commit centrally)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - Added `ImportLineParser` constructor injection and the pre-flight `anyLineParses` gate before `bulkImportService.runImport()`
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - Renamed the non-UTF-8 test to assert 400 (removed its now-unused WireMock stub) and added `shouldReturn400_whenAllLinesFailToParse`
- `frontend/pages/add.vue` - Added the static format-hint paragraph and widened the bulk-import catch-block error type/branch to surface backend 400 messages
- `frontend/test/unit/pages/add.spec.ts` - Added a test proving the 400 status+message body propagates through `uploadBulkImport` unmodified

## Decisions Made
- Reused the pre-existing `IllegalArgumentException` → `handleIllegalArgument` 400 exception handler for the new pre-flight check rather than introducing a new exception type, matching the plan's guidance and the existing `maxLines`-exceeded precedent exactly.
- Left `BulkImportService.processLine()`'s per-line `PARSE_ERROR` persistence (D-03) and all TMDB matching/ambiguity logic (D-05/D-06/D-07) completely untouched — this plan only adds a whole-batch gate in the controller before async dispatch.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Backend Testcontainers could not find the Docker environment**
- **Found during:** Task 1 verification (`./gradlew test`)
- **Issue:** Testcontainers' `UnixSocketClientProviderStrategy` looked for `/var/run/docker.sock`, which doesn't exist under OrbStack (socket lives at `~/.orbstack/run/docker.sock`); `DOCKER_HOST` wasn't propagated into the sandboxed bash environment either.
- **Fix:** Ran the verification command with `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` explicitly set — an environment workaround, no code or config file changed.
- **Files modified:** None (environment-only workaround for this session's verification run)
- **Verification:** `BulkImportControllerTest` ran successfully — 8/8 tests passed
- **Committed in:** N/A (no code change)

**2. [Rule 3 - Blocking] Frontend `node_modules` missing in this worktree**
- **Found during:** Task 2 verification (`npx vitest run`)
- **Issue:** This git worktree had no `frontend/node_modules`, so vitest's config failed to resolve `@nuxt/test-utils/config`.
- **Fix:** Ran `pnpm install` in `frontend/` to populate the worktree's own `node_modules` (gitignored, no lockfile drift, nothing staged).
- **Files modified:** None tracked (node_modules is gitignored)
- **Verification:** `add.spec.ts` ran successfully — 11/11 tests passed
- **Committed in:** N/A (no code change)

---

**Total deviations:** 2 auto-fixed (2 blocking, both environment-setup issues unrelated to source code)
**Impact on plan:** No scope creep — both deviations were local test-environment prerequisites, not code changes. All acceptance criteria for both tasks verified against a fully working test setup.

## Issues Encountered
None beyond the two environment deviations documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Gap G-10-1 closed: a wholly-unparseable bulk import batch is now rejected synchronously with a specific, actionable 400 message, and the required file format is documented directly in the UI before any upload.
- Partial-failure batches (some good lines, some bad) remain completely unaffected — still processed per-line exactly as before (D-03).
- No blockers for Phase 11 (Bulk Import Feedback UI), which can now build on a backend that never silently no-ops on a fully malformed upload.

## Self-Check: PASSED

- FOUND: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
- FOUND: backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
- FOUND: frontend/pages/add.vue
- FOUND: frontend/test/unit/pages/add.spec.ts
- FOUND commit: 60c6777 (fix(10-03): pre-flight gate rejects wholly-unparseable bulk import batches)
- FOUND commit: bcbab89 (feat(10-03): surface bulk import format hint and backend 400 message)
- Backend verification: `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportControllerTest" --no-daemon` — 8/8 tests passed
- Frontend verification: `npx vitest run test/unit/pages/add.spec.ts` — 11/11 tests passed

---
*Phase: 10-bulk-import-engine*
*Completed: 2026-08-24*
