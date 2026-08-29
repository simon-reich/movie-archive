---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
plan: 03
subsystem: ui
tags: [vue, nuxt, sse, wiki-reload, regression-fix]

# Dependency graph
requires:
  - phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
    provides: "wikiMovieHistory per-movie history list and subscribeToWikiReloadProgress SSE plumbing in settings.vue"
  - phase: 16-bulk-import-correctness-wiki-reload-progress-clarity (plan 02)
    provides: "WikiReloadProgressService.complete()'s stop-vs-completed distinction (the 'complete' event that echoes the last progress event's fields)"
provides:
  - "Duplicate-free per-movie wiki-reload history: the terminal 'complete' SSE event no longer produces a second history row for the last processed movie"
affects: [16-bulk-import-correctness-wiki-reload-progress-clarity]

actuals:
  tokens: 805
  tasks: 1
  commits: 2

tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified:
    - frontend/pages/settings.vue
    - frontend/test/unit/pages/settings.spec.ts

key-decisions:
  - "Guard is `p.lastMovieTitle && !p.complete` with no dependency on `p.stopped` — the duplicate-echo mechanism is identical on the Stop path and the genuine-finish path, so the fix must not special-case either."

patterns-established: []

requirements-completed: [D-04, D-09]

coverage:
  - id: D1
    description: "The per-movie wiki-reload history list shows each genuinely-processed movie exactly once, never duplicating the last row on a terminal 'complete' event (Stop path or genuine finish)."
    requirement: "D-04"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#does not duplicate the last movie row when a genuine finish echoes it in the terminal complete event (G-16-2)"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#does not duplicate the last movie row when a Stop echoes it in the terminal complete event (G-16-2, UAT Test 2)"
        status: pass
    human_judgment: false

duration: ~10min
completed: 2026-08-29
status: complete
---

# Phase 16 Plan 03: Guard Wiki-Reload History Push Against Terminal Echo Summary

**Fixed a one-line duplicate-row bug in `settings.vue`'s wiki-reload per-movie history: the terminal `complete` SSE event, which deliberately echoes the last `progress` event's `lastMovieTitle`/`lastMovieStatus`, was being pushed into history a second time — now suppressed via a `!p.complete` guard, verified by two new regression tests covering both the Stop path and the genuine-finish path.**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-08-29T17:38:37+02:00 (base commit)
- **Completed:** 2026-08-29T17:45:25+02:00
- **Tasks:** 1
- **Files modified:** 2

## Accomplishments
- Closed UAT gap G-16-2: the per-movie history list in the wiki-reload progress panel no longer shows the last processed movie twice in two consecutive rows
- Added two regression tests firing a realistic progress-then-complete event sequence for the same movie, one for the genuine-finish path and one for the Stop path (matching UAT Test 2's exact reported scenario), both asserting the history stays at length 1
- Followed full TDD RED → GREEN cycle: tests committed first in a failing state against the pre-fix code, then the one-line guard was added and verified to turn both new tests green without breaking any of the 20 pre-existing tests in the file

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): Add failing regression tests for duplicate history row** - `f319ee1` (test)
2. **Task 1 (GREEN): Guard history push against terminal complete echo** - `51e7d30` (fix)

**Plan metadata:** (this commit, to follow)

_Note: this tdd="true" task used the standard RED → GREEN cycle; no REFACTOR commit was needed given the minimal one-line fix._

## Files Created/Modified
- `frontend/pages/settings.vue` - Changed the `wikiMovieHistory` push condition from `if (p.lastMovieTitle)` to `if (p.lastMovieTitle && !p.complete)` in the `subscribeToWikiReloadProgress` callback
- `frontend/test/unit/pages/settings.spec.ts` - Added two regression tests to the "wiki-reload progress UI (mounted)" describe block, each firing a two-event sequence (progress then terminal complete) for the same movie and asserting `wrapper.findAll('li')` stays at length 1

## Decisions Made
- The `!p.complete` guard is unconditional on `stopped` — confirmed via the debug session's Evidence that the duplicate-echo mechanism (`WikiReloadProgressService.complete()` copying `prior.lastMovieTitle()`/`lastMovieStatus()`) fires identically whether a run is stopped or reaches its natural end, so the fix could not be scoped to only the Stop path without leaving the genuine-finish path still broken.

## Deviations from Plan

None - plan executed exactly as written. Root cause was pre-diagnosed in `.planning/debug/16-history-duplicate-on-stop.md`; no additional debugging was required. Dependencies had to be installed (`pnpm install`) in this fresh worktree before tests could run, which is expected worktree setup, not a plan deviation.

## Issues Encountered
None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- G-16-2 is closed; the per-movie wiki-reload history is now a faithful 1:1 record of genuinely-processed movies on both the Stop path and the genuine-finish path
- No other history-list behavior (icon/label rendering, ordering, the D-09 3-state distinction) was touched — all pre-existing tests for those behaviors still pass unchanged
- No blockers for remaining phase 16 plans

---
*Phase: 16-bulk-import-correctness-wiki-reload-progress-clarity*
*Completed: 2026-08-29*

## Self-Check: PASSED

- FOUND: frontend/pages/settings.vue
- FOUND: frontend/test/unit/pages/settings.spec.ts
- FOUND: commit f319ee1 (test — RED)
- FOUND: commit 51e7d30 (fix — GREEN)
