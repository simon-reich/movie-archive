---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
reviewed: 2026-08-28T00:00:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - backend/build.gradle.kts
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
  - backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java
  - backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java
  - backend/src/main/java/de/moviearchive/bulkimport/dto/ResolveLineRequest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java
  - frontend/composables/useBulkImport.ts
  - frontend/pages/add.vue
  - frontend/pages/imports/[batchId].vue
  - frontend/test/unit/pages/imports-batchId.spec.ts
findings:
  critical: 1
  warning: 5
  info: 3
  total: 9
status: issues_found
---

# Phase 15: Code Review Report

**Reviewed:** 2026-08-28T00:00:00Z
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

Reviewed the bulk-import batch/CSV/resolve backend pipeline and its frontend consumers
(`add.vue`, `imports/[batchId].vue`, `useBulkImport.ts`) at standard depth. The core matching
pipeline (year filter → original-title narrowing → ambiguous fallback), the CR-01
commit-before-enrich sequencing, and the T-15-01 IDOR mitigation on `resolveLine` are all
sound and covered by tests. The main finding is a real data-integrity bug: `upsertLine()`'s
"never insert a duplicate row" dedup logic is scoped by `(user, title, year)` only — not by
`batch` — so re-uploading an overlapping title/year that is currently AMBIGUOUS/NOT_FOUND/
PARSE_ERROR in an *older* batch silently reassigns that row's `batch_id` to the new batch,
leaving the older batch's `totalLines` out of sync with its actual line count. The test suite
itself works around this exact scenario (`shouldReturn404_whenResolvingLineFromDifferentBatch`
explicitly calls `bulkImportLineRepository.deleteAll()` between batch A and batch B to avoid
triggering it), which is a strong signal this is an unhandled edge case rather than a deliberate
design decision. Several smaller robustness/quality issues are noted below (SSE reconnect
logic, duplicated parser code, a flaky sleep-based test).

## Critical Issues

### CR-01: Re-uploading an overlapping title/year silently reassigns a line from an older batch to the new batch

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:339-367`
**Issue:**
`upsertLine()` finds the row to update via `findExistingRow(user.getId(), parsed)`, which is
scoped only by `(userId, normalizedTitle, year)` (or by `rawLine` when year is null) —
**never by `batchId`**:

```java
private Optional<BulkImportLine> findExistingRow(UUID userId, ParsedLine parsed) {
    if (parsed.year() != null) {
        Optional<BulkImportLine> byTitleAndYear = bulkImportLineRepository
                .findByUserIdAndNormalizedTitleAndYear(userId, normalize(parsed.title()), parsed.year());
        ...
```

`upsertLine()` then unconditionally does `row.setBatch(batch)` (line 347), moving whatever row
it found onto the *current* batch. The SAVED case is short-circuited earlier by
`existingSaved` (line 268-274) and never reaches this path, but AMBIGUOUS / NOT_FOUND /
PARSE_ERROR rows are not: if a user uploads batch A containing `"Robin Hood;;2010"` (which
resolves AMBIGUOUS and is persisted against batch A), then later uploads an unrelated batch B
that also happens to contain `"Robin Hood;;2010"`, the *same* `BulkImportLine` row is found and
its `batch` FK is silently repointed to batch B. Batch A's `bulk_import_batch.total_lines`
column (set once at upload time in `createBatch()`) is never adjusted, so:
- `GET /movies/bulk-import/batches/{batchAId}` now returns one fewer line than
  `totalLines` claims, with no indication to the user that a row "disappeared."
- Batch B's detail view shows a line that was never actually part of the file the user
  uploaded for batch B.
- The batch-list `statusCounts` for batch A silently drifts out of sync with `totalLines` too.

The test suite is aware of this exact collision: `shouldReturn404_whenResolvingLineFromDifferentBatch`
in `BulkImportControllerTest.java` explicitly calls
`bulkImportLineRepository.deleteAll()` between uploading batch A and batch B (line 642) purely
to avoid tripping this reassignment — it is not exercised or asserted anywhere as intended
behavior.

**Fix:** Scope the find-or-create lookup to the current batch (or drop the cross-batch reuse
entirely and always insert a fresh row per batch upload):
```java
@Query("SELECT b FROM BulkImportLine b WHERE b.batch.id = :batchId "
        + "AND lower(b.title) = :normalizedTitle AND b.year = :year")
Optional<BulkImportLine> findByBatchIdAndNormalizedTitleAndYear(
        @Param("batchId") UUID batchId,
        @Param("normalizedTitle") String normalizedTitle,
        @Param("year") Integer year);
```
and thread `batchId` through `findExistingRow(UUID userId, UUID batchId, ParsedLine parsed)` so
a row is only reused when it belongs to the batch currently being processed. If the intent of
D-13 really was "don't duplicate rows across re-uploads of the same file," that should be
re-verified against product intent, but as implemented today it corrupts unrelated batches'
historical line counts.

## Warnings

### WR-01: `SAVED` line is silently skipped in a later batch, leaving `totalLines` > `lines.size()` with no user-facing explanation

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:267-274`
**Issue:** When a `(user, title, year)` is already `SAVED` (potentially from a totally different
batch), `processLine()` returns `Optional.empty()` without persisting **any** row for the
current batch:
```java
if (existingSaved.isPresent()) {
    log.info("Bulk import: skipping already-saved line title={} year={}", parsed.title(), parsed.year());
    return Optional.empty();
}
```
`batch.totalLines` is set from the raw uploaded line count in `createBatch()` before this skip
happens, so the batch-detail response (`lines.size()`) can legitimately be smaller than
`totalLines` with zero indication to the user which line(s) were dropped or why. This is
"working as designed" per the D-08/D-10 comment, but from a UX/correctness-of-report
perspective it means the per-batch report is not a complete accounting of every line the user
uploaded for *this* batch.
**Fix:** Either persist a lightweight `SKIPPED_DUPLICATE` row (would need a status enum value)
so the batch report is complete, or surface `totalLines - lines.size()` as an explicit "N lines
were already saved and skipped" note in the frontend batch-detail view.

### WR-02: False-positive CSV header detection can silently discard a genuine data row

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:118-129`
**Issue:** The header-row heuristic removes `rawLines.get(0)` whenever it parses with a
non-blank title but an unparseable year:
```java
if (isCsvFormat && !rawLines.isEmpty()) {
    ImportLineParser.ParsedLine firstLineParsed = importLineParser.parseCsv(rawLines.get(0));
    if (firstLineParsed != null && firstLineParsed.title() != null && !firstLineParsed.valid()) {
        rawLines.remove(0);
    }
}
```
If a user's *first real data row* simply has a typo'd/non-numeric year (e.g.
`"Inception,,201O"` with a letter O), this heuristic indistinguishably treats it as a header row
and silently drops it — the user gets no PARSE_ERROR row and no feedback that their first line
was discarded, they only notice a missing film later.
**Fix:** At minimum, log at INFO when a line is stripped as a suspected header so it's traceable
in server logs; consider requiring more header-like evidence (e.g., match against known column
name tokens like "title"/"year") before discarding rather than any single first line with a
non-numeric year field.

### WR-03: `subscribeToProgress`'s `onerror` throws unconditionally, killing SSE reconnection on transient network errors

**File:** `frontend/composables/useBulkImport.ts:88-91`
**Issue:**
```javascript
onerror(err) {
  // Stop the library's default retry-forever behavior on a fatal error (e.g. 403/404)
  throw err
},
```
`@microsoft/fetch-event-source`'s default behavior is to retry with backoff on transient errors
and only give up when the handler re-throws. This handler re-throws unconditionally for
*every* error, including a momentary network blip, not just fatal 403/404 responses. Given the
docstring on the backend endpoint states a worst-case import can run "~83 minutes," a single
transient disconnect anywhere in that window permanently kills the live progress stream — the
user is left on "Connecting..." (or the last progress percentage) with no further updates,
even though the backend job keeps running to completion. There is no fallback polling of
`getBatchDetail()`/batch status if the SSE stream dies.
**Fix:** Inspect the error/response status before deciding whether to re-throw, e.g.:
```javascript
onerror(err) {
  if (err instanceof FatalError || (err?.status && [401, 403, 404].includes(err.status))) {
    throw err // stop retrying — truly fatal
  }
  // otherwise allow the library's default retry/backoff to continue
},
```

### WR-04: `parse()` and `parseCsv()` duplicate nearly all validation logic

**File:** `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java:33-121`
**Issue:** Both methods repeat the identical blank-line check, empty-original-title-to-null
normalization, empty-title invalidation, and `Integer.parseInt` year-parsing/invalidation logic
— only the field-splitting mechanism (`String.split` vs `CSVParser`) differs. This duplication
means any future change to validation rules (e.g., year range bounds, trimming behavior) has to
be applied twice and can easily drift out of sync between the two formats.
**Fix:** Extract a shared `private ParsedLine fromFields(String trimmed, String[] fields)`
(or `List<String>`) helper that both `parse()` and `parseCsv()` delegate to after they've done
their own delimiter-specific splitting/field-count validation.

### WR-05: `shouldSkipReupload_whenLineAlreadySaved` relies on a fixed `Thread.sleep(1000)` instead of polling

**File:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java:795-798`
**Issue:** Every other async-completion assertion in this test class correctly polls
(`pollForLine`, `pollForLineByTitle`, `drainBulkImportExecutor`) rather than sleeping a fixed
duration. This one test instead does:
```java
// Nothing new expected — brief settle window, not a full 5s poll for a new row
Thread.sleep(1000);
assertThat(movieRepository.count()).isEqualTo(countAfterFirst);
```
Under CI load (or with a slower `pacing-delay-ms` override applied class-wide — this class sets
`bulk-import.pacing-delay-ms=2000`, i.e. *longer* than the sleep itself), the second upload's
async job may not have even reached the dedup-check by the time the assertion runs, which would
make this a false-negative-prone (flaky-in-the-safe-direction, but still non-deterministic) test.
**Fix:** Replace with `drainBulkImportExecutor(10000)` (already used everywhere else in this
class) before asserting the movie count, so the assertion runs only after the async job has
actually finished.

## Info

### IN-01: `resolveAndPersistImdbId`'s blank-imdbId branch is unreachable via `JsonNode.asText(null)`

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:186-189`
**Issue:**
```java
String extractedImdbId = tmdbDetail.path("external_ids").path("imdb_id").asText(null);
if (extractedImdbId != null && extractedImdbId.isBlank()) {
    extractedImdbId = null;
}
```
`.asText(null)` already returns `null` for a missing/`NullNode` field, and TMDB's
`external_ids.imdb_id` is either absent, `null`, or a non-blank `"tt..."` string in practice —
so the blank-but-non-null branch is realistically dead code. Harmless, but slightly misleading
as written (implies blank strings are a real observed case).
**Fix:** Simplify to `.isBlank()` check only if you've actually observed TMDB return an empty
string; otherwise this is fine to leave as defensive code — just noting it for clarity, no
action required.

### IN-02: `posterUrl()` is duplicated verbatim between `add.vue` and `imports/[batchId].vue`

**File:** `frontend/pages/add.vue:104-107`, `frontend/pages/imports/[batchId].vue:72-75`
**Issue:** Identical function:
```javascript
function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}
```
appears in both files with no shared composable/util. Any future change to the TMDB image size
or placeholder path needs to be applied in two places.
**Fix:** Extract to a shared `composables/useTmdbImage.ts` (or a plain util) and import it in
both pages.

### IN-03: `ImportLineParser` allows a signed year (`+2010`, `-2010`) and unbounded magnitude

**File:** `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java:53-58, 113-118`
**Issue:** `Integer.parseInt(yearRaw)` accepts a leading `+`/`-` sign and any in-range integer,
so a line like `"Title;;-5"` or `"Title;;99999"` is treated as `valid=true` with that nonsensical
year, which will then simply fail to match anything in the TMDB year filter and end up
`NOT_FOUND` rather than being reported as a `PARSE_ERROR` with a clearer "invalid year" signal.
**Fix:** Optional — add a plausible range check (e.g. `1888..currentYear+2`) alongside the
existing `NumberFormatException` catch if clearer user feedback on malformed years is desired.

---

_Reviewed: 2026-08-28T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
