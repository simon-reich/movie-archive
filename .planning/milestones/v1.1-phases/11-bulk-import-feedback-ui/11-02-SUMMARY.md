---
phase: 11-bulk-import-feedback-ui
plan: 02
subsystem: api
tags: [spring-boot, sse, server-sent-events, bulk-import]

# Dependency graph
requires:
  - phase: 11-01
    provides: "bulk_import_batch table + BulkImportBatch entity/repository, batchId threaded through runImport()/processLine(), POST /movies/bulk-import returns batchId"
provides:
  - "BulkImportProgressService: in-memory SseEmitter registry (register/publish/complete) with last-known-state replay for reconnects and a synthesized complete event for finished/untracked batches"
  - "runImport() pushes a progress event after every processed line and a complete event at the end of a normal run"
  - "GET /movies/bulk-import/{batchId}/progress — ownership-checked (IDOR-protected) SSE endpoint, text/event-stream"
  - "BulkImportController.loadOwnedBatch(Authentication, UUID) — shared ownership-check helper, reused by Plan 11-03's batch-detail endpoint"
affects: [11-03-batch-list-detail-endpoints, 11-04-frontend-results-page]

# Actuals (#2632)
actuals:
  tokens: 7712
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "In-memory Map<UUID, List<SseEmitter>> registry (single backend instance, no queue infra) instead of a distributed broker, per RESEARCH.md's 'Don't Hand-Roll' guidance"
    - "SseEmitter(Long.MAX_VALUE) — never rely on the container's default async timeout for a stream that can legitimately stay open for the ~83-minute worst-case import duration"
    - "loadOwnedBatch() ownership-check helper ports WikiReloadController.assertOwnership()'s exact pattern for a new {batchId}-path-variable endpoint shape"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java

key-decisions:
  - "register()'s synthetic-complete branch (no lastKnown state) sends the complete event but does NOT call emitter.complete() itself — the client is responsible for closing its own connection after reading that event, consistent with the frontend's planned AbortController pattern (Plan 11-04, RESEARCH.md Pattern 3). Only the real complete() flow (triggered from runImport()'s end) calls emitter.complete()."
  - "sendEvent() removes a failed emitter from the registry on IOException but never calls emitter.completeWithError() afterward, per RESEARCH.md's documented anti-pattern (avoids a double-completion error once the container's own AsyncListener machinery has already handled the disconnect)."

patterns-established:
  - "SSE progress pushed directly from the producer's existing loop (runImport()'s i/rawLines.size()) instead of a separate DB-polling task — see RESEARCH.md Pattern 1"

requirements-completed: [IMPORT-05]

coverage:
  - id: D1
    description: "While an import is running, a client connected to the progress endpoint receives increasing processed/total events without polling"
    requirement: IMPORT-05
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java#publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andCompletesEmitter"
        status: pass
    human_judgment: false
  - id: D2
    description: "A client connecting after the import already finished immediately learns it is complete instead of hanging open forever"
    requirement: IMPORT-05
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java#register_withNoPriorState_immediatelySendsSyntheticComplete"
        status: pass
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java#register_afterComplete_getsSyntheticCompleteFallback_notReplayOfRealCompletion"
        status: pass
    human_judgment: false
  - id: D3
    description: "A user cannot read another user's import progress stream (IDOR protection on the batchId path variable)"
    requirement: IMPORT-05
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn403_whenDifferentUserRequestsProgress"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturnEventStream_whenOwnerRequestsProgress"
        status: pass
    human_judgment: false

duration: ~51min
completed: 2026-08-24
status: complete
---

# Phase 11 Plan 2: Live Progress SSE Endpoint Summary

**In-memory `BulkImportProgressService` SSE emitter registry pushed directly from `runImport()`'s existing loop, exposed via an ownership-checked `GET /movies/bulk-import/{batchId}/progress` endpoint (`text/event-stream`, `SseEmitter(Long.MAX_VALUE)`), with last-known-state replay for reconnects and a synthesized "complete" event for already-finished batches.**

## Performance

- **Duration:** ~51 min
- **Tasks:** 2
- **Files modified:** 6 (2 created, 4 modified)

## Accomplishments
- `BulkImportProgressService` (`@Service`, no Spring context needed to unit-test): `register()`/`publish()`/`complete()` over `Map<UUID, List<SseEmitter>>` + `Map<UUID, ProgressState>` (nested record `ProgressState(int processed, int total, boolean complete)`, Jackson-serialized as the SSE JSON payload)
- `runImport()` calls `progressService.publish(batchId, i + 1, rawLines.size())` after every processed line and `progressService.complete(batchId)` once at the end of a normal (non-interrupted) run
- `register()` replays the batch's last-known state immediately for reconnect/already-in-flight clients, or synthesizes an immediate `complete` event (using the batch's persisted `totalLines` as fallback) when no lastKnown state exists — so a client visiting an already-finished (or never-tracked-this-process-lifetime) batch is never left waiting forever
- `complete()` broadcasts a terminal `complete` event, calls `emitter.complete()` on every registered emitter, then evicts both the emitter list and the lastKnown entry for the batch — bounding memory growth (T-11-05) so a later `register()` correctly falls back to the synthesized-complete case
- `sendEvent()` removes a disconnected emitter on `IOException` without calling `completeWithError()` afterward, avoiding a double-completion error (RESEARCH.md anti-pattern)
- New `GET /movies/bulk-import/{batchId}/progress` endpoint on `BulkImportController`: `loadOwnedBatch()` ports `WikiReloadController.assertOwnership()`'s exact pattern (403 on ownership mismatch, 404 on unknown `batchId`), constructs `SseEmitter(Long.MAX_VALUE)` (never the container's default timeout, given a worst-case ~83-minute import), and registers it with `progressService`
- New `AccessDeniedException` (403) / `NoSuchElementException` (404) exception handlers on `BulkImportController`

## Task Commits

Each task was committed atomically:

1. **Task 1 (tdd): BulkImportProgressService — in-memory SSE emitter registry** - `fa54ae3` (feat)
2. **Task 2: SSE progress endpoint with ownership check (IDOR mitigation)** - `9ffb48f` (feat)

_Note: no separate plan-metadata commit — worktree execution mode excludes STATE.md/ROADMAP.md; this SUMMARY.md commit follows._

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` - new in-memory SSE emitter registry (register/publish/complete)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - `progressService` constructor field; `runImport()` publishes progress per line and completes the stream at the end
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - `GET .../progress` SSE endpoint, `loadOwnedBatch()` ownership helper, `AccessDeniedException`/`NoSuchElementException` handlers
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java` - plain JUnit5+Mockito unit tests covering all 4 required registry behaviors
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - constructor updated for the new `progressService` dependency (compile fix, see Deviations)
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - owner-gets-200/intruder-gets-403 integration tests; rate-limit-bucket reset added (see Deviations)

## Decisions Made
- `register()`'s synthetic-complete branch never calls `emitter.complete()` itself — only sends the informational `complete` SSE event. The connection is closed client-side (Plan 11-04's `AbortController`, per RESEARCH.md Pattern 3), not server-side, for this branch. This matches the plan's literal `<behavior>` spec (which only describes sending an event in this branch) and keeps the completion-ownership model simple: only the real end-of-import `complete()` call ever terminates the async context server-side.
- `sendEvent()` on `IOException` removes the emitter but never calls `completeWithError()`, per RESEARCH.md's documented Spring `ResponseBodyEmitter.send()` anti-pattern (double-completion risk).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated BulkImportServiceTest's constructor call for the new progressService dependency**
- **Found during:** Task 1 (BulkImportProgressService)
- **Issue:** Adding `BulkImportProgressService progressService` as a new constructor parameter on `BulkImportService` breaks `BulkImportServiceTest`'s existing `new BulkImportService(...)` call (positional-arg mismatch, compile error). This test file wasn't in the plan's `files_modified` list but the change is unavoidable — any consumer of `BulkImportService`'s constructor must be updated.
- **Fix:** Added a `@Mock BulkImportProgressService progressService` field and passed it in the constructor call in `setUp()`. No stubbing needed — `processLine()` (the method under test in this class) never calls `progressService` directly; only `runImport()` does.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportServiceTest"` — all 6 tests pass.
- **Committed in:** `fa54ae3` (Task 1 commit)

**2. [Rule 1 - Bug] Fixed a genuinely hanging MockMvc test (`asyncDispatch()` on a never-completing SseEmitter)**
- **Found during:** Task 2 (SSE progress endpoint), while writing the "owner gets 200 text/event-stream" integration test
- **Issue:** The first version of `shouldReturnEventStream_whenOwnerRequestsProgress` called `mockMvc.perform(asyncDispatch(result))` after `request().asyncStarted()`. `asyncDispatch()` blocks on a `CountDownLatch` until the underlying async context (the `SseEmitter`) actually completes. In this test's scenario — a GET against an already-finished batch (after `drainBulkImportExecutor()`) — `register()` hits the synthetic-complete branch, which sends the `complete` event but never calls `emitter.complete()` (see Decisions above). The async context therefore never completes server-side, so `asyncDispatch()` hung indefinitely. Root-caused via a jstack thread dump showing the test thread parked in `DefaultMvcResult.awaitAsyncDispatch` / `CountDownLatch.await()`.
- **Fix:** Removed the `asyncDispatch()` call. The test now asserts directly on the un-dispatched `MvcResult` after `request().asyncStarted()` — `result.getResponse().getContentType()` starts with `text/event-stream`. This is a real correctness bug in the test's design (not the production SSE endpoint, which behaves per the plan's `<behavior>` spec), and matches the plan's own required assertion ("returns 200 with Content-Type starting text/event-stream").
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.bulkimport.*"` — full class completes in ~25s with no hang.
- **Committed in:** `9ffb48f` (Task 2 commit)

**3. [Rule 3 - Blocking] Reset the per-IP `/auth/login` rate-limit bucket between tests**
- **Found during:** Task 2, first full-class run after adding the two new SSE tests
- **Issue:** `RateLimitService` buckets `/auth/login` attempts per client IP (Bucket4j, 10/min). `BulkImportControllerTest` did not reset this bucket between tests (unlike `MovieControllerTest`/`SearchControllerTest`/etc., which already follow this pattern). All `MockMvc` requests in a test class share one client IP, so login attempts accumulate across the whole class. This plan's two new tests added 3 more `loginAndGetToken()` calls, pushing the class's cumulative total over the 10/min limit and causing `shouldReturn422_whenNoTmdbKeyConfigured` to intermittently get `429` instead of `200` from its own login call.
- **Fix:** Autowired `RateLimitService` and added `rateLimitService.resetAll()` to `cleanDb()`'s `@BeforeEach`, mirroring the exact pattern already established in `MovieControllerTest`.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.bulkimport.*"` — 20 tests (4 + 6 + 10), 0 failures.
- **Committed in:** `9ffb48f` (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (1 blocking compile fix, 1 test-design bug, 1 blocking test-flakiness fix)
**Impact on plan:** All three were necessary to get a genuinely green, non-hanging, non-flaky test suite. No production-code scope creep — all three fixes are test-only.

## Issues Encountered
- The hanging `asyncDispatch()` test (deviation #2 above) initially manifested as apparent Gradle daemon/build-lock contention from multiple stacked background test invocations, which obscured the real root cause until a synchronous foreground run plus a jstack thread dump isolated it to the specific test method. Resolved per deviation #2.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- `BulkImportProgressService` and the `GET .../progress` SSE endpoint are live and tested — Plan 11-04's frontend can consume them via `@microsoft/fetch-event-source` exactly as RESEARCH.md's Pattern 3 describes.
- `BulkImportController.loadOwnedBatch()` is a reusable, tested ownership-check helper — Plan 11-03's batch-detail GET endpoint can call it as-is.
- No blockers.

## Self-Check: PASSED

All created files verified present on disk (`BulkImportProgressService.java`, `BulkImportProgressServiceTest.java`); both task commits (`fa54ae3`, `9ffb48f`) verified in `git log`; full `de.moviearchive.bulkimport.*` test suite (20 tests) verified green via a synchronous foreground `./gradlew test` run.

---
*Phase: 11-bulk-import-feedback-ui*
*Completed: 2026-08-24*
