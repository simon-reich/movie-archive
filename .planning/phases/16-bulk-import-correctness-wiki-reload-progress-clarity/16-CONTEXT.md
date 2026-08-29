# Phase 16: Bulk Import Correctness & Wiki-Reload Progress Clarity - Context

**Gathered:** 2026-08-29
**Status:** Ready for planning

<domain>
## Phase Boundary

Folds three deferred/newly-decided quality-debt items into v1.1 before it closes:

1. **Cross-batch dedup bug fix** — `BulkImportService.findExistingRow()` (and the sibling
   `existingSaved` fast-path in `processLine()`) currently scope row-reuse lookups by
   `(userId, normalizedTitle, year)` only, not by `batchId`. A title/year overlapping across two
   unrelated batches silently reassigns/skips a row instead of each batch staying an independent
   snapshot of its own import run.
2. **Wiki-reload progress UI clarity** — `WikiReloadProgressService.complete()` always reports
   `(total, total, true, ...)` regardless of whether a run was stopped early via the Stop button,
   so `settings.vue`'s panel makes a stopped run look indistinguishable from (and then, once
   `complete` is true, simply vanishes like) a fully finished one. Additionally, the per-movie
   history list collapses 3 real backend outcomes (SUCCESS/NOT_FOUND/FAILED) into 2 displayed
   states, hiding whether "no Wikipedia article exists" (expected, not an error) happened vs an
   actual fetch failure.
3. **Multi-stage TMDB matching rework** — `BulkImportService.processLine()`'s automatic TMDB
   matching changes from "filter to year-matches first, NOT_FOUND if a single year-mismatched
   result exists" to: search by title only; a single overall result is taken directly (no year
   check — unambiguous); multiple results narrow to an exact title+year match; if that still
   doesn't produce a single match, fall back to the existing original-title narrowing; otherwise
   AMBIGUOUS.

This is the last phase before v1.1 (Enrichment Reliability & Bulk Import) can close.

</domain>

<decisions>
## Implementation Decisions

### Dedup fix scope (item 1)
- **D-01:** `findExistingRow()` is scoped by `batchId` in addition to `(userId, normalizedTitle, year)` (and the `rawLine`-based fallback for null-year rows) — CR-01's fix, matches the pending todo's specified solution exactly. Each batch becomes a fully independent snapshot; no cross-batch row reuse or reassignment. — **Reversibility:** reversible
- **D-02:** The `existingSaved` fast-path in `processLine()` (currently: skip entirely, no TMDB call, no write, if a title/year is already `SAVED` in ANY batch) also becomes batch-scoped. A title/year already `SAVED` in an older batch is treated as unseen for a new batch. — **Reversibility:** reversible
- **D-03:** When such a line is re-processed for the new batch: run the full match pipeline and call `movieService.initiate()` again (idempotent by `tmdbId` — finds the existing `Movie` row, creates no duplicate) and upsert a `SAVED` row scoped to the new batch. No separate cross-batch `SAVED` lookup or TMDB-call-avoidance optimization — simplicity over saving one redundant TMDB search per re-import collision. — **Reversibility:** reversible

### Stopped vs Completed UI (item 2)
- **D-04:** `WikiReloadProgressService.ProgressState` gains a `stopped` field (per the existing `2026-08-27-distinguish-stopped-vs-completed-in-progress-ui.md` todo). `complete()` reports the real last-published `processed` count instead of always reporting `total`, and sets `stopped` from `progressService.isStopRequested(userId)` (checked before `stopFlags` is cleared). — **Reversibility:** reversible
- **D-05:** Status text above the progress bar in `settings.vue` distinguishes the two terminal states via wording, e.g. "Stopped at 12 / 40" vs "Completed 40 / 40" — bar and history-list layout otherwise unchanged. — **Reversibility:** reversible
- **D-06:** `settings.vue`'s `v-if="wikiProgress && !wikiProgress.complete"` guard (currently hides the entire progress panel including history once `complete` is true) changes so the panel/history stays visible after a **stopped**-terminal event too, not just while a run is actively in-progress. — **Reversibility:** reversible
- **D-07:** The "Reload missing Wikipedia data" button re-enables as soon as the stopped-terminal SSE event arrives — same trigger point as today's completed-based re-enable, no new disabled-state logic needed. — **Reversibility:** reversible
- **D-08:** Clicking "Reload" again clears the previous run's panel/history immediately on click — extends the existing `wikiMovieHistory.value = []` clear-on-start logic (WR-03) to also fire when the prior terminal state was `stopped`, not only when it was `complete`. — **Reversibility:** reversible
- **D-09:** The per-movie history list shows 3 distinct icons/labels instead of 2, threading the real backend `WikiRetryOutcome` value (`SUCCESS`/`NOT_FOUND`/`FAILED`) through instead of collapsing to a SUCCESS/FAILED binary in the frontend: checkmark for `SUCCESS` ("data found & saved"), a neutral icon + "No Wikipedia article found" label for `NOT_FOUND` (expected outcome, not an error), X for `FAILED` (real fetch error). — **Reversibility:** reversible

### Multi-stage TMDB matching details (item 3)
- **D-10:** The existing original-title narrowing tiebreaker (Phase 10 D-06: when multiple title+year candidates remain, narrow further by comparing `parsed.originalTitle()` against TMDB's `originalTitle` field) survives as a fallback step, applied only after the exact title+year narrowing fails to produce a single match and before falling back to `AMBIGUOUS`. — **Reversibility:** reversible
- **D-11:** "Exact title match" in the title+year narrowing step is case-insensitive and checked against **either** TMDB's `title` field or its `originalTitle` field (not `title` only) — a match counts if `parsed.title()` equals either one, case-insensitively. Chosen over title-field-only because it also catches cases where the user typed a film's original title but TMDB's localized display title differs. — **Reversibility:** reversible
- **D-12:** TMDB's title search returning **zero results** (distinct from "multiple results, none an exact match") still maps to `NOT_FOUND`, not `AMBIGUOUS` — matches today's behavior for the true no-match case. — **Reversibility:** reversible
- **D-13 (unchanged, carried forward):** `TmdbClient.search()` is already title-only (no year parameter) — no change needed to the search call itself, only to how `processLine()` interprets and narrows its results.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Source todos (fully folded into this phase's scope)
- `.planning/todos/pending/2026-08-28-fix-cross-batch-line-reassignment-in-bulk-import-dedup.md` — CR-01 dedup fix; specifies the `findExistingRow()` batchId-scoping solution directly (D-01–D-03 extend it to also cover the `existingSaved` fast-path).
- `.planning/todos/pending/2026-08-27-distinguish-stopped-vs-completed-in-progress-ui.md` — WR-02 stopped-vs-completed fix; specifies the `stopped` field, real-processed-count reporting, and `v-if` guard change directly (D-04–D-08).

### Root cause / review context
- `.planning/phases/15-bulk-import-page-completion-view-toggle-movie-links-real-csv/15-REVIEW.md` §CR-01 (lines 55-90) — full data-integrity writeup of the cross-batch reassignment bug, including the existing test (`shouldReturn404_whenResolvingLineFromDifferentBatch`) that works around it via `deleteAll()` rather than exercising it as intended behavior.
- `.planning/phases/14-wiki-batch-reload-pacing-cooldown-fix-progress-ui/14-REVIEW.md` §WR-02 (lines 122-137) — original finding and suggested `ProgressState` schema change.

### Phase 10 foundation (bulk-import engine — matching pipeline, still binding except where D-01–D-12 supersede it)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` — `processLine()` (matching pipeline being reworked, D-10–D-12), `findExistingRow()`/`upsertLine()` (dedup fix, D-01–D-03), `runImport()` (Pass 1/Pass 2 orchestration, unaffected)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` — needs new batch-scoped query methods per D-01/D-02 (e.g. `findByUserIdAndBatchIdAndNormalizedTitleAndYear` and batch-scoped equivalents of the other lookup variants)
- `.planning/phases/10-bulk-import-engine/10-CONTEXT.md` — D-04 ("never auto-guess on ambiguity") remains a hard invariant, unchanged by D-10–D-12's rework; D-06 (original-title narrowing) is the tiebreaker being preserved as a fallback per D-10

### Wiki-reload progress foundation (Phase 8/13/14 — still binding except where D-04–D-09 supersede it)
- `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java` — `ProgressState` record (schema change, D-04), `complete()` (real-processed-count + `stopped` field, D-04), `isStopRequested()` (existing, read before `stopFlags` is cleared)
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — `WikiRetryOutcome` enum (`SUCCESS`/`NOT_FOUND`/`FAILED`, line 55) and `batchReload()`'s `progressService.publish(...)` call (line 241) — already passes the real per-movie `status` string; D-09 is a frontend-side change to stop collapsing it
- `frontend/pages/settings.vue` — `v-if` guard (D-06), progress-panel status text (D-05), Reload button disabled-state (D-07), `wikiMovieHistory` clear-on-start logic (D-08), per-movie history list icon/label rendering (D-09)
- `frontend/composables/useSettings.ts` — `WikiReloadProgress` type needs extending with the `stopped` field (D-04) to match the backend schema change
- `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java` — existing equality-based assertions on `ProgressState` will need updating for the new `stopped` field (flagged in the source todo as the reason this was deferred out of Phase 14's review-pass fixes)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BulkImportController.loadOwnedBatch()` — existing ownership-check pattern; batch-scoped repository queries (D-01/D-02) follow the same `(userId, batchId)` scoping shape already established there
- `movieService.initiate()` — already idempotent by `tmdbId`; directly relied on by D-03 to make "re-run full match for a new batch" safe with no duplicate `Movie` rows
- `TmdbClient.search(query, apiKey)` — already title-only; no signature change needed for the matching rework (D-10–D-12)

### Established Patterns
- "Never auto-guess on ambiguity" (Phase 10 D-04) — hard invariant, carried forward unchanged through the matching rework
- Per-line/per-movie failure isolation (bulk-import D-03, wiki-reload's per-movie try/catch) — both existing loops already isolate one bad item from aborting the whole run; neither item 1 nor item 2 changes this
- SSE terminal-state broadcasting (`WikiReloadProgressService.broadcast()`) — D-04's `stopped` field flows through the existing broadcast/`lastKnown` mechanism, no new transport needed

### Integration Points
- New batch-scoped repository query methods on `BulkImportLineRepository` (D-01/D-02) — additive, existing non-batch-scoped methods likely become dead code once callers switch, but check for other callers before removing
- `ProgressState` record signature change (add `stopped: boolean`) — a source-breaking change for every existing test asserting on the record's shape; the source todo explicitly flags this as the reason it wasn't a quick review-pass patch
- Frontend `WikiReloadProgress` TS type (`useSettings.ts`) and `settings.vue` template both need the new field threaded through together

</code_context>

<specifics>
## Specific Ideas

- User's own words on the per-movie status gap (surfaced during this discussion, not from a prior todo): "Ich würde gerne noch sehen können bei dem Laden der Daten... Eine Filme ist auch dann erfolgreich durchlaufen worden, wenn es gar keine Wikipedia-Daten gab. Ich würde gerne in dieser Liste auch sehen können, ob Daten gefunden wurden und gespeichert wurden oder nicht." — directly drove D-09; this was not in the original WR-02 todo's scope but is closely related (same panel, same history list) and the user raised it unprompted while discussing the stop/complete area.
- User confirmed the original-title narrowing tiebreaker (Phase 10 D-06) should NOT be dropped by the new multi-stage algorithm, even though their 2026-08-29 phrasing (as captured in ROADMAP.md) didn't explicitly mention it — it stays as a fallback before AMBIGUOUS (D-10).

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. D-09 (3-state per-movie status display) is a scope *clarification* within the already-planned wiki-reload progress UI item, not a new capability.

### Reviewed Todos (not folded)
- **`.planning/todos/pending/2026-08-28-create-api-contract-doc-for-future-flutter-port.md`** — project-wide documentation todo, not phase-specific. Not folded (consistent with Phase 15's same assessment of this todo).

</deferred>

---

## UAT Gap-Closure Decisions (added 2026-08-29, post-execution)

- **G-16-3 (wiki-reload infinite-retry conflict):** Debug session `.planning/debug/16-notfound-icon-shows-checkmark.md` found `MovieRepository.findEligibleForWikiReload` and `WikiReloadService.WikiRetryOutcome` use two conflicting "found" definitions (content-extracted vs. page-exists), causing 41/305 movies with a genuinely-found-but-content-incomplete Wikipedia page to be retried forever. User confirmed (via AskUserQuestion) to fix this as a Phase 16 gap rather than defer it to a separate backlog item. Of the three fix directions the debug session proposed, the simplest-correct one was selected: key `findEligibleForWikiReload` off `wiki_url IS NULL` instead of `wiki_plot IS NULL AND wiki_critics IS NULL`. Accepted trade-off: these movies' detail pages permanently show "no Wikipedia data" once a page is found but content extraction fails — no retry. Broadening `WikipediaClient`'s section-name allowlist (option c) and a distinct "content incomplete" status (option b) were explicitly scoped out as future work. Implemented in `16-04-PLAN.md`.

---

*Phase: 16-bulk-import-correctness-wiki-reload-progress-clarity*
*Context gathered: 2026-08-29*
