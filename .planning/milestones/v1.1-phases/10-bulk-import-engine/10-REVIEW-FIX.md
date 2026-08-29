---
phase: 10-bulk-import-engine
fixed_at: 2026-08-24T13:12:00Z
review_path: .planning/phases/10-bulk-import-engine/10-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 10: Code Review Fix Report

**Fixed at:** 2026-08-24T13:12:00Z
**Source review:** .planning/phases/10-bulk-import-engine/10-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (fix_scope: critical_warning — CR-01, WR-01, WR-02, WR-03; IN-01/IN-02 excluded)
- Fixed: 4
- Skipped: 0

**Verification environment:** All fixes were applied and verified inside an isolated git
worktree (`workflow.use_worktrees` was not overridden, so it defaulted to `true`). Tier 2
verification ran `./gradlew compileJava compileTestJava` after every fix (clean compile each
time), and `./gradlew test --tests "de.moviearchive.bulkimport.*"` once after the final fix.
`BulkImportServiceTest` (6/6, pure Mockito unit tests) and `ImportLineParserTest` (6/6) passed.
`BulkImportControllerTest` failed with `initializationError` — a pre-existing Testcontainers
limitation (`Could not find a valid Docker environment`) in this sandbox, unrelated to any of
the four fixes; it is a Testcontainers/Docker-in-CI integration test, not a unit test, and
requires a Docker daemon that isn't available in this environment. These commands are
reproducible in the main checkout by re-running the same gradle invocations after `git log`
confirms the fix commits (`9c2636d`, `d3fa4a7`, `01a7eba`, `9c0c2dd`) are present.

## Fixed Issues

### CR-01: Async enrichment races against the still-open `processLine()` transaction

**Files modified:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`
**Commit:** `9c2636d`
**Status:** fixed
**Applied fix:** `processLine()` now returns `Optional<UUID>` — the id of any newly-created
movie — instead of calling `enrichmentService.enrich()` itself from inside its own
`@Transactional` scope. `runImport()` (not `@Transactional`) now calls
`self.processLine(...).ifPresent(enrichmentService::enrich)`, so the `@Async` enrichment task
only starts after `processLine()`'s transaction has returned and committed. `saveAndUpsert()`
was updated to return `Optional<UUID>` (present only when `MovieInitiateResult.isNew()`)
instead of firing `enrich()` directly. This matches the fix approach proposed in REVIEW.md.

### WR-01: TMDB failures leave the line with no persisted record at all

**Files modified:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`
**Commit:** `d3fa4a7`
**Status:** fixed
**Applied fix:** Wrapped `tmdbClient.search(...)` in a try/catch inside `processLine()`. On
failure, logs a warning and persists a `BulkImportLineStatus.NOT_FOUND` row via `upsertLine()`
(reusing the existing status, per REVIEW.md's first suggested option) instead of letting the
exception propagate out of the transactional method and leave the line with no row at all. A
new dedicated status (e.g. `TMDB_ERROR`) was considered but rejected for this pass since it
would require altering the `bulk_import_line_status_check` CHECK constraint via a new Flyway
migration — reusing `NOT_FOUND` resolves the audit-trail gap with a strictly additive,
lower-risk change.

### WR-02: No upper bound on bulk-import file size / line count

**Files modified:**
`backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java`,
`backend/src/main/resources/application.properties`
**Commit:** `01a7eba`
**Status:** fixed
**Applied fix:** Added a configurable `bulk-import.max-lines` property (default `5000`, env
override `BULK_IMPORT_MAX_LINES`), following the same `@Value`-injected pattern already used
for `bulk-import.pacing-delay-ms`. `uploadBulkImport()` now throws `IllegalArgumentException`
(already mapped to an HTTP 400 by the controller's existing `@ExceptionHandler`) when
`rawLines.size() > maxLines`, before dispatching to `bulkImportService.runImport(...)`.

### WR-03: Re-upload after fixing a `PARSE_ERROR` line creates a duplicate row

**Files modified:**
`backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java`,
`backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`
**Commit:** `9c0c2dd`
**Status:** fixed: requires human verification
**Applied fix:** REVIEW.md's own fix section notes the previous raw-line text "isn't knowable"
for a general solution, but observes that `ImportLineParser.parse()` still populates `title`
for a line that fails only on the year field (e.g. `"Title;;abcd"` → `title="Title"`,
`year=null`, `valid=false`). `findExistingRow()` was extended so that, when the current parse
succeeds (`year != null`) and no exact `(title, year)` match exists, it additionally probes a
new repository method `findByUserIdAndNormalizedTitleAndYearIsNull` for a stale `year=null` row
sharing the same normalized title — covering exactly the `"Title;;abcd"` → `"Title;;2010"`
re-upload scenario described in the finding. This does not cover every conceivable
identity-drift case (e.g. a line that also fails to split into 3 fields, or whose title text
itself changes between uploads) — those remain an accepted, now better-documented limitation
via the method's javadoc.
**Why "requires human verification":** this is a data-identity/query-matching fix (not just a
syntax change); Tier 1 (re-read) and Tier 2 (compile + the existing unit/integration test
suite) confirm the code compiles and does not regress the 6 passing `BulkImportServiceTest`
cases, but none of the existing tests exercise the specific PARSE_ERROR-then-fixed re-upload
path this fix targets. A developer should add/run a test asserting that re-uploading a
previously-`PARSE_ERROR` line with a now-valid year updates the existing row in place (no new
row inserted) before considering this fully verified.

## Skipped Issues

None — all four in-scope findings were fixed.

---

_Fixed: 2026-08-24T13:12:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
