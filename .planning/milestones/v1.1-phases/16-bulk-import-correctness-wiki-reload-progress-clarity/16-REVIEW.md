---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
reviewed: 2026-08-29T00:00:00Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
  - backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
  - frontend/composables/useSettings.ts
  - frontend/pages/settings.vue
  - frontend/test/unit/pages/settings.spec.ts
findings:
  critical: 0
  warning: 4
  info: 1
  total: 5
status: issues_found
---

# Phase 16: Code Review Report

**Reviewed:** 2026-08-29T00:00:00Z
**Depth:** standard
**Files Reviewed:** 9 (1 test file has no corresponding runtime file requirement, listed above)
**Status:** issues_found

## Summary

Reviewed the CR-01 batch-scoping fix in `BulkImportService`/`BulkImportLineRepository`, the
16-01 multi-stage TMDB matching rework, the WR-02 "stopped vs completed" fix in
`WikiReloadProgressService`, and the corresponding frontend changes in `useSettings.ts` /
`settings.vue`. The diff against `2c21d56^` was inspected directly (not just the final file
state) to separate phase-16-introduced defects from pre-existing code.

The core CR-01 fix (batch-scoping every dedup/upsert lookup) is correctly implemented and well
covered by new tests (`shouldNotReuseRow_acrossDifferentBatchIds`,
`shouldReuseRow_onSameBatchReupload`, the controller-level cross-batch regression test). The
WR-02 `stopped`-flag fix in `WikiReloadProgressService.complete()` correctly reads
`isStopRequested()` before clearing the flag, matching its own bug-history javadoc.

However, the matching-logic rework left a silent, untested behavior change (multi-result +
zero-year-match now resolves to AMBIGUOUS instead of NOT_FOUND), the CR-01 refactor left four
now-dead repository methods that are exact IDOR-relevant near-duplicates of the safe ones (a
foot-gun for future maintainers), and the new "stopped/completed" progress panel introduces a
gap where a genuinely-completed run with zero eligible movies is indistinguishable from — and
hidden the same way as — the "no run has ever started" placeholder, silently swallowing
completion feedback for that case. Details below.

## Warnings

### WR-01: Multi-result / zero-year-match now silently resolves to AMBIGUOUS instead of NOT_FOUND — untested

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:240-282`
**Issue:** Before this phase, when TMDB returned multiple results and *none* of them matched the
parsed year, `yearMatches.isEmpty()` short-circuited to `NOT_FOUND` (see the pre-16-01 diff: the
old code computed `yearMatches` first and mapped an empty result to `NOT_FOUND`). The 16-01
rework restructured this into `results.isEmpty()` → NOT_FOUND, `results.size()==1` → save
regardless of year, `exactMatches` (title-or-originalTitle + year), then a `yearMatches`
fallback narrowed by `originalTitle`. There is no longer any branch that reproduces the old
"multiple results, zero of them match the requested year" → `NOT_FOUND` outcome; that case now
falls through every branch and lands on `AMBIGUOUS` (line 280-281), presenting the user a list of
candidates none of which actually match their year.

This may be an intentional side effect of the "trust title over year" decision (D-10/D-11), but
it is not documented anywhere in the extensive javadoc/inline comments describing the rework, and
it is not covered by a test. The pre-existing test that covered exactly this shape
(`shouldRecordNotFound_whenZeroYearMatchingCandidates`, single result + year mismatch) was
rewritten into `shouldSave_whenSingleResultRegardlessOfYearMismatch`, which asserts the opposite
outcome for a single-result input and no longer exercises the *multi*-result / zero-year-match
case at all. Confirm this AMBIGUOUS-instead-of-NOT_FOUND behavior is actually desired, and add a
test that pins it down explicitly (e.g. two candidates, both with a different year than the
parsed line, no `originalTitle` to narrow with).
**Fix:**
```java
// If this fallthrough-to-AMBIGUOUS is intentional, add a regression test, e.g.:
@Test
void shouldMarkAmbiguous_whenMultipleResultsNoneMatchRequestedYear() {
    when(tmdbClient.search("Old Movie", TMDB_KEY)).thenReturn(List.of(
            item(1, "Old Movie", "Old Movie", 1950),
            item(2, "Old Movie", "Old Movie", 1965)));

    bulkImportService.processLine(EMAIL, TMDB_KEY, "Old Movie;;2010", UUID.randomUUID(), false);

    // Documents the new (post-16-01) outcome explicitly, instead of leaving it implicit.
    verify(movieService, never()).initiate(anyString(), anyInt());
    ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
    verify(bulkImportLineRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.AMBIGUOUS);
}
```

### WR-02: Four now-dead repository finder methods left behind after the CR-01 batch-scoping refactor

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java:18-56`
**Issue:** This phase added batch-scoped siblings for every dedup/upsert lookup
(`findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus`,
`findByUserIdAndBatchIdAndNormalizedTitleAndYear`,
`findByUserIdAndBatchIdAndNormalizedTitleAndYearIsNull`,
`findByUserIdAndBatchIdAndRawLineAndYearIsNull`) specifically because the un-scoped originals
caused the CR-01 cross-batch row-reassignment bug. `BulkImportService` was fully migrated to the
batch-scoped versions — confirmed via `grep`, the four original (non-batch-scoped) methods
(`findByUserIdAndNormalizedTitleAndYearAndStatus`, `findByUserIdAndNormalizedTitleAndYear`,
`findByUserIdAndRawLineAndYearIsNull`, `findByUserIdAndNormalizedTitleAndYearIsNull`) are no
longer called anywhere in `backend/src/main`, only referenced from Javadoc `{@link}` tags.

Leaving these in place is more than ordinary dead-code clutter: they are the *exact* signatures
whose absence-of-batch-scoping caused the bug this phase fixes. A future change that
accidentally calls one of them (e.g. copy-pasting a "find existing row" snippet) silently
reintroduces the CR-01 cross-batch data-isolation bug with no compiler or test signal, since
these compile fine and have no `@Deprecated` marker warning callers away.
**Fix:**
```java
// Delete these four now-unused methods entirely (or, if intentionally kept for some other
// caller not visible in this diff, mark them @Deprecated with a pointer to the batch-scoped
// replacement so a future caller doesn't reach for them by accident):
// - findByUserIdAndNormalizedTitleAndYearAndStatus
// - findByUserIdAndNormalizedTitleAndYear
// - findByUserIdAndRawLineAndYearIsNull
// - findByUserIdAndNormalizedTitleAndYearIsNull
```

### WR-03: settings.vue hides feedback for a genuinely-completed run that had zero eligible movies

**File:** `frontend/pages/settings.vue:513-514`
**Issue:** The progress panel's visibility guard is `v-if="wikiProgress && wikiProgress.total > 0"`.
The comment above it explains this is meant to hide only the *synthetic placeholder* that
`WikiReloadProgressService.register()` sends when no run has ever started
(`processed=0, total=0, complete=true, stopped=true`). But per
`WikiReloadProgressService.start()`'s own javadoc, `start(userId, total)` is called
unconditionally "right after computing the eligible list, before the ... Wikidata SPARQL prefetch
call" — i.e. even when the eligible list is empty (`total == 0`). In that case the run
immediately calls `complete(userId)`, producing a *real* terminal state
(`processed=0, total=0, complete=true, stopped=false`) that is structurally indistinguishable
from the synthetic never-started placeholder, and is hidden by the exact same `total > 0` guard.

A user who clicks "Reload missing Wikipedia data" when every movie already has Wikipedia data
sees "Reload started — this runs in the background..." and then no further feedback at all — no
progress bar, no "Completed 0 / 0", nothing — even though the run genuinely started and finished.
There is no test covering `total === 0` with `complete === true` to catch this.
**Fix:** Distinguish "never started" from "genuinely completed with nothing to do" — e.g. track
whether a `start`/`progress` event has ever been observed client-side, or have the backend send a
distinguishable payload (e.g. a `hasRun` flag, or keep `stopped: true` reserved exclusively for
the synthetic placeholder and use it as the discriminator instead of `total`):
```ts
// settings.vue
const wikiHasEverRun = ref(false)
// inside subscribeToWikiReloadProgress's onProgress callback:
if (p.total > 0 || (p.complete && !p.stopped)) wikiHasEverRun.value = true
```
```html
<div v-if="wikiProgress && wikiHasEverRun" data-testid="wiki-reload-progress" ...>
```

### WR-04: `runImport()` abandons Pass 2 enrichment and never signals completion on thread interruption

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:110-118, 134-142`
**Issue:** If `Thread.sleep(pacingDelayMs)` is interrupted during Pass 1 (e.g. executor shutdown),
`runImport()` re-interrupts the thread and `return`s immediately — before Pass 2 ever runs and
before `progressService.complete(batchId)` is called. Any movie already matched and inserted in
Pass 1 up to that point (`matchedMovieIds`) is silently abandoned: it never receives its TMDB
detail / OMDB enrichment call, and there is no retry path for it afterward. Additionally, because
`complete()` is never called, any SSE subscriber watching this batch's progress is left frozen on
the last `publish()`'d state forever. This is a pre-existing pattern (mirrored from
`WikiReloadService`) rather than new in this phase, but it lives in a file under review here and
represents a real (if narrow-precondition) data-loss / stuck-progress risk.
**Fix:** At minimum, call `progressService.complete(batchId)` in a `finally` block so subscribers
are always released from the "in progress" state, even on early return:
```java
@Async("bulkImportExecutor")
public void runImport(...) {
    try {
        // existing Pass 1 / Pass 2 logic
    } finally {
        progressService.complete(batchId);
    }
}
```
(Note the two early `return` statements inside the interrupted-sleep branches would need to be
removed/adjusted so the `finally` block is reached exactly once, or the `complete()` call moved
into a small helper invoked from both the normal end-of-method path and the interrupted-sleep
branches.)

## Info

### IN-01: Synthetic "no run has ever started" ProgressState sets `stopped: true`, which is semantically wrong

**File:** `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java:94`
**Issue:** `register()`'s synthetic placeholder for "no `lastKnown` state exists yet" is
constructed as `new ProgressState(0, 0, true, null, null, 0L, true)` — `stopped: true`. Nothing
was ever started, so nothing was "stopped"; this is a placeholder for the absence of any run.
Currently harmless because the frontend's `total > 0` guard hides this state regardless of the
`stopped` value (see WR-03), but the value is semantically incorrect and would surface if that
guard is ever changed (e.g. per the WR-03 fix above, which would need to explicitly exclude this
synthetic case rather than relying on `stopped`/`total` coincidentally hiding it).
**Fix:** Use `false` for the synthetic placeholder's `stopped` field, since it represents "no run,"
not "a stopped run":
```java
ProgressState synthesized = new ProgressState(0, 0, true, null, null, 0L, false);
```

---

_Reviewed: 2026-08-29T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
