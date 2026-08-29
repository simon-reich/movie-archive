---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
fixed_at: 2026-08-29T13:09:44Z
review_path: .planning/phases/16-bulk-import-correctness-wiki-reload-progress-clarity/16-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 16: Code Review Fix Report

**Fixed at:** 2026-08-29T13:09:44Z
**Source review:** .planning/phases/16-bulk-import-correctness-wiki-reload-progress-clarity/16-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (fix_scope: critical_warning — WR-01 through WR-04; IN-01 excluded)
- Fixed: 4
- Skipped: 0

**Verification environment:** All backend fixes were compiled and test-verified offline
(`./gradlew --offline compileJava`, `compileTestJava`, and `test --tests
de.moviearchive.bulkimport.BulkImportServiceTest`) inside the isolated review-fix worktree,
which shares the host's Gradle dependency cache. Frontend fixes were verified via Tier 1
(re-read of modified sections) only — the isolated worktree intentionally has no
`frontend/node_modules` installed, so `vitest`/`tsc` could not be run there; the new/updated
Vitest spec (`frontend/test/unit/pages/settings.spec.ts`) should be run in the main checkout
(`pnpm --filter frontend test`) to confirm it passes with real dependencies before this phase
is considered fully verified.

## Fixed Issues

### WR-01: Multi-result / zero-year-match now silently resolves to AMBIGUOUS instead of NOT_FOUND — untested

**Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
**Commit:** 52f5b48
**Applied fix:** Added the regression test suggested by the review
(`shouldMarkAmbiguous_whenMultipleResultsNoneMatchRequestedYear`) that pins down the current
(post-16-01) behavior: two TMDB candidates, neither matching the parsed year, no
`originalTitle` to narrow with — resolves to `AMBIGUOUS`, not `NOT_FOUND`. This is a
test-only fix (no production code changed): the review explicitly flagged this as a possibly
intentional side effect of the "trust title over year" decision (D-10/D-11) that needed
confirmation rather than an unambiguous bug, and its own suggested fix was the test itself.
**Status: fixed: requires human verification** — a developer must confirm that AMBIGUOUS
(rather than NOT_FOUND) is the intended outcome for "multiple results, none matching the
requested year." The test now makes this behavior explicit and enforceable either way; if the
intended behavior turns out to be NOT_FOUND instead, both the test and the corresponding
branch in `BulkImportService.processLine()` (lines ~264-281) need a follow-up change together.

### WR-02: Four now-dead repository finder methods left behind after the CR-01 batch-scoping refactor

**Files modified:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java`
**Commit:** 6372b90
**Applied fix:** Deleted the four confirmed-unused non-batch-scoped finder methods
(`findByUserIdAndNormalizedTitleAndYearAndStatus`, `findByUserIdAndNormalizedTitleAndYear`,
`findByUserIdAndRawLineAndYearIsNull`, `findByUserIdAndNormalizedTitleAndYearIsNull`) — verified
via `grep` across `backend/src/main` and `backend/src/test` that they had zero callers left
(only referenced from Javadoc). Updated the remaining `{@link ...}` Javadoc tags on the
batch-scoped siblings to `{@code ...}` plain-text references to the now-removed method names
(a `{@link}` to a deleted method would otherwise be a dangling reference). Verified with
`./gradlew --offline compileJava` and `compileTestJava` — both compile clean.

### WR-03: settings.vue hides feedback for a genuinely-completed run that had zero eligible movies

**Files modified:** `frontend/pages/settings.vue`, `frontend/test/unit/pages/settings.spec.ts`
**Commit:** bed9008
**Applied fix:** Added a `wikiHasEverRun` ref (per the review's suggested approach) that is set
to `true` only when a real progress/terminal event is observed (`p.total > 0 || (p.complete &&
!p.stopped)`) — the synthetic "never started" placeholder (`stopped: true, total: 0`) never
satisfies this condition. Changed the progress panel's `v-if` guard from `wikiProgress &&
wikiProgress.total > 0` to `wikiProgress && wikiHasEverRun`, so a genuinely-completed run with
zero eligible movies (`processed=0, total=0, complete=true, stopped=false`) is no longer
indistinguishable from the never-started placeholder. Added a regression test
(`shows completion feedback for a genuinely-completed run with zero eligible movies (WR-03)`)
asserting the progress panel becomes visible and shows "Completed 0 / 0" for exactly this
event shape. Not runnable in the isolated worktree (no `frontend/node_modules`) — verified via
Tier 1 re-read only; run `pnpm --filter frontend test` in the main checkout to confirm.

### WR-04: `runImport()` abandons Pass 2 enrichment and never signals completion on thread interruption

**Files modified:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`,
`backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
**Commit:** c001b57
**Applied fix:** Wrapped `runImport()`'s Pass 1 / Pass 2 logic in a `try { ... } finally {
progressService.complete(batchId); }` block, per the review's suggested minimum fix. The two
early `return` statements inside the interrupted-`Thread.sleep` branches were replaced with a
`break` out of their respective loop (plus an `interrupted` flag for Pass 1, so Pass 2 is
skipped when Pass 1 was interrupted) — control now always reaches the `finally` block exactly
once, regardless of which pass gets interrupted or whether the method completes normally. Added
a regression test (`shouldCallComplete_evenWhenPass1SleepIsInterrupted`) that pre-interrupts the
test thread before calling `runImport()` (making `Thread.sleep()` throw immediately regardless
of the configured delay) and asserts `progressService.complete(batchId)` is still invoked.
Verified with `./gradlew --offline compileJava`/`compileTestJava` and
`./gradlew --offline test --tests de.moviearchive.bulkimport.BulkImportServiceTest` — all tests
in the class pass, including the two new ones.

## Skipped Issues

None — all in-scope findings were fixed.

---

_Fixed: 2026-08-29T13:09:44Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
