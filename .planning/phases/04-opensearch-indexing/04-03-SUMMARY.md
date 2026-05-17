---
phase: 04-opensearch-indexing
plan: 03
subsystem: api
tags: [opensearch, spring-boot, jwt, testcontainers, mockmvc]

# Dependency graph
requires:
  - phase: 04-opensearch-indexing/04-02
    provides: IndexingService.fullReindex + reindexPending methods, AbstractOpenSearchTest base class

provides:
  - POST /admin/reindex/{userId} — full delete+recreate+reindex-all endpoint with 403 IDOR protection
  - POST /admin/reindex/{userId}/pending — partial reindex for null indexed_at with 403 IDOR protection
  - ReindexController with email-based ownership check
  - ReindexControllerTest — IDX-04 (4 tests green)

affects: [phase-5-search, phase-7-e2e]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Email-based ownership check: resolve userId from auth.getName() (email) via userRepository.findByEmail()"
    - "AccessDeniedException handler: @ExceptionHandler in controller returning 403 Map.of('message','Access denied.')"
    - "Unique tmdbId per test movie: use sequence counter to avoid (user_id, tmdb_id) unique constraint in tests"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/admin/ReindexController.java
  modified:
    - backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java

key-decisions:
  - "auth.getName() returns email (not userId UUID) in current UserDetailsServiceImpl — ownership check must resolve userId via userRepository.findByEmail(auth.getName())"
  - "ReindexController injects UserRepository to resolve userId from JWT email subject"

patterns-established:
  - "Email ownership check: userRepository.findByEmail(auth.getName()).getId().equals(pathUserId)"
  - "Test movie helper: use incrementing tmdbId sequence to avoid unique constraint on (user_id, tmdb_id)"

requirements-completed: [IDX-04]

# Metrics
duration: 25min
completed: 2026-05-17
---

# Phase 04 Plan 03: Admin Reindex Endpoints Summary

**POST /admin/reindex/{userId} and /pending endpoints with email-based ownership enforcement (D-03), backed by IndexingService.fullReindex + reindexPending — 4 IDX-04 tests green**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-05-17T19:05:00Z
- **Completed:** 2026-05-17T19:30:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Created `ReindexController` with two admin endpoints: full reindex (delete+recreate+reindex-all) and pending reindex (indexed_at IS NULL only), both protected by JWT-subject ownership enforcement (D-03)
- Enabled and implemented all 4 `ReindexControllerTest` cases for IDX-04: 403 IDOR protection, full reindex with indexed_at Pitfall 5 assertion, partial reindex correctness, and indexed count response
- Full test suite green — all prior 6 IndexingIntegrationTest tests and all ReindexControllerTest tests pass (no regressions)

## Task Commits

Each task was committed atomically:

1. **Task 1: ReindexController (initial)** - `50172c9` (feat)
2. **Task 2: ReindexController ownership fix + ReindexControllerTest** - `08c2254` (feat — includes Rule 1 bug fix)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `backend/src/main/java/de/moviearchive/admin/ReindexController.java` — Two admin reindex endpoints with email-based ownership check, AccessDeniedException handler
- `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — @Disabled removed, all 4 IDX-04 tests implemented with real OpenSearch + Postgres assertions

## Decisions Made

- `auth.getName()` in `UserDetailsServiceImpl` returns the user's **email** (not userId UUID) — ownership check must use `userRepository.findByEmail(auth.getName())` to resolve `userId`, then compare to path variable. The plan's stated pattern (`auth.getName().equals(userId.toString())`) does not match the actual `UserDetailsServiceImpl` implementation.
- `ReindexController` injects `UserRepository` (constructor injection) for email→userId resolution.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Ownership check used wrong auth.getName() assumption**
- **Found during:** Task 2 (shouldFullReindex test returning 403 for owner)
- **Issue:** Plan assumed `auth.getName()` returns userId UUID string. `UserDetailsServiceImpl.loadUserByUsername(userId)` returns `new User(user.getEmail(), ...)` — `auth.getName()` actually returns email. The check `auth.getName().equals(userId.toString())` always fails.
- **Fix:** Changed ownership check to resolve User from email via `userRepository.findByEmail(auth.getName())` and compare `user.getId()` to path `userId`. Also injects `UserRepository` into controller via constructor.
- **Files modified:** `backend/src/main/java/de/moviearchive/admin/ReindexController.java`
- **Verification:** `shouldFullReindex`, `shouldIndexOnlyPending`, `shouldReturnIndexedCount` all pass with 200 for owner; `shouldReturn403_whenUserMismatch` still returns 403 for cross-user calls
- **Committed in:** `08c2254`

**2. [Rule 1 - Bug] Test movie helper used duplicate tmdbId**
- **Found during:** Task 2 (DataIntegrityViolationException on second persistMovie call)
- **Issue:** `persistMovie` used hard-coded `tmdbId=27205` for all movies; `(user_id, tmdb_id)` unique constraint rejected second insert for same user.
- **Fix:** Added `tmdbIdSeq` counter field; each `persistMovie` call uses a unique `tmdbId` starting at 1000.
- **Files modified:** `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java`
- **Verification:** All 4 tests pass without constraint violations
- **Committed in:** `08c2254`

---

**Total deviations:** 2 auto-fixed (2 Rule 1 bugs)
**Impact on plan:** Both fixes required for correctness — no scope creep.

## Issues Encountered

None beyond the auto-fixed deviations above.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Phase 4 complete: OpenSearch indexing stack (custom analyzer, DocumentBuilder, IndexingService, EnrichmentService Step 5, full+partial reindex admin endpoints) fully implemented and tested (10 integration tests green)
- Phase 5 (Search) can use `IndexingService`, `movies-{userId}` index, and custom `custom_english_analyzer` directly
- No blockers

## Threat Flags

None — all trust boundaries in the threat model (T-04-03-01 through T-04-03-04) covered by implementation and tests.

## Self-Check: PASSED

- ReindexController.java — FOUND
- ReindexControllerTest.java — FOUND
- 04-03-SUMMARY.md — FOUND
- Commit 50172c9 — FOUND (feat: initial ReindexController)
- Commit 08c2254 — FOUND (MOV-5: ReindexController fix + ReindexControllerTest IDX-04)
- Full test suite — PASSED (no regressions)

---
*Phase: 04-opensearch-indexing*
*Completed: 2026-05-17*
