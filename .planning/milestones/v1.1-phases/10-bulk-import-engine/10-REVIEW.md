---
phase: 10-bulk-import-engine
reviewed: 2026-08-24T11:02:36Z
depth: standard
files_reviewed: 20
files_reviewed_list:
  - backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineStatus.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
  - backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
  - backend/src/test/resources/fixtures/tmdb/robin-hood-ambiguous-search.json
  - backend/src/main/java/de/moviearchive/config/AsyncConfig.java
  - backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java
  - backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java
  - backend/src/main/java/de/moviearchive/movie/MovieService.java
  - backend/src/main/resources/application.properties
  - backend/src/test/resources/application-test.properties
  - frontend/composables/useMovies.ts
  - frontend/pages/add.vue
  - frontend/test/unit/composables/useMovies.spec.ts
  - frontend/test/unit/pages/add.spec.ts
findings:
  critical: 1
  warning: 3
  info: 2
  total: 6
status: issues_found
---

# Phase 10: Code Review Report

**Reviewed:** 2026-08-24T11:02:36Z
**Depth:** standard
**Files Reviewed:** 20
**Status:** issues_found

## Summary

Reviewed the bulk-import engine: SQL migration, entity/repository, line parser, async
service, controller, their tests, and the small supporting diffs to `TmdbClient`,
`TmdbSearchResultItem`, `MovieService`, `AsyncConfig`, and the frontend `add.vue` page /
`useMovies` composable.

The parsing logic (`ImportLineParser`), the dedup/upsert design, and the queue-capacity
DoS mitigation (bounded `bulkImportExecutor`, 1 running + 1 queued) are solid and well
tested. However, tracing the transaction/async boundary between `BulkImportService.processLine()`
and `EnrichmentService.enrich()` surfaces a real race condition that silently defeats
enrichment for every bulk-imported film (Critical). Two further gaps affect the
completeness/robustness of the per-line audit trail (Warnings), plus a minor input-validation
gap and a couple of Info-level polish items.

## Critical Issues

### CR-01: Async enrichment races against the still-open `processLine()` transaction — bulk-imported movies are silently never enriched or indexed

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:93-161`

**Issue:**
`processLine()` is annotated `@Transactional` (required so the `@Lazy` self-proxy call from
`runImport()` actually opens a transaction). Inside that same transaction, `saveAndUpsert()`
calls:
```java
MovieInitiateResult result = movieService.initiate(email, tmdbId);   // INSERT, same tx (REQUIRED)
if (result.isNew()) {
    enrichmentService.enrich(result.id());                            // @Async — fires immediately
}
upsertLine(user, parsed, BulkImportLineStatus.SAVED, tmdbId);         // still same tx
```
`MovieService` is class-level `@Transactional` with default `REQUIRED` propagation, so
`initiate()`'s `INSERT` **joins** `processLine()`'s already-open transaction rather than
committing on its own. `EnrichmentService.enrich()` is `@Async("enrichmentExecutor")`,
so calling it returns immediately and the enrichment task starts running on a separate
thread/connection almost immediately — very likely **before** `processLine()` returns and
its surrounding `@Transactional` proxy issues the `COMMIT` (which only happens after
`upsertLine()` also runs and the method returns).

Under Postgres READ COMMITTED isolation, the enrichment thread's separate connection cannot
see the uncommitted `Movie` row. `EnrichmentService.enrich()` does:
```java
Movie movie = movieRepository.findByIdWithUser(movieId)
        .orElseThrow(() -> new IllegalStateException("Movie not found for enrichment: " + movieId));
```
This throws `IllegalStateException` on an `@Async` thread (fire-and-forget — nothing catches
or surfaces it), leaving the `Movie` row permanently stuck at `status=PENDING`: no title, no
poster, no Wikipedia content, never indexed to OpenSearch. The `bulk_import_line` row still
gets marked `SAVED` with a `tmdbId`, so the feature *looks* like it worked, but the archived
film is empty and unsearchable.

This is a genuine regression relative to the pattern this code claims to mirror
(`saveAndUpsert()`'s javadoc: "exactly `MovieController.saveMovie()`'s sequence"). Compare
`MovieController.saveMovie()` (`backend/src/main/java/de/moviearchive/movie/MovieController.java:34-42`):
that controller method is **not** `@Transactional`, so `movieService.initiate()` commits its
own transaction before returning to the controller, and by the time `enrich()` fires, the row
is already durable. Wrapping the bulk-import equivalent in `@Transactional` breaks that
precondition — the sequence is not actually equivalent, despite the comment.

This is a race, not a guaranteed 100%-reproducible failure, which is why
`BulkImportControllerTest.shouldSaveUniqueMatch_andPersistBulkImportLineRow` (which only
asserts `bulk_import_line.status == SAVED` and `movieRepository.count() == 1`, never that the
`Movie` itself reaches `status=SUCCESS`/gets a title) does not catch it — but it will manifest
intermittently-to-reliably under real database latency.

**Fix:** Defer the `enrich()` call until after the current transaction commits, e.g. register
a `TransactionSynchronization` that fires `afterCommit()`, or (simpler, and consistent with
the rest of the codebase) don't call `enrich()` from inside a `@Transactional` method at all —
have `processLine()` return the id of any newly created movie and let a non-transactional
caller (`runImport()`, or a wrapper around `self.processLine()`) invoke
`enrichmentService.enrich()` after the per-line transaction has returned/committed:
```java
// processLine() returns Optional<UUID> of a newly-created movie instead of calling enrich() itself
@Transactional
public Optional<UUID> processLine(String email, String tmdbKey, String rawLine) {
    ...
    return saveAndUpsert(user, email, parsed, yearMatches.get(0).tmdbId()); // returns movie id if new, else empty
}

// runImport() — NOT transactional — is a safe place to fire the async enrichment
for (...) {
    self.processLine(email, tmdbKey, rawLines.get(i)).ifPresent(enrichmentService::enrich);
}
```
Alternatively use `TransactionSynchronizationManager.registerSynchronization` with an
`afterCommit()` callback around the `enrich()` call if keeping the call inside
`saveAndUpsert()` is preferred.

## Warnings

### WR-01: TMDB failures leave the line with no persisted record at all

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:120,93-149`

**Issue:** `tmdbClient.search(...)` is called before any `upsertLine()` write in `processLine()`.
If it ultimately throws (e.g. `@Retryable` on `TmdbClient.search` exhausts its 3 attempts, or
TMDB returns a non-2xx the retry doesn't handle), the exception propagates out of the
`@Transactional processLine()` method, rolling back the (empty) transaction, and is caught only
by `runImport()`'s generic `catch (Exception e)`, which just logs a warning and moves to the
next line. Unlike every other outcome (`SAVED`, `AMBIGUOUS`, `NOT_FOUND`, `PARSE_ERROR`), this
path never persists a `bulk_import_line` row — the user has no way to discover, from the data
this feature is supposed to produce, that a specific line failed. `BulkImportLineStatus` also
has no state to represent it (only the 4 values allowed by the migration's `CHECK` constraint).

**Fix:** Wrap the `tmdbClient.search(...)` call and persist a row (e.g. reuse `NOT_FOUND`, or
add a new `BulkImportLineStatus.ERROR`/`TMDB_ERROR` value) so every line the user submits ends
up with a queryable outcome:
```java
List<TmdbSearchResultItem> results;
try {
    results = tmdbClient.search(parsed.title(), tmdbKey);
} catch (Exception e) {
    log.warn("Bulk import: TMDB search failed for title={}: {}", parsed.title(), e.getMessage());
    upsertLine(user, parsed, BulkImportLineStatus.NOT_FOUND, null); // or a new ERROR status
    return;
}
```

### WR-02: No upper bound on bulk-import file size / line count — a single upload can monopolize the only import slot for hours

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:41-61`

**Issue:** `uploadBulkImport()` reads the entire file into a `List<String>` with no cap on the
number of lines, and `bulkImportExecutor` (`AsyncConfig.java:49-58`) is intentionally sized to
a single global run-slot + single queue-slot. With the default `pacing-delay-ms` of 1000ms
(`application.properties:62`), even a moderately sized list (tens of thousands of lines, well
within Spring Boot's default 1MB multipart limit) ties up the single import slot for hours,
during which every other bulk-import request — from this user or, since the slot is global
rather than per-user, from any user — is rejected with 503. There is no line-count or
file-size validation to fail fast with a clear error before dispatching the async job.

**Fix:** Reject (400) uploads exceeding a sane line-count ceiling (e.g. a few thousand lines)
before calling `bulkImportService.runImport(...)`:
```java
if (rawLines.size() > MAX_LINES) {
    throw new IllegalArgumentException("File exceeds " + MAX_LINES + " lines; split into smaller batches.");
}
```

### WR-03: Re-upload after fixing a `PARSE_ERROR` line creates a duplicate row instead of updating in place

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:180-186`

**Issue:** `findExistingRow()` looks up unparseable lines (`year == null`) by
`(userId, rawLine)` and parseable lines by `(userId, normalizedTitle, year)`. These two
identity paths never intersect. Concretely: upload `"Title;;abcd"` → persisted as
`PARSE_ERROR` with `raw_line="Title;;abcd", year=null`. Fix the typo and re-upload
`"Title;;2010"` → `parsed.year()=2010`, so the lookup goes through
`findByUserIdAndNormalizedTitleAndYear(userId, "title", 2010)`, which does not match the
existing `year=null` row (SQL `NULL = 2010` is `UNKNOWN`, not `TRUE`) — a brand-new row is
inserted, and the original `PARSE_ERROR` row is orphaned forever. This directly contradicts
the migration's own documented guarantee ("`One row per logical line` is enforced at the
application layer via find-then-update" — `V9__create_bulk_import_line.sql:16-19`), which
only holds within a single value of `valid`/`year != null`, not across the parse-error →
parseable transition that re-uploads are explicitly designed to support (D-13).

**Fix:** When a line's year newly parses, also probe for a stale `PARSE_ERROR` row keyed on
the *previous* raw line text isn't knowable — but at minimum, before falling back to
`orElseGet(() -> new BulkImportLine(...))`, consider keying the null-year lookup differently
(e.g. carry a stable per-line index/hash from the upload rather than raw text), or accept and
document the limitation explicitly rather than implicitly promising "one row per line."

## Info

### IN-01: Bulk-import file input has no associated `<label>`

**File:** `frontend/pages/add.vue:215-221`

**Issue:** The `<input id="bulk-import-file" type="file">` has no `<label for="bulk-import-file">`,
unlike the search field (which uses the labeled `InputText` component). Screen-reader users
get no accessible name for the control beyond the browser's generic "Choose File" text.

**Fix:**
```html
<label for="bulk-import-file" class="sr-only">Bulk import file</label>
<input id="bulk-import-file" type="file" ... >
```

### IN-02: `raw_line` is never refreshed when an existing row is updated in place

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:167-178`

**Issue:** `upsertLine()` sets `title`, `originalTitle`, `year`, `status`, `tmdbId`, and
`updatedAt` on the matched/created row, but never `row.setRawLine(parsed.rawLine())`. If a
re-uploaded file has cosmetic differences in a matched line (e.g. different internal
whitespace or field casing that still normalizes to the same `(title, year)`), the stored
`raw_line` keeps showing the first-ever variant, which can be confusing when diagnosing why a
particular row exists.

**Fix:**
```java
row.setRawLine(parsed.rawLine());
```

---

_Reviewed: 2026-08-24T11:02:36Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
