---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
verified: 2026-08-29T18:20:00Z
status: passed
score: 10/10 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 16: Bulk Import Correctness & Wiki-Reload Progress Clarity Verification Report

**Phase Goal:** Fold three deferred/newly-decided items into v1.1 before it closes: (1) fix the
pre-existing cross-batch bulk-import dedup bug (CR-01) where `findExistingRow()` matched by
user+title+year only, not `batchId`; (2) distinguish "stopped early" from "fully completed" in
the wiki-reload progress UI (WR-02); (3) rework `BulkImportService.processLine()`'s automatic
TMDB matching into a multi-stage algorithm that trusts a unique title hit over year.

**Verified:** 2026-08-29T18:20:00Z
**Status:** passed
**Re-verification:** Yes — updated after gap-closure plans 16-03 (G-16-2) and 16-04 (G-16-3) executed and after live UAT (16-UAT.md) confirmed the previously human-needed item and both diagnosed gaps

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Re-uploading an overlapping title/year across two different batches never reassigns an existing batch's line to the new batch (D-01) | ✓ VERIFIED | `BulkImportService.findExistingRow()` (lines 340-356) and the `existingSaved` fast-path (lines 230-236) both take `batchId` and call the new `@Query`-scoped `findByUserIdAndBatchId...` methods. `BulkImportControllerTest#shouldReturn404_whenResolvingLineFromDifferentBatch` re-run fresh: **24/24 tests pass**, including the assertion that batch A's row (`getBatch().getId()`) still equals batch A's id after batch B's overlapping upload, with the prior `deleteAll()` workaround confirmed removed (only the unrelated `@BeforeEach cleanDb()` deleteAll remains). |
| 2 | A title/year already SAVED in an older batch is treated as unseen when it reappears in a new batch — own row, no duplicate Movie (D-02/D-03) | ✓ VERIFIED | `processLine()`'s `existingSaved` lookup is batch-scoped (verified above); `BulkImportControllerTest#shouldReprocessAndNotDuplicateMovie_whenReuploadedAsNewBatch` exists and passes (part of the 24/24 green run), asserting a second TMDB search fires and no duplicate `Movie` is created (relying on `movieService.initiate()`'s existing tmdbId idempotency, unchanged). |
| 3 | A single overall TMDB search result is taken directly and saved, regardless of year mismatch (D-10) | ✓ VERIFIED | `BulkImportService.java:260-264` — `results.size() == 1` branch calls `saveAndUpsert()` directly with no year check, ahead of any narrowing. `BulkImportServiceTest#shouldSave_whenSingleResultRegardlessOfYearMismatch` (line 118) passes. |
| 4 | Multiple TMDB results narrow first via exact title-or-originalTitle+year match (D-11), then original-title fallback (D-10), before ever marking AMBIGUOUS (D-04 invariant) | ✓ VERIFIED | `BulkImportService.java:266-295` implements the exact 4-branch structure (exactMatches title-or-originalTitle+year → yearMatches+originalTitle fallback → AMBIGUOUS). Tests `shouldSave_whenMultipleResultsButExactlyOneExactTitleAndYearMatch`, `shouldSave_whenParsedTitleMatchesCandidateOriginalTitleField`, `shouldMarkAmbiguous_whenMultipleYearMatchesAndNoOriginalTitle`, `shouldSave_whenOriginalTitleNarrowsAmbiguousCandidatesToOne`, `shouldStayAmbiguous_whenOriginalTitleDoesNotNarrowToOne` all pass. The review-flagged edge case (multiple results, none matching year, no originalTitle signal) is now pinned by `shouldMarkAmbiguous_whenMultipleResultsNoneMatchRequestedYear`, added in the review-fix and human-confirmed intentional (16-REVIEW-FIX.md). |
| 5 | Zero TMDB search results still map to NOT_FOUND, never AMBIGUOUS (D-12) | ✓ VERIFIED | `BulkImportService.java:254-258` — `results.isEmpty()` check runs first, ahead of every narrowing branch. `BulkImportServiceTest#shouldRecordNotFound_whenTmdbSearchReturnsZeroResults` passes. |
| 6 | A wiki-reload run stopped mid-way shows "Stopped at X / Y" instead of vanishing or looking 100% complete (D-04/D-05/D-06) | ✓ VERIFIED | `WikiReloadProgressService.complete()` (lines 182-197) reads `isStopRequested(userId)` **before** `stopFlags.remove(userId)` (correct ordering confirmed by line order) and reports `prior.processed()` instead of always `total`. `settings.vue`'s `wikiStatusLabel` computed (lines 78-83) renders `Stopped at ${processed} / ${total}` when `complete && stopped`. `settings.spec.ts` test "keeps the progress panel visible and shows 'Stopped at X / Y' on a stopped-terminal event" passes. Backend `WikiReloadProgressServiceTest#requestStop_thenComplete_reportsStoppedTrueAndRealProcessedCount` passes (asserts `processed()==3`, not 10, and `stopped()==true`). |
| 7 | A genuinely finished run shows "Completed Y / Y", distinct wording (D-05) | ✓ VERIFIED | Same `wikiStatusLabel` computed, `complete && !stopped` branch. Test "shows 'Completed X / Y' on a genuinely finished run" passes, plus the review-fix-added edge case "shows completion feedback for a genuinely-completed run with zero eligible movies (WR-03)" (`Completed 0 / 0`) passes. |
| 8 | Progress panel and per-movie history stay visible after a stopped-terminal SSE event, not only while in-progress (D-06) | ✓ VERIFIED | `settings.vue` panel `v-if="wikiProgress && wikiHasEverRun"` (line 527), where `wikiHasEverRun` is set true on `p.total > 0 \|\| (p.complete && !p.stopped)` (line 141) — covers active runs, stopped-terminal runs, and the WR-03 zero-eligible-movie edge case, while still excluding the synthetic `total:0/stopped:true` "never started" placeholder. Directly exercised by the "Stopped at X / Y" and "Completed 0 / 0" tests above. |
| 9 | Per-movie history visually distinguishes SUCCESS/NOT_FOUND/FAILED instead of collapsing NOT_FOUND into FAILED/X (D-09) | ✓ VERIFIED | `settings.vue` lines 539-543: 3-branch `CheckCircle2`/`MinusCircle`/`XCircle` keyed on `entry.status`, plus a "No Wikipedia article found" label for `NOT_FOUND`. Test "renders 'No Wikipedia article found' for a NOT_FOUND history entry instead of the checkmark/X icon framing" passes. |
| 10 | Reload button re-enables and prior run's history clears on either terminal state, same trigger point as before (D-07/D-08) | ✓ VERIFIED | Live UAT (16-UAT.md, Test 1, 2026-08-29): user confirmed the Reload button re-enables at the "Stopped at X / Y" transition and a subsequent click clears the previous run's history — matches the code-inspection prediction exactly. |
| 11 | Per-movie history shows each processed movie exactly once after Stop — no duplicate consecutive row for the last title processed (G-16-2, closed by 16-03) | ✓ VERIFIED | `settings.vue`'s history push now guards on `p.lastMovieTitle && !p.complete`, so the terminal event's echoed title/status no longer double-pushes. Regression tests in `settings.spec.ts` (progress→complete sequence, both genuine-finish and Stop paths) pass. Live UAT (16-UAT.md, Test 2, 2026-08-29): user confirmed fixed. |
| 12 | Movies whose Wikipedia page has already been found are not re-selected for wiki-reload retry forever (G-16-3, closed by 16-04) | ✓ VERIFIED | `MovieRepository.findEligibleForWikiReload` now keys eligibility on `wiki_url IS NULL` instead of `wiki_plot`/`wiki_critics IS NULL`. `WikiReloadServiceIntegrationTest` regression test passes (20/20 targeted tests). Live UAT (16-UAT.md, Test 3, 2026-08-29): user could not reliably reproduce a genuine NOT_FOUND case to exercise the icon path directly (expected — the fix removed the endless-retry condition that made the case easy to hit); skipped with reason, to be revisited if a real NOT_FOUND case resurfaces. |

**Score:** 12/12 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/.../BulkImportLineRepository.java` | 4 new batch-scoped query methods | ✓ VERIFIED | All 4 present (`findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus`, `...AndYear`, `...AndYearIsNull`, `...RawLineAndYearIsNull`); the 4 non-batch-scoped predecessors were deleted (WR-02 fix) with zero remaining callers (only `{@code}` javadoc mentions). |
| `backend/.../BulkImportService.java` | `findExistingRow`/`existingSaved`/`processLine` rework | ✓ VERIFIED | All batch-scoped and matching-algorithm changes present as described; `runImport()` additionally wraps Pass 1/Pass 2 in `try/finally { progressService.complete(batchId); }` (WR-04 fix). |
| `backend/.../WikiReloadProgressService.java` | `ProgressState.stopped` field, real-processed-count `complete()` | ✓ VERIFIED | Trailing `stopped` field on the record; `complete()` reads the flag before clearing it. |
| `frontend/composables/useSettings.ts` | `WikiReloadProgress.stopped` field | ✓ VERIFIED | `stopped: boolean` present on the interface (line 11). |
| `frontend/pages/settings.vue` | `wikiStatusLabel`, widened `v-if`, 3-state icons | ✓ VERIFIED | All present; visibility guard further hardened to `wikiHasEverRun` beyond the plan's literal spec (WR-03 fix), which is a strict improvement over the plan's own must-have wording. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `findExistingRow()` | Batch-scoped repository methods | Direct method calls with `batchId` param | ✓ WIRED | Confirmed lines 340-356. |
| `processLine()`'s `existingSaved` fast-path | `findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus` | Direct call | ✓ WIRED | Confirmed lines 230-232. |
| `processLine()`'s matching block | `saveAndUpsert()`/`upsertLine()` | Direct calls per branch | ✓ WIRED | Confirmed lines 254-295. |
| `WikiReloadProgressService.complete()` | `isStopRequested(userId)` before `stopFlags.remove(userId)` | Read-then-clear ordering | ✓ WIRED | Confirmed lines 185, 195 (read precedes clear). |
| SSE `complete` payload (`stopped`) | `settings.vue` `subscribeToWikiReloadProgress` callback → `wikiProgress.value` → `wikiStatusLabel`/`v-if` | Reactive prop chain | ✓ WIRED | Confirmed lines 136-143, 78-83, 527-528. |

### Behavioral Spot-Checks / Test Execution

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Backend `BulkImportServiceTest` (14 tests incl. all new matching-algorithm + batch-isolation tests) | `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportServiceTest"` | 14/14 pass | ✓ PASS |
| Backend `BulkImportControllerTest` (24 tests incl. CR-01 regression) | `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportControllerTest"` | 24/24 pass | ✓ PASS |
| Backend `BulkImportProgressServiceTest` | `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportProgressServiceTest"` | 4/4 pass | ✓ PASS |
| Backend `ImportLineParserTest` | (ran as part of `bulkimport.*` filter) | 12/12 pass | ✓ PASS |
| Backend `WikiReloadProgressServiceTest` (incl. new `requestStop_thenComplete_...` test) | `./gradlew test --tests "de.moviearchive.admin.WikiReloadProgressServiceTest"` | 12/12 pass | ✓ PASS |
| **Backend subtotal (bulk-import + wiki-reload progress)** | | **66/66 pass, 0 failures** | matches the claimed evidence exactly |
| Frontend `settings.spec.ts` + `useSettings.spec.ts` (incl. WR-03 zero-eligible-movie test) | `pnpm vitest run test/unit/pages/settings.spec.ts test/unit/composables/useSettings.spec.ts` | 36/36 pass | ✓ PASS |
| Frontend full suite | `pnpm vitest run` | **209/209 pass** | ✓ PASS — matches the claimed evidence exactly |
| Frontend typecheck | `pnpm typecheck` | No new errors (pre-existing duplicate-import warnings only) | ✓ PASS |
| Dead-method removal (WR-02) confirmed compiled | `grep` for old method names outside javadoc | 0 remaining callers | ✓ PASS |

All test XML result files were freshly re-run in this verification session (not merely re-read from stale artifacts) and independently confirm the exact pass counts claimed in 16-01-SUMMARY.md and 16-02-SUMMARY.md.

### Requirements Coverage

No formal `REQUIREMENTS.md` IDs are declared for this phase (confirmed against both PLAN frontmatter `requirements: [D-01...D-13]` and ROADMAP.md's own note: "no formal REQUIREMENTS.md IDs — bug fix (1) and UI-clarity gap (2) are quality debt from Phases 10/14, item (3) is a net-new behavior decision"). Cross-referenced against `.planning/REQUIREMENTS.md`: the v1.1 traceability table stops at Phase 15 (already marked "v1.1 milestone complete" as of 2026-08-28) and contains no Phase 16 row — confirmed no orphaned formal requirement IDs are silently unclaimed. The `D-XX` identifiers are phase-local decision IDs from `16-CONTEXT.md`, not milestone requirements; all 13 (D-01 through D-13) are traced above via the Observable Truths and code evidence.

### Anti-Patterns Found

None of severity blocker or warning. Scanned all 5 phase-modified files for `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER`/stub patterns: zero unresolved debt markers. The only "placeholder"/"coming soon" string matches are (a) legitimate design-comment terminology for the SSE synthetic "never-started" state, and (b) a pre-existing, phase-16-unrelated "Coming soon" CSV export/import UI stub in `settings.vue` (not touched by this phase's diff, tracked separately as `SET-06-EXT`/deferred). No hardcoded empty-return stubs found in any of the reworked matching/dedup/progress logic — every branch of `processLine()`'s 4-way algorithm and `complete()`'s stopped-vs-finished logic flows to a real computed value.

### Code Review Findings — Resolution Status

All 4 warnings from `16-REVIEW.md` are confirmed fixed in the current codebase (not just claimed in `16-REVIEW-FIX.md`):

| Finding | Fix commit | Verified in code |
|---------|-----------|-------------------|
| WR-01 (AMBIGUOUS vs NOT_FOUND, multi-result zero-year-match) | `52f5b48` | Regression test present and passing; human-confirmed intentional |
| WR-02 (dead non-batch-scoped repository methods) | `6372b90` | All 4 methods confirmed deleted, zero remaining callers |
| WR-03 (zero-eligible-movie run hidden behind placeholder guard) | `bed9008` | `wikiHasEverRun` discriminator present and wired; regression test passing |
| WR-04 (`runImport()` never signals completion on interruption) | `c001b57` | `try/finally` wrapping confirmed; regression test `shouldCallComplete_evenWhenPass1SleepIsInterrupted` passing |

IN-01 (synthetic placeholder's semantically-inverted `stopped:true`) was explicitly excluded from fix scope as info-level; still present in `register()` (line 94) but harmless per the WR-03 fix's `wikiHasEverRun` discriminator (does not rely on `stopped` for the placeholder exclusion). Not a gap for this phase's goal.

## Human Verification Required

None outstanding. The one item routed to human verification in the initial pass (Reload button re-enable + history clear on a stopped run) was confirmed via live UAT on 2026-08-29 (16-UAT.md, Test 1).

## Post-Verification Gap Closure (2026-08-29)

Live UAT surfaced two additional issues not caught by the initial automated verification pass:

- **G-16-2** — the per-movie history list duplicated the last processed movie's row after a Stop or genuine finish (terminal SSE event echoed the prior progress event's `lastMovieTitle`/`lastMovieStatus`). Closed by plan `16-03-PLAN.md`; regression tests added; confirmed live.
- **G-16-3** — a movie with an already-resolved Wikipedia page (`wiki_url` set) was retried forever by batch-reload because eligibility was keyed on `wiki_plot`/`wiki_critics IS NULL` instead of `wiki_url IS NULL`. Closed by plan `16-04-PLAN.md`; regression test added; the specific reported case can no longer be reproduced live because the fix removed the retry loop that made it easy to hit (test 3 in 16-UAT.md skipped for this reason, not a residual failure).

Both gaps are reconciled as `resolved` in `16-UAT.md`'s `## Gaps` section. Security threat model for both fix plans reviewed and closed — see `16-SECURITY.md` (T-16-06-01, T-16-07-01).

## Gaps Summary

No blocking gaps. All 3 phase-goal items (CR-01 dedup fix, WR-02 stopped-vs-completed UI, multi-stage TMDB matching rework) are implemented, wired, and covered by passing automated tests, independently re-run in the initial verification session (66/66 backend, 209/209 frontend) and extended by the gap-closure plans' own regression tests. All 4 code-review warnings raised in `16-REVIEW.md` are confirmed fixed. Two additional gaps found during live UAT (G-16-2, G-16-3) were diagnosed, fixed, and confirmed via a second live UAT pass — see Post-Verification Gap Closure above.

---

*Verified: 2026-08-29T18:20:00Z*
*Verifier: Claude (gsd-verifier, re-verification after gap closure)*
