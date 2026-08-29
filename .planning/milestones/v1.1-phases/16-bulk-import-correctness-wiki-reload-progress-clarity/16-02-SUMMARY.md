---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
plan: 02
subsystem: ui
tags: [wiki-reload, sse, progress-ui, vue, spring-boot, jackson-records]

# Dependency graph
requires:
  - phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
    provides: WikiReloadProgressService SSE progress registry, ProgressState record, Stop-flag mechanism, settings.vue progress panel
provides:
  - "ProgressState.stopped field distinguishing a stopped-early run from a genuinely finished one"
  - "complete() reporting the real last-published processed count instead of always total"
  - "wikiStatusLabel computed with distinct in-progress/Stopped/Completed wording"
  - "Progress panel visibility surviving both terminal states (stopped and finished), not just active runs"
  - "3-state per-movie history icons/labels: SUCCESS/NOT_FOUND/FAILED"
affects: [17-*, any-future-wiki-reload-ui-work]

# Actuals (#2632)
actuals:
  tokens: 4510
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Trailing-field-addition to a Jackson-serialized Java record (positional-by-name, not by order) to extend an SSE payload without disturbing existing construction sites"
    - "Read-before-clear ordering for a stop-flag check inside a finally-guaranteed cleanup method"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
    - backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
    - frontend/composables/useSettings.ts
    - frontend/pages/settings.vue
    - frontend/test/unit/pages/settings.spec.ts

key-decisions:
  - "ProgressState.stopped placed as a trailing record field (not mid-record as 14-REVIEW.md originally sketched) — Jackson serializes records positionally by field name, so appending avoids reordering every existing construction site's argument meaning"
  - "complete() reads isStopRequested(userId) BEFORE stopFlags.remove(userId) clears it — reading after would always observe a cleared flag and silently reintroduce the WR-02 bug this plan fixes"
  - "Progress panel visibility guard widened from the plan's literal '!complete || stopped' to 'total > 0' — the literal guard would hide the panel on any genuine (non-stopped) completion, exactly when it needs to show 'Completed X / Y', contradicting the plan's own truths and its acceptance test (b); 'total > 0' excludes only the zero-progress synthetic register() placeholder sent before any real run has started"
  - "register()'s synthesized never-run placeholder ProgressState uses stopped=true (per plan's explicit instruction), but is excluded from panel visibility via the total===0 check rather than via the stopped flag, since OR-ing in wikiProgress.stopped would have made that placeholder visible too"

patterns-established:
  - "Amber-vs-default text color to visually distinguish a stopped-terminal status line from a genuinely-completed one, driven by the same stopped field the status text branches on"

requirements-completed: [D-04, D-05, D-06, D-07, D-08, D-09]

coverage:
  - id: D1
    description: "complete() reports stopped=true and the real last-published processed count (not total) when a run ends via requestStop()"
    requirement: "D-04"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java#requestStop_thenComplete_reportsStoppedTrueAndRealProcessedCount"
        status: pass
    human_judgment: false
  - id: D2
    description: "wikiStatusLabel distinguishes in-progress / 'Stopped at X / Y' / 'Completed X / Y' wording"
    requirement: "D-05"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#keeps the progress panel visible and shows \"Stopped at X / Y\" on a stopped-terminal event"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#shows \"Completed X / Y\" on a genuinely finished run"
        status: pass
    human_judgment: false
  - id: D3
    description: "Progress panel and per-movie history stay visible after a stopped-terminal SSE event, not only while a run is actively in-progress"
    requirement: "D-06"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#keeps the progress panel visible and shows \"Stopped at X / Y\" on a stopped-terminal event"
        status: pass
    human_judgment: false
  - id: D4
    description: "Reload button re-enables and history clears on either terminal state (stopped or completed) — same trigger point as before, verified against the new schema, no code change needed"
    requirement: "D-07, D-08"
    verification: []
    human_judgment: true
    rationale: "Traced both call sites (:disabled binding and onTriggerWikiReload's history-clear condition) against the new ProgressState.complete semantics and confirmed both already key off `complete` alone, which is true for both stopped and finished states after Task 1 — but this is a code-reading confirmation, not a dedicated automated test asserting the button re-enables specifically after a *stopped* event (existing tests only exercise the genuinely-finished re-enable path)."
  - id: D5
    description: "Per-movie history renders 3 distinct states: SUCCESS (checkmark), NOT_FOUND (neutral icon + 'No Wikipedia article found' label), FAILED (X)"
    requirement: "D-09"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#renders \"No Wikipedia article found\" for a NOT_FOUND history entry instead of the checkmark/X icon framing"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-08-29
status: complete
---

# Phase 16 Plan 02: Wiki-Reload Progress Clarity Summary

**Fixed `WikiReloadProgressService.complete()`'s always-100%-looking terminal state by adding a `stopped` field read before the stop-flag is cleared, and threaded it through `settings.vue` so a stopped run shows "Stopped at X / Y" instead of silently vanishing or looking fully done, while the per-movie history now distinguishes SUCCESS/NOT_FOUND/FAILED instead of collapsing NOT_FOUND into a generic failure icon.**

## Performance

- **Duration:** ~20 min (not precisely instrumented — start timestamp was not captured at session start)
- **Completed:** 2026-08-29T10:18:57Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- `ProgressState` record gains a trailing `stopped` boolean field; `complete()` now reads `isStopRequested(userId)` before `stopFlags.remove(userId)` clears it, and reports the real last-published processed count instead of always `total`
- `WikiReloadProgress` TypeScript interface and `settings.vue`'s `wikiStatusLabel` computed distinguish in-progress / "Stopped at X / Y" / "Completed X / Y" wording
- Progress panel visibility survives both terminal states (stopped and genuinely finished), not just active runs — fixed a plan-authoring inconsistency where the literally-specified `!complete || stopped` guard would have hidden the panel exactly when "Completed X / Y" needed to be shown
- Per-movie history list now renders 3 visually distinct states (SUCCESS checkmark, NOT_FOUND neutral icon + label, FAILED X) instead of collapsing NOT_FOUND into the FAILED/X state

## Task Commits

Each task was committed atomically:

1. **Task 1: Backend ProgressState.stopped field + real-processed-count complete() (D-04)** - `619c304` (feat)
2. **Task 2: Frontend wiring — type, status text, visibility guard, 3-state history (D-05-D-09)** - `3346895` (feat)

_Both tasks were TDD-tagged (`tdd="true"`); the RED phase for each was covered by first running the existing suite to confirm the two behavior-change assertions failed under the old implementation (see Deviations), then fixing forward to GREEN within the same commit — no separate `test(...)` commit was produced since each task's `<files>` list combines source + test file and the plan's own commit-type table maps this combined change to `feat`._

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java` - `ProgressState` record gains `stopped`; `complete()` reads the stop flag before clearing it and reports the real processed count
- `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java` - Updated all `new ProgressState(...)` call sites and two pre-existing assertions that assumed the old always-`total` behavior; added `requestStop_thenComplete_reportsStoppedTrueAndRealProcessedCount`
- `frontend/composables/useSettings.ts` - `WikiReloadProgress` interface gains `stopped: boolean`
- `frontend/pages/settings.vue` - `wikiStatusLabel` computed, widened progress-panel `v-if` guard, 3-state history icon/label branching, amber status-text color for a stopped run
- `frontend/test/unit/pages/settings.spec.ts` - Added `stopped: false` to all 8 existing `WikiReloadProgress` fixtures; added 3 new tests covering stopped-status-text, completed-status-text, and the NOT_FOUND history icon/label

## Decisions Made
- `ProgressState.stopped` placed as a trailing record field, not mid-record — Jackson serializes records positionally by field name, so appending avoids disturbing every existing construction site's argument order
- `complete()`'s `isStopRequested(userId)` read happens strictly before `stopFlags.remove(userId)` — reading after would always observe a cleared flag, silently reintroducing the exact WR-02 bug this plan fixes; directly asserted by the new backend test
- Widened the progress-panel visibility guard from the plan's literal `!complete || stopped` to `total > 0` (see Deviations below) so both terminal states render their distinguishing text

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Progress-panel `v-if` guard as literally specified would hide "Completed X / Y" exactly when it needs to show**
- **Found during:** Task 2, while writing the "shows 'Completed X / Y' on a genuinely finished run" test the plan itself specifies
- **Issue:** The plan's `<action>` text specifies `v-if="wikiProgress && (!wikiProgress.complete || wikiProgress.stopped)"`. Under this exact formula, a genuinely-finished run (`complete: true, stopped: false`) evaluates the guard to `false`, hiding the panel — so "Completed X / Y" can never actually render in the DOM. This directly contradicts the plan's own `must_haves.truths` ("A genuinely finished run shows 'Completed Y / Y'") and its explicitly-specified acceptance test (b) ("a `{ complete: true, stopped: false, processed: 3, total: 3 }` event shows 'Completed 3 / 3'"), which fails under the literal guard (confirmed by running it — see verification below).
- **Fix:** Changed the guard to `wikiProgress && wikiProgress.total > 0`. This keeps the panel visible during an active run (total > 0, complete: false) AND for both terminal states (stopped or genuinely finished, both total > 0, complete: true) — while still hiding it for the one case that must stay hidden: the zero-progress synthetic placeholder `register()` sends before any real run has ever started (`total: 0`). OR-ing in `wikiProgress.stopped` instead (rather than switching to `total > 0`) would have been wrong: `register()`'s synthesized placeholder itself has `stopped: true` (per the plan's own Task 1 instruction), so that combination would have made the "nothing has ever run" placeholder visible too.
- **Files modified:** `frontend/pages/settings.vue` (guard + explanatory comment)
- **Verification:** Ran `pnpm exec vitest run test/unit/pages/settings.spec.ts test/unit/composables/useSettings.spec.ts` with the literal plan guard first — confirmed the "Completed 3 / 3" test failed (`expected '...' to contain 'Completed 3 / 3'`, panel not rendered). After the fix, all 35 tests pass, `pnpm typecheck` reports no new errors.
- **Committed in:** `3346895` (Task 2 commit)

**2. [Rule 1 - Bug] Two pre-existing backend tests asserted the old always-`total` `complete()` behavior**
- **Found during:** Task 1, running the existing `WikiReloadProgressServiceTest` suite after implementing the real-processed-count fix
- **Issue:** `publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andKeepsEmitterOpen` and `register_afterComplete_replaysRealCompletionState_notSyntheticFallback` both call `complete()` after a `publish(userId, 2, 10, ...)` (only 2 of 10 published) and asserted the resulting state's `processed` equals `10` (the old always-`total` behavior). Under the fix, `complete()` correctly reports the real last-published count (`2`), so both assertions failed.
- **Fix:** Updated both tests' expected `processed` values from `10` to `2`, with an inline comment explaining why (WR-02 fix, Phase 16), and added the trailing `stopped` argument to the `new ProgressState(...)` construction in the first test.
- **Files modified:** `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.admin.WikiReloadProgressServiceTest"` — all 12 tests pass.
- **Committed in:** `619c304` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (2 bugs — one in the plan's own visibility-guard specification, one in pre-existing test assertions that assumed the old, now-corrected, `complete()` behavior)
**Impact on plan:** Both fixes were necessary for the plan's own stated truths and acceptance tests to hold. No scope creep — no files touched beyond the plan's declared `files_modified` list.

### Minor deviation (not a fix — documented for accuracy)

The plan's Task 1 acceptance criterion `grep -c "isStopRequested(userId)" ... returns at least 2` observed a count of 1, not 2. The single exact-substring match is the new read inside `complete()` (`boolean stopped = isStopRequested(userId);`). The method's own declaration (`public boolean isStopRequested(UUID userId)`) does not literally contain the substring `isStopRequested(userId)` (there is a `UUID ` type token between the parenthesis and the parameter name), so it cannot match this grep regardless of implementation. All other acceptance criteria for both tasks pass exactly as specified, and the actual behavioral requirement this criterion was checking for — the ordering of `isStopRequested()` before `stopFlags.remove()` — is independently verified by the ordering-specific criterion (which passes) and by the new backend test.

## Issues Encountered
- The worktree had no `frontend/node_modules` installed (worktrees don't inherit the main checkout's `node_modules`). Ran `pnpm install --frozen-lockfile` inside the worktree before the frontend verification commands could run; this modified nothing in the lockfile (frozen) and added no tracked files (`node_modules` is gitignored) — confirmed via `git status --short` before/after.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Wiki-reload progress UI now correctly distinguishes stopped-vs-completed runs and 3-state per-movie outcomes; this closes the WR-02 gap flagged in 14-REVIEW.md and deferred since Phase 14.
- No blockers for 16-01 or any subsequent plan in this phase — this plan's `depends_on: []` means it had no upstream dependency, and nothing else in the phase depends on it per `.planning/phases/16-bulk-import-correctness-wiki-reload-progress-clarity/16-CONTEXT.md`.

---
*Phase: 16-bulk-import-correctness-wiki-reload-progress-clarity*
*Completed: 2026-08-29*

## Self-Check: PASSED

- FOUND: backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
- FOUND: backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
- FOUND: frontend/composables/useSettings.ts
- FOUND: frontend/pages/settings.vue
- FOUND: frontend/test/unit/pages/settings.spec.ts
- FOUND commit: 619c304 (Task 1)
- FOUND commit: 3346895 (Task 2)
- All acceptance criteria for both tasks re-verified passing (see Deviations section for the one grep-count discrepancy, documented and explained)
- Plan-level `<verification>` commands re-run and green: backend `WikiReloadProgressServiceTest` suite, frontend `settings.spec.ts` + `useSettings.spec.ts`, `pnpm typecheck`
