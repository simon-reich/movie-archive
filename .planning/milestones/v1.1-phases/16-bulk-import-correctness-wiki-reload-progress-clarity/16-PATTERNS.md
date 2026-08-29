# Phase 16: Bulk Import Correctness & Wiki-Reload Progress Clarity - Pattern Map

**Mapped:** 2026-08-29
**Files analyzed:** 8 (all modified — no new files this phase)
**Analogs found:** 8 / 8 (all self-modifications; every file's own current code is its own strongest analog for the surrounding style)

This phase touches no brand-new files — every target file is an existing file being modified
in place. Patterns below are drawn from adjacent code *within the same file* (the dominant
convention to extend) plus one true cross-file analog (`BulkImportController.loadOwnedBatch()`
→ scoping shape for new repository queries).

## File Classification

| File to Modify | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/.../bulkimport/BulkImportService.java` (`findExistingRow`, `processLine`, dedup fast-path) | service | CRUD | same file's existing `findExistingRow`/`upsertLine`/`processLine` methods | exact (self) |
| `backend/.../bulkimport/BulkImportLineRepository.java` (new batch-scoped queries) | model/repository | CRUD | same file's existing `@Query`/derived-method pairs; scoping shape from `BulkImportController.loadOwnedBatch()` | exact (self) + role-match |
| `backend/.../admin/WikiReloadProgressService.java` (`ProgressState`, `complete()`) | service | event-driven (SSE) | same file's existing `ProgressState` record + `complete()`/`publish()` | exact (self) |
| `backend/.../enrichment/WikiReloadService.java` (no code change expected — outcome already threaded; verify only) | service | event-driven | same file — `batchReload()` already passes real `status` string via `progressService.publish()` | exact (self), likely no diff needed |
| `frontend/pages/settings.vue` (`v-if` guard, status text, button re-enable, history clear, icon/label per entry) | component | request-response + SSE consumer | same file's existing wiki-reload section (script + template) | exact (self) |
| `frontend/composables/useSettings.ts` (`WikiReloadProgress` type) | utility/composable (SSE client) | streaming | same file's existing `WikiReloadProgress` interface | exact (self) |
| `backend/.../test/admin/WikiReloadProgressServiceTest.java` (update record-equality assertions) | test | — | same file's existing equality assertions (lines 100-105) | exact (self) |
| New/updated tests for `BulkImportService`/`BulkImportLineRepository` batch scoping | test | — | existing `shouldReturn404_whenResolvingLineFromDifferentBatch` test referenced in 15-REVIEW.md §CR-01 | role-match |

## Pattern Assignments

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`

**Analog:** itself — extend the existing `findExistingRow()`/`upsertLine()`/`processLine()` methods in place.

**Current dedup lookup to batch-scope** (`findExistingRow`, lines 297-311):
```java
private Optional<BulkImportLine> findExistingRow(UUID userId, ParsedLine parsed) {
    if (parsed.year() != null) {
        Optional<BulkImportLine> byTitleAndYear = bulkImportLineRepository
                .findByUserIdAndNormalizedTitleAndYear(userId, normalize(parsed.title()), parsed.year());
        if (byTitleAndYear.isPresent()) {
            return byTitleAndYear;
        }
        return bulkImportLineRepository.findByUserIdAndNormalizedTitleAndYearIsNull(
                userId, normalize(parsed.title()));
    }
    return bulkImportLineRepository.findByUserIdAndRawLineAndYearIsNull(userId, parsed.rawLine());
}
```
D-01 pattern: thread `batchId` as an additional parameter through this private method (called
from `upsertLine()`, which is called from `processLine()` — `batchId` is already a parameter of
`processLine()` and `batch` is already resolved there via `bulkImportBatchRepository.getReferenceById(batchId)`
at line 202) into new batch-scoped repository methods.

**Current cross-batch fast-path to batch-scope** (`existingSaved`, lines 211-218):
```java
Optional<BulkImportLine> existingSaved = bulkImportLineRepository
        .findByUserIdAndNormalizedTitleAndYearAndStatus(
                user.getId(), normalizedTitle, parsed.year(), BulkImportLineStatus.SAVED);
if (existingSaved.isPresent()) {
    log.info("Bulk import: skipping already-saved line title={} year={}", parsed.title(), parsed.year());
    return Optional.empty();
}
```
D-02/D-03 pattern: add `batchId` to this query call; per D-03, when the batch-scoped lookup now
returns empty for a title/year that IS `SAVED` in a different (older) batch, **do not** add any
new cross-batch check — fall straight through to the existing full TMDB search + `saveAndUpsert()`
pipeline below (lines 220-259), unchanged. `movieService.initiate()` is already idempotent by
`tmdbId`, so no duplicate `Movie` row risk.

**Matching pipeline to rework** (`processLine()`, lines 220-259) — D-10/D-11/D-12 multi-stage rework:
```java
List<TmdbSearchResultItem> results;
try {
    results = tmdbClient.search(parsed.title(), tmdbKey);
} catch (Exception e) {
    log.warn("Bulk import: TMDB search failed for title={}: {}", parsed.title(), e.getMessage());
    upsertLine(user, parsed, BulkImportLineStatus.NOT_FOUND, null, null, batch);
    return Optional.empty();
}
List<TmdbSearchResultItem> yearMatches = results.stream()
        .filter(r -> r.year() != null && r.year().equals(parsed.year()))
        .toList();

if (yearMatches.isEmpty()) {
    upsertLine(user, parsed, BulkImportLineStatus.NOT_FOUND, null, null, batch);
    return Optional.empty();
}

if (yearMatches.size() == 1) {
    return saveAndUpsert(user, email, parsed, yearMatches.get(0), batch);
}

// D-06: still ambiguous after year filter — try original-title narrowing.
if (parsed.originalTitle() != null && !parsed.originalTitle().isBlank()) {
    List<TmdbSearchResultItem> narrowed = yearMatches.stream()
            .filter(r -> r.originalTitle() != null
                    && r.originalTitle().equalsIgnoreCase(parsed.originalTitle()))
            .toList();
    if (narrowed.size() == 1) {
        return saveAndUpsert(user, email, parsed, narrowed.get(0), batch);
    }
}

// D-04: multiple candidates, no unambiguous narrowing — never auto-guess.
upsertLine(user, parsed, BulkImportLineStatus.AMBIGUOUS, null, null, batch);
return Optional.empty();
```
Preserve exactly: the `try/catch` around `tmdbClient.search()` mapping to `NOT_FOUND` (D-13,
unchanged), the terminal `upsertLine(..., AMBIGUOUS, ...)` fallback shape (D-04, "never
auto-guess"), and the original-title narrowing block's structure (moves to become the LAST
fallback per D-10, still gated by `parsed.originalTitle() != null && !isBlank()` and still
`equalsIgnoreCase`). New logic per D-11/D-12:
- `results.isEmpty()` → `NOT_FOUND` (D-12, unchanged from today's zero-match case)
- `results.size() == 1` → take directly, no year check (D-10 "unambiguous single result")
- Multiple results → exact title+year match: `parsed.title().equalsIgnoreCase(r.title())
  || parsed.title().equalsIgnoreCase(r.originalTitle())` combined with `r.year() != null &&
  r.year().equals(parsed.year())` (D-11 — either field, case-insensitive)
- If that narrowing doesn't yield exactly one → fall through to the existing original-title
  narrowing block verbatim (D-10 fallback survives unchanged)
- Still no single match → `AMBIGUOUS` (D-04 invariant, unchanged)

**`saveAndUpsert()` — unchanged, reuse as-is** (lines 262-275):
```java
private Optional<MatchedLine> saveAndUpsert(
        User user, String email, ParsedLine parsed, TmdbSearchResultItem match, BulkImportBatch batch) {
    MovieInitiateResult result = movieService.initiate(email, match.tmdbId());
    upsertLine(user, parsed, BulkImportLineStatus.SAVED, match.tmdbId(), match.posterPath(), batch);
    return result.isNew() ? Optional.of(new MatchedLine(result.id(), match.tmdbId())) : Optional.empty();
}
```

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java`

**Analog:** itself (existing `@Query`/derived-method pairs) + `BulkImportController.loadOwnedBatch()`'s `(userId, batchId)` ownership-scoping shape (referenced directly by CONTEXT.md's Reusable Assets).

**Pattern to extend — existing non-batch-scoped query pair** (lines 18-35):
```java
@Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId "
        + "AND lower(b.title) = :normalizedTitle AND b.year = :year AND b.status = :status")
Optional<BulkImportLine> findByUserIdAndNormalizedTitleAndYearAndStatus(
        @Param("userId") UUID userId,
        @Param("normalizedTitle") String normalizedTitle,
        @Param("year") Integer year,
        @Param("status") BulkImportLineStatus status);

@Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId "
        + "AND lower(b.title) = :normalizedTitle AND b.year = :year")
Optional<BulkImportLine> findByUserIdAndNormalizedTitleAndYear(
        @Param("userId") UUID userId,
        @Param("normalizedTitle") String normalizedTitle,
        @Param("year") Integer year);
```
D-01/D-02 pattern: add sibling `@Query` methods with `AND b.batch.id = :batchId` appended
(mirrors `countByBatchIdGroupByStatus`'s `b.batch.id = :batchId` JPQL clause at line 70, and
`findByIdAndBatchId`'s derived-method `AndBatchId` naming convention at line 78) —
e.g. `findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus`,
`findByUserIdAndBatchIdAndNormalizedTitleAndYear`,
`findByUserIdAndBatchIdAndNormalizedTitleAndYearIsNull`,
`findByUserIdAndBatchIdAndRawLineAndYearIsNull`. Keep the existing non-batch-scoped methods in
place initially (CONTEXT.md's Integration Points note: "likely become dead code once callers
switch, but check for other callers before removing").

**Derived-method naming convention to follow** (line 78):
```java
Optional<BulkImportLine> findByIdAndBatchId(UUID id, UUID batchId);
```

---

### `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java`

**Analog:** itself — `ProgressState` record + `complete()` method.

**Record to extend** (line 63-65):
```java
public record ProgressState(int processed, int total, boolean complete,
                             String lastMovieTitle, String lastMovieStatus, long etaSeconds) {
}
```
D-04: add trailing `boolean stopped` field — `record ProgressState(int processed, int total,
boolean complete, String lastMovieTitle, String lastMovieStatus, long etaSeconds, boolean
stopped)`. Every other construction site in this file (`register()`'s synthesized state line 84,
`start()` line 103, `publish()` line 133) needs a trailing `false` (or, for `register()`'s
synthetic complete, `true` since nothing was "stopped" — matches finished/never-run semantics)
argument added.

**`complete()` to change from always-total to real-processed-count + stopped flag** (lines 164-178):
```java
public void complete(UUID userId) {
    ProgressState prior = lastKnown.get(userId);
    int total = prior != null ? prior.total() : 0;
    ProgressState state = new ProgressState(
            total, total, true,
            prior != null ? prior.lastMovieTitle() : null,
            prior != null ? prior.lastMovieStatus() : null,
            0L);
    lastKnown.put(userId, state);

    broadcast(userId, "complete", state);

    stopFlags.remove(userId);
    durationWindowsMs.remove(userId);
}
```
D-04 pattern: read `boolean stopped = isStopRequested(userId)` (call `isStopRequested()` —
already exists, line 195 — BEFORE the `stopFlags.remove(userId)` cleanup line, per CONTEXT.md's
explicit ordering requirement) and use `prior != null ? prior.processed() : total` (real
last-published count) instead of always `total` for the `processed` field. Keep the
`stopFlags.remove`/`durationWindowsMs.remove` cleanup lines exactly where they are (after
`broadcast()`), and keep the extensive javadoc bug-history comment intact/updated, not deleted —
matches this file's established pattern of preserving incident-history javadoc on every method
touched by a prior fix.

---

### `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java`

**Analog:** itself — no functional change expected; `batchReload()` already passes the real
per-movie `status` string (`SUCCESS`/`NOT_FOUND`/`FAILED`, from `WikiRetryOutcome.name()`) to
`progressService.publish()`:
```java
WikiRetryOutcome outcome = self.retryWikipedia(movie, resolvedTitles);
status = outcome.name();
...
progressService.publish(userId, processedCount, eligibleCount, movie.getTitle(), status, durationMs);
```
D-09 confirms this backend value is already correct — the enum (line 55: `public enum
WikiRetryOutcome { SUCCESS, NOT_FOUND, FAILED }`) and its threading into `publish()` (line 241)
require no code change. Verify only; do not modify unless a planner discovers otherwise.

---

### `frontend/pages/settings.vue`

**Analog:** itself — extend the existing Wikipedia Data section (script setup + template) in place.

**`v-if` guard to change** (D-06, template lines 483, 495):
```html
<button v-if="wikiProgress && !wikiProgress.complete" ... data-testid="wiki-stop-button">
...
<div v-if="wikiProgress && !wikiProgress.complete" data-testid="wiki-reload-progress" class="mt-4 space-y-2">
```
New guard shape: panel/history stays visible through a `stopped`-terminal event too — e.g.
`v-if="wikiProgress && (!wikiProgress.complete || wikiProgress.stopped)"` for the progress panel
(line 495). The Stop button (line 483) stays gated on `!wikiProgress.complete` only — no longer
relevant once truly complete/stopped (either terminal state hides it, matching D-07's "button
re-enables on either terminal event" without needing the Stop button to linger).

**Status text to add wording for two terminal states** (D-05, line 496):
```html
<p class="text-sm text-foreground">{{ wikiProgress.processed }} / {{ wikiProgress.total }} processed</p>
```
Extend with a computed label (mirrors the existing `wikiEtaLabel` computed pattern, lines 61-66)
that branches on `wikiProgress.stopped`: e.g. `Stopped at {{ processed }} / {{ total }}` vs
`Completed {{ processed }} / {{ total }}` vs (in-progress) `{{ processed }} / {{ total }}
processed` — same `computed(() => ...)` shape as `wikiEtaLabel`.

**Reload button re-enable — existing pattern already covers D-07** (line 479, script line 128-130):
```html
<ButtonPrimary ... :disabled="wikiReloadTriggering || !!(wikiProgress && !wikiProgress.complete)" @click="onTriggerWikiReload">
```
```js
if (p.complete) {
  wikiStopping.value = false
}
```
No change needed to the `:disabled` binding itself (already keys off `wikiProgress.complete`,
which fires on both stopped and fully-finished per D-04's schema change) or the `wikiStopping`
reset callback — D-07 confirms "same trigger point as today's completed-based re-enable."

**History clear-on-start to extend** (D-08, `onTriggerWikiReload()`, lines 225-244):
```js
async function onTriggerWikiReload() {
  wikiReloadTriggering.value = true
  wikiReloadMessage.value = null
  try {
    const result = await triggerWikiReload()
    if (result === 'started' && (!wikiProgress.value || wikiProgress.value.complete)) {
      wikiMovieHistory.value = []
    }
    ...
```
This condition (`!wikiProgress.value || wikiProgress.value.complete`) already fires on a
`stopped`-terminal state too, since `stopped` is a state that ALSO has `complete: true` per
D-04's design ("stopped runs are a kind of complete"). Likely requires no code change — verify
against the new `ProgressState` schema; flag for the planner if `complete` semantics shift.

**Per-movie history icon/label — 2-state to 3-state** (D-09, script line 122, template lines 501-511):
```js
wikiMovieHistory.value.push({ title: p.lastMovieTitle, status: p.lastMovieStatus ?? 'FAILED' })
```
```html
<li v-for="(entry, idx) in wikiMovieHistory" :key="idx" class="flex items-center gap-2 text-sm text-foreground">
  <CheckCircle2 v-if="entry.status === 'SUCCESS'" class="w-4 h-4 shrink-0" />
  <XCircle v-else class="w-4 h-4 shrink-0" />
  <span>{{ entry.title }}</span>
</li>
```
D-09 pattern: the `status` field already carries the real 3-value backend string
(`SUCCESS`/`NOT_FOUND`/`FAILED`) unchanged (push logic needs no edit) — only the template's
icon/label branching needs a third case. Existing import line 3: `import { CheckCircle2,
XCircle } from 'lucide-vue-next'` — add a neutral icon (e.g. `MinusCircle` or `HelpCircle` from
the same `lucide-vue-next` package, matching the existing import-grouping convention) for
`NOT_FOUND`, plus a status label span (e.g. "No Wikipedia article found") alongside/instead of
just the title, following the existing `<span>{{ entry.title }}</span>` structure.

---

### `frontend/composables/useSettings.ts`

**Analog:** itself — extend the existing `WikiReloadProgress` interface.

**Type to extend** (D-04, lines 4-11):
```ts
export interface WikiReloadProgress {
  processed: number
  total: number
  complete: boolean
  lastMovieTitle: string | null
  lastMovieStatus: string | null
  etaSeconds: number
}
```
Add `stopped: boolean` as a trailing field, matching the backend `ProgressState` record's new
field order exactly (Jackson serializes records positionally-named by field, so field ORDER in
the TS interface doesn't need to match Java, but the field NAME `stopped` must). No other change
needed in this file — `subscribeToWikiReloadProgress()`'s `JSON.parse(ev.data) as
WikiReloadProgress` cast (line 82) picks up the new field automatically.

---

### `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java`

**Analog:** itself — existing equality-based assertions need a trailing argument added.

**Assertions to update** (lines 100-105, flagged explicitly by the source todo as the reason this fix was deferred):
```java
var states = captor.getAllValues().stream().map(this::capturedState).toList();
assertThat(states.get(0)).isEqualTo(
        new WikiReloadProgressService.ProgressState(1, 10, false, "Inception", "SUCCESS", 9L));
assertThat(states.get(1)).isEqualTo(
        new WikiReloadProgressService.ProgressState(2, 10, false, "Whiplash", "NOT_FOUND", 8L));
assertThat(states.get(2)).isEqualTo(
        new WikiReloadProgressService.ProgressState(10, 10, true, "Whiplash", "NOT_FOUND", 0L));
```
Every `new ProgressState(...)` call site in this file (also lines 48-53, 68-73, 128-133,
171-175, plus every `publish(...)` call passing through the record) needs the trailing `stopped`
argument added — `false` for in-progress/never-stopped states, and for the `complete()`-produced
terminal state at line 104-105 (`processed==total==10`, a genuine finish, not the new
stopped-scenario test), `stopped` should be `false` too since `isStopRequested()` was never
called in that test's setup. New test(s) should be added following this exact same
Mockito-mock-SseEmitter + `ArgumentCaptor<SseEmitter.SseEventBuilder>` + `capturedState()`
helper pattern (already defined at lines 26-33) to cover: `complete()` after `requestStop()` set
→ `stopped=true` and `processed` equals the last-published count (not `total`).

**New test to add — stopped-vs-finished distinction**, following the existing
`resetRun_afterPriorRequestStop_clearsFlagBackToFalse`-style Arrange/Act/Assert shape (lines
203-212):
```java
@Test
void requestStop_thenComplete_reportsStoppedTrueAndRealProcessedCount() {
    UUID userId = UUID.randomUUID();
    progressService.publish(userId, 3, 10, "Movie C", "SUCCESS", 1000L);
    progressService.requestStop(userId);

    progressService.complete(userId);

    ProgressState state = ... // capture via mocked emitter, same pattern as existing tests
    assertThat(state.processed()).isEqualTo(3); // NOT 10
    assertThat(state.stopped()).isTrue();
}
```

## Shared Patterns

### Self-proxy `@Lazy` pattern for `@Async`/`@Transactional` methods
**Source:** `BulkImportService` (lines 47, 60, 69, 104) and `WikiReloadService` (lines 43, 66-76, 100-103, 221) — both already establish `private final <Self> self` fields wired via constructor `@Lazy` injection, with same-class unqualified calls routed through `self.method(...)` so Spring AOP (transactional/async proxying) actually applies. No new files introduce this pattern this phase, but any new method added to either service that needs `@Transactional` MUST be called via `self.`, never `this.`/bare, if invoked from a non-transactional method in the same class (e.g. if D-01's batch-scoped `findExistingRow()` becomes its own `@Transactional` method — unlikely, but flag for planner).

### Per-line/per-movie failure isolation
**Source:** `BulkImportService.runImport()` (lines 98-119, try/catch per line, `log.warn` + continue) and `WikiReloadService.batchReload()` (lines 220-227, try/catch per movie, mapped to `FAILED` outcome) — established pattern: never let one bad item's exception abort the whole loop; log a `warn` with the item's identifying field and continue. Applies unchanged to both item 1 and item 2's rework — CONTEXT.md confirms neither changes this invariant.

### JPQL scoping shape for new repository queries
**Source:** `BulkImportLineRepository.countByBatchIdGroupByStatus()` (line 70: `WHERE b.batch.id = :batchId`) and `findByIdAndBatchId()` (line 78, derived-method `AndBatchId` suffix) — the two established idioms (explicit `@Query` JPQL vs. Spring Data derived-method naming) for batch-scoping a query. D-01's new methods should follow whichever idiom the sibling method they're extending already uses (the existing `findByUserIdAndNormalizedTitleAndYearAndStatus` etc. all use explicit `@Query`, so their batch-scoped counterparts should too, for consistency within the same lookup family).

### SSE terminal-state broadcasting
**Source:** `WikiReloadProgressService.broadcast()`/`complete()`/`publish()` (lines 123-178) — `stopped` threads through the exact same `lastKnown.put()` + `broadcast(userId, "complete", state)` mechanism already used for `complete`/`processed`/`etaSeconds`. No new transport, no new SSE event name — the existing `"complete"` event name is reused for both a genuine finish and a stop (distinguished by the new `stopped` field inside the payload, per D-04).

### Frontend `computed()` for derived progress-panel text
**Source:** `settings.vue`'s `wikiProgressPercent` (lines 54-57) and `wikiEtaLabel` (lines 59-66) — both are `computed(() => ...)` derivations off `wikiProgress.value`, gated with an early-return default (`0` / `''`) when no active state exists. The new stopped-vs-completed status-text label (D-05) should follow this exact shape rather than inlining a ternary directly in the template.

## No Analog Found

None — every file in scope is a modification to existing, already-analyzed code; no wholly new files are created in this phase.

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/bulkimport/`, `backend/src/main/java/de/moviearchive/admin/`, `backend/src/main/java/de/moviearchive/enrichment/`, `backend/src/test/java/de/moviearchive/admin/`, `frontend/pages/`, `frontend/composables/`
**Files scanned:** 8 (all read in full — each under 520 lines, single-pass reads, no re-reads)
**Pattern extraction date:** 2026-08-29
