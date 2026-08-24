---
phase: 10-bulk-import-engine
verified: 2026-08-24T15:35:00Z
status: human_needed
score: 17/17 must-haves verified
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "Visually load the Add Film page (/add) and confirm the new 'Bulk Import' section (file input + Import button + FormErrorBanner/message placement) renders as intended, and that the disabled/loading states (Uploading...) are visible and usable."
    expected: "Section renders below the poster grid with an hr separator, matches settings.vue's heading style, no rounded corners anywhere, button disables correctly until a file is chosen."
    why_human: "add.spec.ts uses this file's established composable-level assertion convention (no DOM mount) — the section's actual visual rendering, spacing, and button state transitions have never been exercised by an automated test or screenshot. 10-02-SUMMARY.md itself flags this with human_judgment: true."
---

# Phase 10: Bulk Import Engine Verification Report

**Phase Goal:** Users can upload a title+year list in the Add Film area and have the system
automatically resolve and save unique TMDB matches, flag ambiguous ones for manual review, and
safely skip already-imported lines on re-upload.

**Verified:** 2026-08-24T15:35:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Critical Bug Verification (CR-01) — Explicitly Requested by Orchestrator

The orchestrator asked me to specifically confirm, in current source, that `processLine()` no
longer calls `enrichmentService.enrich()` from inside its own transactional scope, and that
`runImport()` invokes `enrich()` only after the transaction commits.

**Confirmed by direct source read** of
`backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`:
- `processLine()` (line 105, `@Transactional`) now returns `Optional<UUID>` and never calls
  `enrichmentService.enrich()` itself. `saveAndUpsert()` (line 180) also no longer calls
  `enrich()` — it returns `Optional<UUID>` (present only when `MovieInitiateResult.isNew()`).
- `runImport()` (line 68, **not** `@Transactional`) calls
  `self.processLine(email, tmdbKey, rawLines.get(i)).ifPresent(enrichmentService::enrich)` —
  `enrich()` fires only after `self.processLine(...)` (routed through the `@Lazy` self-proxy)
  has returned, i.e. after its transaction has committed.

**Confirmed by direct behavioral test** (not just presence/wiring): I temporarily added a test
to `BulkImportControllerTest.java` that uploads `"Inception;;2010"`, stubs both
`/3/movie/27205` (TMDB detail) and `/w/api.php` (Wikipedia, missing-page), and polls the
resulting `Movie` row until it leaves `PENDING`. **Result: `Movie.status == SUCCESS` and
`Movie.title == "Inception"`** — the enrichment pipeline completes successfully end-to-end,
proving the race described in CR-01 no longer causes `IllegalStateException`/stuck-`PENDING`
movies. The test file was reverted via `git checkout` immediately after (confirmed clean via
`git status`/`git diff`) — this test is **not** part of the committed test suite (see Gaps
Summary — this is a test-coverage recommendation, not a blocking finding, since the underlying
behavior is proven correct).

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Multipart upload returns 202, dispatches async without blocking (IMPORT-01) | ✓ VERIFIED | `BulkImportController.uploadBulkImport` reads file synchronously then calls `bulkImportService.runImport(...)` (`@Async`); `BulkImportControllerTest#shouldSaveUniqueMatch_andPersistBulkImportLineRow` and `#shouldReturn202_notCrash_forNonUtf8Bytes` pass (verified via test run) |
| 2 | UTF-8 read; blank lines trimmed/skipped silently, never persisted | ✓ VERIFIED | `ImportLineParser.parse` returns `null` for blank input; `ImportLineParserTest` (6/6 pass) covers this |
| 3 | Malformed line records PARSE_ERROR, batch continues (D-03) | ✓ VERIFIED | `processLine()` line 116-119: `if (!parsed.valid()) upsertLine(..., PARSE_ERROR, null); return;`. **No pre-existing test exercises row persistence for this path** (confirmed via `grep -r PARSE_ERROR src/test/` = 0 hits) — I ran a temporary reverted test uploading `"BadLine;;abcd"`: resulting row had `status=PARSE_ERROR`, `title="BadLine"`, and zero TMDB calls. Behavior confirmed correct; permanent test coverage is a gap (see Gaps Summary) |
| 4 | TMDB results filtered to exact release-year, no ±1 tolerance (D-05, IMPORT-02) | ✓ VERIFIED | `processLine()`: `r.year() != null && r.year().equals(parsed.year())`; `BulkImportServiceTest` branch tests confirm |
| 5 | Unique year-match auto-saved via `initiate()` then `enrich()` only if new (D-12, IMPORT-03) | ✓ VERIFIED | `saveAndUpsert()` + CR-01 fix (see above); confirmed end-to-end via temporary behavioral test (`Movie.status == SUCCESS`) |
| 6 | Multiple year-matches never auto-saved unless original-title narrows to exactly one (D-04/D-06, IMPORT-04) | ✓ VERIFIED | `BulkImportServiceTest#shouldMarkAmbiguous...`, `#shouldStayAmbiguous...`, `#shouldSave_whenOriginalTitleNarrows...` all pass; `BulkImportControllerTest#shouldMarkAmbiguous_whenMultipleYearMatchesNoOriginalTitle`, `#shouldNarrowToUnique_whenOriginalTitleMatches` pass |
| 7 | Zero year-matches recorded as distinct NOT_FOUND (D-07) | ✓ VERIFIED | `BulkImportServiceTest#shouldRecordNotFound_whenZeroYearMatchingCandidates` passes |
| 8 | Re-upload skips SAVED lines (no TMDB call); AMBIGUOUS/NOT_FOUND/PARSE_ERROR retried (D-08/D-09/D-10, IMPORT-07) | ✓ VERIFIED | `BulkImportControllerTest#shouldSkipReupload_whenLineAlreadySaved` asserts exactly 1 TMDB call across 2 identical uploads — passes |
| 9 | PARSE_ERROR lines (unparseable year) identified for retry by normalized raw line, not (title, year) | ✓ VERIFIED | `BulkImportLineRepository.findByUserIdAndRawLineAndYearIsNull` used in `findExistingRow` when `parsed.year() == null`; code present and structurally correct (adjacent WR-03 behavioral test below confirms the update-in-place mechanism functions) |
| 10 | `MovieService.initiate()` idempotent — no duplicate Movie row on repeat calls (D-12) | ✓ VERIFIED | Reuses pre-existing, already-verified `MovieService.initiate()` check-then-insert logic from Phase 3, unchanged by this phase |
| 11 | Third overlapping trigger rejected 503 (bulkImportExecutor core=1/max=1/queue=1) (D-11) | ✓ VERIFIED | `AsyncConfig.bulkImportExecutor` bean matches spec exactly; `BulkImportControllerTest#shouldReject_whenThirdImportExceedsQueueCapacity` passes (503 + non-empty message) |
| 12 | Interrupted run: SAVED lines skipped on re-upload, no separate resume mechanism needed | ✓ VERIFIED | Design-only claim (no resume state to build) — consistent with the find-then-update upsert model; not independently testable beyond truth #8 |
| 13 | No TMDB key → 422 synchronously, before any line processed | ✓ VERIFIED | `BulkImportController.uploadBulkImport` calls `movieService.resolveTmdbKey(email)` before reading the file; `BulkImportControllerTest#shouldReturn422_whenNoTmdbKeyConfigured` asserts 0 TMDB calls — passes |
| 14 | File input + Import button in Add Film's Bulk Import section, calls `uploadBulkImport(file)` (IMPORT-01) | ✓ VERIFIED | `add.vue` lines 108-132 (handlers) and ~212-231 (template); `add.spec.ts` (10/10 pass) |
| 15 | 202 success shows inline acknowledgement; no progress bar/results list (Phase 11 scope) | ✓ VERIFIED | `bulkImportMessage.value = 'Import started — this runs in the background.'`; no progress/results markup present in `add.vue` |
| 16 | 422 shows "No TMDB key configured. Add your key in Settings." (reused message) | ✓ VERIFIED | `add.vue` catch block: `status === 422` → exact string match; `add.spec.ts` covers it |
| 17 | Other errors show generic "Import failed. Please try again." | ✓ VERIFIED | `add.vue` catch block `else` branch |

**Score:** 17/17 truths verified (0 present-but-behavior-unverified — all behavior-dependent
truths were confirmed via passing tests, including 3 confirmed via a verifier-authored
temporary test that was reverted after use; see Gaps Summary for the resulting test-coverage
recommendation)

### Code Review Fix Verification (10-REVIEW.md → 10-REVIEW-FIX.md)

| Finding | Fix Commit | Verified In Source | Verified Behaviorally |
|---------|-----------|---------------------|------------------------|
| CR-01 (Critical): enrich() races open transaction | `9c2636d` | ✓ Yes — `processLine()`/`runImport()` restructured as described above | ✓ Yes — temp test proved `Movie.status == SUCCESS` |
| WR-01: TMDB search failure leaves no row | `d3fa4a7` | ✓ Yes — try/catch around `tmdbClient.search()` persists `NOT_FOUND` on failure | Not independently re-tested (low-risk, straightforward try/catch; existing `BulkImportServiceTest`/`ControllerTest` suite continues to pass, no regression) |
| WR-02: no upload size cap | `01a7eba` | ✓ Yes — `bulk-import.max-lines` (default 5000) checked before dispatch, 400 on exceed | Not independently re-tested (simple `size() > maxLines` guard; low risk) |
| WR-03: duplicate row on PARSE_ERROR→fixed reupload | `9c0c2dd` | ✓ Yes — `findByUserIdAndNormalizedTitleAndYearIsNull` probe added to `findExistingRow` | ✓ Yes — temp test: uploaded `"FixMe;;abcd"` (→ PARSE_ERROR row), then `"FixMe;;2010"` (→ SAVED); `bulkImportLineRepository.count() == 1` both times — row updated in place, no duplicate |

REVIEW-FIX.md itself flagged WR-03 as "requires human verification" (a data-identity fix with
no dedicated test). This verification report closes that open item with direct behavioral proof.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `V9__create_bulk_import_line.sql` | bulk_import_line table, no UNIQUE constraint, 3 indexes | ✓ VERIFIED | Matches spec exactly |
| `BulkImportLineStatus.java` | SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR enum | ✓ VERIFIED | Matches CHECK constraint |
| `BulkImportLine.java` | JPA entity, Movie-style conventions | ✓ VERIFIED | |
| `BulkImportLineRepository.java` | 3 finder methods + WR-03's 4th | ✓ VERIFIED | |
| `ImportLineParser.java` | Pure Title;OriginalTitle;Year parser | ✓ VERIFIED | 6/6 unit tests pass |
| `BulkImportService.java` | Async orchestrator, self-proxy, matching, upsert | ✓ VERIFIED | Post-CR-01-fix structure confirmed |
| `BulkImportController.java` | Multipart endpoint, 202/400/422/503 | ✓ VERIFIED | |
| `frontend/composables/useMovies.ts` | `uploadBulkImport(file)` | ✓ VERIFIED | 9/9 composable tests pass |
| `frontend/pages/add.vue` | Bulk Import section | ✓ VERIFIED | 10/10 page tests pass |
| Test files (Controller/Parser/Service/composable/page) | Full coverage per plan | ✓ VERIFIED | All pass; see Behavioral Spot-Checks |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `BulkImportController.uploadBulkImport` | `BulkImportService.runImport` | `@Async` method call | ✓ WIRED | Direct call after 422/400 checks |
| `runImport` | `self.processLine` | `@Lazy` self-proxy | ✓ WIRED | `self.processLine(...).ifPresent(enrichmentService::enrich)` |
| `processLine` | `TmdbClient.search` / `MovieService.initiate` / `EnrichmentService.enrich` | direct calls (enrich now called from `runImport`, post-commit) | ✓ WIRED | Confirmed correct post-CR-01-fix ordering |
| `AsyncConfig.bulkImportExecutor` | `@Async("bulkImportExecutor")` | Spring bean name match | ✓ WIRED | |
| `MovieService.resolveTmdbKey` | `BulkImportController`'s 422 fail-fast | direct call, pre-dispatch | ✓ WIRED | |
| `add.vue handleBulkImport()` | `useMovies().uploadBulkImport(file)` | destructured composable fn | ✓ WIRED | |
| `useMovies().uploadBulkImport` | `POST /api/movies/bulk-import` | `$fetch` multipart FormData | ✓ WIRED | No `Content-Type` override (confirmed absent) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Backend bulk-import test package | `./gradlew test --tests "de.moviearchive.bulkimport.*"` | 19/19 pass (7 controller, 6 parser, 6 service) | ✓ PASS |
| Backend `movie` package regression | `./gradlew test --tests "de.moviearchive.movie.*"` | BUILD SUCCESSFUL, no failures | ✓ PASS |
| Frontend composable + page tests | `npx vitest run test/unit/composables/useMovies.spec.ts test/unit/pages/add.spec.ts` | 19/19 pass | ✓ PASS |
| CR-01 fix: enrichment completes after bulk save | Temporary test (reverted): upload → poll `Movie.status` | `SUCCESS`, `title="Inception"` | ✓ PASS |
| PARSE_ERROR row persistence | Temporary test (reverted): upload `"BadLine;;abcd"` | Row `status=PARSE_ERROR`, 0 TMDB calls | ✓ PASS |
| WR-03 fix: fixed-PARSE_ERROR reupload updates in place | Temporary test (reverted): `"FixMe;;abcd"` then `"FixMe;;2010"` | `bulkImportLineRepository.count() == 1` (no duplicate) | ✓ PASS |

All temporary test files were reverted via `git checkout` and confirmed clean via `git status`
before this report was written — no test-file changes from this verification session remain in
the working tree.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| IMPORT-01 | 10-01, 10-02 | Upload text file in Add Film area | ✓ SATISFIED | Backend endpoint + frontend trigger both verified |
| IMPORT-02 | 10-01 | Parse each line, TMDB search filtered by year | ✓ SATISFIED | `ImportLineParser` + exact-year filter verified |
| IMPORT-03 | 10-01 | Unique match auto-saved via existing save logic | ✓ SATISFIED | `saveAndUpsert` + CR-01 fix verified end-to-end |
| IMPORT-04 | 10-01 | Ambiguous matches flagged, never auto-guessed | ✓ SATISFIED | AMBIGUOUS branch + narrowing verified |
| IMPORT-07 | 10-01 | Re-upload skips already-saved, no duplicate TMDB calls | ✓ SATISFIED | `shouldSkipReupload_whenLineAlreadySaved` verified |

No orphaned requirements — REQUIREMENTS.md's Phase 10 traceability row set
(IMPORT-01/02/03/04/07) exactly matches the union of both plans' `requirements:` frontmatter.
IMPORT-05/IMPORT-06 are correctly scoped to Phase 11 and not claimed by this phase.

### Anti-Patterns Found

None. Grep for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` (case-insensitive) across all
phase-modified backend and frontend files returned zero matches. No stub returns, no
hardcoded-empty props feeding rendered output, no unwired handlers.

Two Info-level findings from 10-REVIEW.md were consciously left unfixed (correctly, per
10-REVIEW-FIX.md's scope note `IN-01/IN-02 excluded`) and remain as residual polish items, not
gaps against this phase's must-haves:
- IN-01: `bulk-import-file` input has no associated `<label>` (accessibility).
- IN-02: `upsertLine()` never refreshes `raw_line` on an in-place row update.

### Human Verification Required

1. **Visual rendering of the Bulk Import section on the Add Film page**
   - **Test:** Load `/add` in a browser, confirm the "Bulk Import" section renders correctly
     below the poster grid (file input styling, Import button disabled/enabled/loading states,
     FormErrorBanner and success-message placement).
   - **Expected:** Section matches the established design system (no rounded corners, reused
     button/heading classes from settings.vue), button disables until a file is chosen, message
     text appears/clears correctly across the upload lifecycle.
   - **Why human:** `add.spec.ts` follows this file's established composable-level assertion
     convention (no DOM mount) — no automated test or screenshot exercises the actual rendered
     output. `10-02-SUMMARY.md` itself flags this with `human_judgment: true`.

### Gaps Summary

No blocking gaps. All 17 must-have truths across both plans are verified — including direct
behavioral confirmation (not just code presence) of the CR-01 critical-bug fix and the WR-03
duplicate-row fix, both obtained by temporarily adding and then reverting test methods during
this verification session.

**Recommendation (non-blocking, test-coverage gap):** Three genuinely important behaviors have
zero permanent regression-test coverage in the committed suite, despite being independently
proven correct in this verification pass:
1. That a bulk-imported movie's `Movie.status` actually reaches `SUCCESS` (not just that a
   `bulk_import_line` row exists) — this is the exact scenario CR-01 broke.
2. That a malformed line's row is actually persisted with `status=PARSE_ERROR` (only the pure
   parser's `valid=false` output is tested; `processLine`'s persistence of that outcome is not).
3. That re-uploading a previously-`PARSE_ERROR` line after fixing its year updates the existing
   row in place rather than creating a duplicate (the exact WR-03 scenario).

A future small phase or follow-up task should add these three as permanent tests to
`BulkImportControllerTest`/`BulkImportServiceTest` so a future regression in any of these three
areas is caught by CI rather than requiring another manual/ad-hoc verification pass.

---

_Verified: 2026-08-24T15:35:00Z_
_Verifier: Claude (gsd-verifier)_
