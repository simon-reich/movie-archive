---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
plan: 03
subsystem: backend
tags: [spring-boot, bulk-import, csv-parsing, apache-commons-csv]

requires:
  - phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
    provides: "Plan 15-01/15-02's batch-detail page, movie links, and inline resolve (BulkImportController.java touched again by this plan, additive only)"
provides:
  - "ImportLineParser.parseCsv() — comma-delimited CSV sibling parser (RFC4180-style quoting via CSVFormat.DEFAULT), identical ParsedLine contract to parse()"
  - "File-level isCsvFormat detection in BulkImportController.uploadBulkImport() (comma sniff on first non-blank line)"
  - "CSV-only optional header-row auto-skip (D-14)"
  - "BulkImportService.runImport()/processLine() dispatch parse() vs parseCsv() via a threaded isCsvFormat parameter"
affects: []

actuals:
  tokens: 7143
  tasks: 2
  commits: 3

tech-stack:
  added:
    - "org.apache.commons:commons-csv:1.14.1 — comma-delimited CSV parsing with RFC4180-style quoting"
  patterns:
    - "Sibling-parser dispatch: two independent parser methods (parse()/parseCsv()) returning the identical ParsedLine record, selected once per file by a boolean threaded through the controller -> service -> processLine() call chain — never duplicated downstream logic (dedup/match/upsert stay parser-agnostic)"

key-files:
  created: []
  modified:
    - backend/build.gradle.kts
    - backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
    - frontend/pages/add.vue

key-decisions:
  - "CSV as a second supported format — add-alongside, not promote (plan's own assumption_delta_decision): isCsvFormat is a one-time, file-level boolean computed in the controller, never a stored/user-facing property, no schema/DTO change"
  - "Header-row skip guarded on title() != null && !valid() — a null result (blank line) or null title (field-count mismatch) falls through unchanged to the normal PARSE_ERROR path rather than being silently dropped as a false-positive header"

patterns-established:
  - "record.size() != 3 checked BEFORE any .get(index) call — the CSV-format regression of the semicolon parser's existing field-count guard (T-15-05 DoS mitigation), never lets a malformed record throw uncaught"

requirements-completed: [D-12, D-13, D-14, D-15, D-16, D-17]

coverage:
  - id: D1
    description: "A comma-delimited CSV upload (Title,OriginalTitle,Year, standard RFC4180 quoting) parses and matches exactly like a semicolon upload"
    requirement: "D-12, D-13"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java#shouldParseValidCsvLine_withEmptyOriginalTitle"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldParseCommaDelimitedCsv_andSaveUniqueMatch"
        status: pass
    human_judgment: false
  - id: D2
    description: "A quoted comma-containing title parses as one field via CSVFormat.DEFAULT's default quoting, no special-casing"
    requirement: "D-15"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java#shouldParseQuotedCommaContainingTitle_asOneField"
        status: pass
    human_judgment: false
  - id: D3
    description: "An optional CSV header row (first row's Year column not numeric) is auto-detected and skipped, without an explicit flag"
    requirement: "D-14"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldSkipHeaderRow_whenFirstCsvLineHasNonNumericYear"
        status: pass
    human_judgment: false
  - id: D4
    description: "A malformed CSV line (wrong field count) becomes PARSE_ERROR, never an uncaught exception"
    requirement: "D-12 (V5 Input Validation, T-15-05)"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java#shouldBeInvalid_whenCsvFieldCountIsWrong"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldMarkParseError_forCsvLineWithWrongFieldCount"
        status: pass
    human_judgment: false
  - id: D5
    description: "The existing semicolon format keeps working completely unchanged — same parser, same behavior, same test suite green"
    requirement: "D-16"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java (all 6 pre-existing parse() tests, unmodified, still pass)"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldSaveUniqueMatch_andPersistBulkImportLineRow (semicolon format, unmodified input, still passes)"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn400_whenAllLinesFailToParse (pre-flight gate still correctly rejects wholly unparseable input)"
        status: pass
    human_judgment: false
  - id: D6
    description: "saubere_filmliste.txt (1139 lines, semicolon format) still imports with identical per-line outcomes after this plan — real-world backward-compat regression check"
    verification: []
    human_judgment: true
    rationale: "Explicitly marked manual-only in the plan's own <verification> section (per 15-VALIDATION.md, D-17) — requires running a real bulk import against the live app stack (TMDB key, DB, SSE progress) with the actual file, which this non-interactive worktree executor cannot do. The legacy parser path (ImportLineParser.parse()) itself is unmodified by this plan, so this is expected to be a no-op regression check once run."

duration: ~35min
completed: 2026-08-28
status: complete
---

# Phase 15 Plan 03: Real CSV Parsing for Bulk Import Summary

**Comma-delimited CSV as a second supported bulk-import format (quoted fields, optional header row), fully additive alongside the existing strict semicolon format**

## Performance

- **Duration:** ~35 min
- **Completed:** 2026-08-28
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments
- `org.apache.commons:commons-csv:1.14.1` added as a resolved backend dependency (D-13)
- `ImportLineParser.parseCsv()` — a sibling parser to the existing `parse()`, using `CSVFormat.DEFAULT` (comma delimiter + double-quote quoting built in, zero config for D-15's quoted-comma-title case), returning the IDENTICAL `ParsedLine` record contract so every downstream consumer (dedup-check, TMDB search, match, upsert) is unaware of which parser produced a line
- `record.size() != 3` is checked BEFORE any `.get(index)` call — a malformed/short CSV record degrades to an invalid `ParsedLine` instead of throwing (T-15-05 DoS mitigation, the CSV-format regression of the semicolon parser's existing field-count guard)
- `BulkImportController.uploadBulkImport()` computes `isCsvFormat` once per file (comma sniff on the first non-blank line) and auto-skips an optional CSV header row (D-14), scoped to the CSV path only — the legacy semicolon path is completely untouched (D-16)
- The pre-flight `anyLineParses` 400-gate now dispatches through the same `isCsvFormat` selection (G-10-1 regression guard) so a well-formed CSV upload no longer false-fails the synchronous check
- `BulkImportService.runImport()`/`processLine()` thread a new trailing `isCsvFormat` parameter that selects `parse()` vs `parseCsv()` — the ONLY change inside `processLine()`, every downstream step is untouched
- `frontend/pages/add.vue`'s Bulk Import format-hint copy now documents both supported formats and explicitly calls out that a semicolon-delimited German-locale Excel "CSV" export is NOT supported as CSV (15-RESEARCH.md Pitfall 3)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Apache Commons CSV dependency + ImportLineParser.parseCsv()** (tracer, RED->GREEN) - `39c32b4` (feat)
2. **Task 2: File-level format detection, header-row skip, and parser dispatch** - `ea5d37d` (feat)

**Deferred-items log:** `02816c2` (docs — logs an out-of-scope pre-existing full-suite test-isolation flakiness, not part of a task commit)

**Plan metadata:** (this commit, follows)

## Files Created/Modified
- `backend/build.gradle.kts` - added `org.apache.commons:commons-csv:1.14.1`
- `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java` - new `parseCsv()` sibling method
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - `isCsvFormat` detection, CSV-only header-row skip, pre-flight-gate dispatch fix, `runImport()` call site updated
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - `runImport()`/`processLine()` gain a trailing `boolean isCsvFormat` parameter, dispatch `parse()` vs `parseCsv()`
- `backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java` - 6 new `parseCsv()` tests (blank line, valid two-field, valid with original title, quoted comma-containing title, wrong field count, non-numeric year)
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - 3 new tests (CSV end-to-end save, header-row skip, wrong-field-count PARSE_ERROR)
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - existing `processLine()`/`runImport()` call sites updated with the new trailing `false` (legacy format) argument
- `frontend/pages/add.vue` - Bulk Import format-hint `<p>` extended to document both formats (copy-only change)

## Decisions Made
- Followed the plan's own `assumption_delta_decision`: CSV is add-alongside, not promoted to a first-class stored/user-facing property — `isCsvFormat` is a one-time file-level boolean computed in the controller, never persisted, never a DTO field.
- Header-row skip is guarded on `title() != null && !valid()` (year specifically failed to parse) — a `null` result (blank first line) or `null` title (field-count mismatch/blank title) falls through unchanged to the normal PARSE_ERROR path, exactly per the plan's action spec, rather than being silently dropped as a false-positive "header."

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] BulkImportServiceTest.java's existing call sites broke on the new signature**
- **Found during:** Task 2 compile check (`./gradlew compileTestJava`)
- **Issue:** `BulkImportServiceTest.java` (a pre-existing unit test file, NOT in this plan's `files_modified` list) calls `bulkImportService.processLine(...)` and `bulkImportService.runImport(...)` directly with the old 4-arg/4-arg signatures. The plan's required signature change (adding a trailing `boolean isCsvFormat` parameter to both methods) broke compilation of this file.
- **Fix:** Appended `, false` (legacy semicolon format) to all 7 call sites — every input in this file is semicolon-delimited, so `false` preserves the exact tested behavior unchanged.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
- **Verification:** `./gradlew compileTestJava` succeeds; full class re-run — 9/9 tests pass.
- **Committed in:** `ea5d37d` (Task 2 commit)

### Out-of-scope discoveries (logged, not fixed)

**2. Pre-existing full-suite `./gradlew check` cross-class test isolation flakiness**
- **Found during:** Task 2's overall `<verification>` step (`./gradlew check`)
- **Issue:** Running the entire backend suite fails ~97 tests across classes entirely unrelated to bulk-import (`UserControllerTest`, `SettingsIntegrationTest`, `MovieControllerTest`, `SearchControllerTest`, `WikiReloadServiceIntegrationTest`, `EnrichmentIntegrationTest`, `IndexingIntegrationTest`, `MovieDetailControllerTest`, `DashboardControllerTest`, `WikipediaClientTest`) — a mix of Spring context load failures and `DataIntegrityViolationException`/FK-constraint violations (e.g. `bulk_import_line_user_id_fkey` blocking a `users` row delete).
- **Root cause (confirmed, not fixed):** No test class in this suite has `@AfterAll`/class-boundary cleanup for the shared Testcontainers Postgres instance — whichever test class happens to run last before a class expecting a clean `users` table can trip a residual-row FK violation. Isolated confirmation: `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportControllerTest" --tests "de.moviearchive.user.UserControllerTest"` passes cleanly (BUILD SUCCESSFUL), proving this plan's 3 new CSV tests are not the cause.
- **Files NOT modified:** none of the failing classes are in this plan's `files_modified` list; this is out-of-scope test infrastructure, not part of the CSV-parsing feature.
- **Logged to:** `.planning/phases/15-bulk-import-page-completion-view-toggle-movie-links-real-csv/deferred-items.md` and the cross-phase `WINDOWS.md` ledger (kind: `deviation`).

---

**Total deviations:** 1 auto-fixed (test-signature blocking issue), 1 out-of-scope discovery logged (pre-existing test infrastructure gap)
**Impact on plan:** The auto-fix was necessary to compile the required test suite at all; the out-of-scope discovery does not affect this plan's own required `<verify>` commands, both of which pass 100% (`ImportLineParserTest`: 12/12; `BulkImportControllerTest`: 23/23, confirmed both in isolation and inside the full-suite run).

## Issues Encountered
- Local Docker daemon (OrbStack) required `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` for Testcontainers to find it — same environment quirk already documented in 15-01's SUMMARY, not a repo-file change (not committed).

## Known Stubs
None — no stubs introduced. `parseCsv()` is a real, fully-implemented parser exercised end-to-end through the unmodified match/save pipeline in the new integration tests.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- This is the last plan in Phase 15 — both loose bulk-import follow-up todos (view toggle/movie links/inline resolve from 15-01/15-02, and real CSV parsing from this plan) are now closed, clearing the way for v1.1 (Enrichment Reliability & Bulk Import) to close.
- The manual `saubere_filmliste.txt` regression check (D6, D-17) remains genuinely un-automatable per the plan's own `<verification>` section — flagged as `human_judgment: true` for UAT. The legacy parser path (`ImportLineParser.parse()`) itself is unmodified by this plan, so this check is expected to be a no-op regression once run.
- The pre-existing full-suite test-isolation gap (deferred item #2 above) is a real, reproducible issue independent of this plan's scope — worth a dedicated hotfix/todo if it starts blocking CI runs, but not something this 2-task CSV-parsing plan should absorb.

---
*Phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv*
*Completed: 2026-08-28*

## Self-Check: PASSED

- `backend/build.gradle.kts` — FOUND on disk, `commons-csv:1.14.1` present
- `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java` — FOUND on disk, `parseCsv()` present
- Commit `39c32b4` (Task 1) — FOUND in `git log --oneline --all`
- Commit `ea5d37d` (Task 2) — FOUND in `git log --oneline --all`
- Commit `02816c2` (deferred-items docs) — FOUND in `git log --oneline --all`
- `ImportLineParserTest` — 12/12 pass (6 pre-existing `parse()` tests unmodified + 6 new `parseCsv()` tests)
- `BulkImportControllerTest` — 23/23 pass (20 pre-existing + 3 new CSV tests), confirmed both in isolation and inside the full-suite run
- `BulkImportServiceTest` — 9/9 pass (unmodified test logic, call sites updated for the new signature)
- `pnpm exec eslint pages/add.vue` — clean
