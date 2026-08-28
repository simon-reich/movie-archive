---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
verified: 2026-08-28T14:25:58Z
status: human_needed
score: 14/16 must-haves verified
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "Toggle to list view on /imports/{batchId}, do a real hard browser reload (not a component remount), confirm list view is still selected."
    expected: "View mode persists across a genuine full-page reload, not just a re-mounted component with pre-seeded localStorage."
    why_human: "D-02's must_haves truth is tagged verification:backstop — the automated test only pre-seeds window.localStorage before mounting the component in Vitest/happy-dom, which proves the read-on-mount code path works but does not exercise an actual browser navigation/reload cycle."
  - test: "Open a real bulk-import batch with mixed statuses (SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR) in a browser. Click a SAVED card and confirm it navigates to /movies/{id}. Visually confirm PARSE_ERROR reads as a clearly distinct category (not just another status icon)."
    expected: "Navigation works; PARSE_ERROR is visually distinguishable at a glance."
    why_human: "Plan 15-01's own <verification> section marks this 'not automatable' — requires a real browser session; Vitest unit tests only assert href/testid presence, not visual perception."
  - test: "Expand the resolve widget on a real AMBIGUOUS or NOT_FOUND line, run a live TMDB search, pick a candidate, and confirm the batch report immediately shows SAVED with a working movie link."
    expected: "End-to-end resolve flow works against the real TMDB API and real backend, not mocked fetch calls."
    why_human: "Plan 15-02's own <verification> section marks this 'not automatable' — unit tests mock useMovies().searchTmdb() and useBulkImport().resolveLine(); no test exercises the real TMDB API or a real running backend."
  - test: "Run a real bulk import against saubere_filmliste.txt (repo root, untracked, 1139 lines, semicolon format) using the live app stack (TMDB key, DB, SSE progress) and confirm every line resolves to the identical per-line outcome it would have produced before this phase."
    expected: "No behavioral change for the legacy semicolon format — a no-op regression check."
    why_human: "D-17's must_haves truth is tagged verification:backstop and Plan 15-03's own <verification> section marks this 'manual-only' — requires the live app stack, TMDB API key, and a 1139-line real-world file; not something a non-interactive worktree executor can run."
---

# Phase 15: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing Verification Report

**Phase Goal:** Close out the two loose bulk-import todos left over from Phases 10-11: the batch detail page needs a view toggle, movie links, and inline ambiguous-match resolution (todo from 2026-08-25), and bulk-import parsing needs to move from the strict `Title;OriginalTitle;Year` format to real CSV parsing with proper quoting (todo from 2026-08-24, was v2 candidate SET-06, pulled forward into this milestone at the user's request).
**Verified:** 2026-08-28T14:25:58Z
**Status:** human_needed
**Re-verification:** No — initial verification (a prior attempt was interrupted mid-way before writing any VERIFICATION.md; this is a clean restart, not a resume)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | SAVED bulk-import line's card/row is entirely clickable, navigates to `/movies/{movieId}` (D-05) | ✓ VERIFIED | `[batchId].vue:196-197,293-294` — `<component :is="movieLinkTarget(line) ? 'NuxtLink' : 'div'" :to="movieLinkTarget(line)">`; `movieLinkTarget()` returns `/movies/${line.movieId}` only for SAVED+movieId. Vitest test `wraps a SAVED line in a whole-card link to /movies/{movieId}` passes (ran live: 16/16 pass). |
| 2 | AMBIGUOUS, NOT_FOUND, PARSE_ERROR lines render with no movie link at all (D-07) | ✓ VERIFIED | Same `movieLinkTarget()` guard (`line.status === 'SAVED' && line.movieId`) returns `null` for every other status → renders `<div>`, not `<NuxtLink>`. Test `does not render a movie link for an AMBIGUOUS/NOT_FOUND line` passes. |
| 3 | PARSE_ERROR line displays raw line text verbatim, visually distinct category (D-11 display half) | ✓ VERIFIED | `[batchId].vue:216,311` — `<p data-testid="raw-line-text">{{ line.rawLine }}</p>` inside a `border-[#7A3520]`-styled block, distinct `data-testid="parse-error-card"`. Backend `BulkImportLineResult.rawLine` populated unconditionally in `getBatchDetail()` (`BulkImportController.java:231`). Backend test `shouldExposeRawLine_forParseErrorLines` and frontend test `renders a PARSE_ERROR line with its distinct testid and raw line text` both pass (ran live). |
| 4 | Grid view is the default view on page load (D-01) | ✓ VERIFIED | `[batchId].vue:26` — `const viewMode = ref<'grid'\|'list'>('grid')`. Test `renders grid view by default when no localStorage entry is present` passes. |
| 5 | List view shows small thumbnail + text per row, same status vocabulary as grid, inline not overlay (D-03/D-04) | ✓ VERIFIED | `[batchId].vue:292-386` — `w-16 aspect-[2/3]` thumbnail, `statusLabel()`/`CheckCircle2`/`XCircle` reused inline (not `absolute` overlay, unlike grid). Test `switches to list view when the ViewToggle list button is clicked` asserts `view-list-row` count and shared status text. |
| 6 | Toggling to list view and reloading the page keeps list view selected (localStorage persistence, D-02) | insufficient_spec | Code present and wired: `onMounted()` reads `localStorage.getItem('bulk-import-view-mode')`, `watch(viewMode, ...)` writes it (`[batchId].vue:56-65`). Vitest test `renders list view immediately when localStorage has bulk-import-view-mode=list` only pre-seeds `window.localStorage` before mount — it does not exercise a real full-page reload. Plan itself tags this truth `verification: backstop`. Routed to human verification below. |
| 7 | AMBIGUOUS or NOT_FOUND line can be resolved in-place: expand, fresh TMDB search prefilled with title, pick candidate, save (D-08) | ✓ VERIFIED | `[batchId].vue:132-147` `toggleResolve()` calls `searchTmdb(line.title)` fresh on every expand. Backend `resolveLine()` (`BulkImportService.java:217-231`) saves via `movieService.initiate()`. Backend test `shouldResolveAmbiguousLine_savingMovieAndUpdatingLineStatus` and frontend test `expanding the resolve widget runs a fresh TMDB search...` both pass (ran live). |
| 8 | After a successful resolve, page reflects new SAVED status/tmdbId/movieId by refetching the full batch — never optimistic patch (D-09) | ✓ VERIFIED | `[batchId].vue:151-164` `pickCandidate()` calls `resolveLine()` then `await loadDetail()` (full `getBatchDetail()` refetch), never assigns to `line.status`/`line.movieId` directly. Test `clicking a candidate calls resolveLine with the picked tmdbId/posterPath, then refetches the batch` passes. |
| 9 | Resolving a line updates that specific BulkImportLine row's status/tmdbId/posterPath, not just the movie (D-10) | ✓ VERIFIED | `BulkImportService.resolveLine()` (`BulkImportService.java:224-228`) sets `line.setStatus(SAVED)`, `setTmdbId()`, `setPosterPath()`, `save(line)`. Backend test asserts `bulkImportLineRepository.findById(lineId)` has `status=SAVED` and matching `tmdbId` after resolve. |
| 10 | PARSE_ERROR lines never show a resolve widget (D-11 boundary confirmed) | ✓ VERIFIED | `isResolvable(line)` (`[batchId].vue:100-102`) returns true only for AMBIGUOUS/NOT_FOUND. Test `does not render a resolve-toggle on a PARSE_ERROR line` passes. |
| 11 | A lineId not belonging to the batchId in the URL is rejected — ownership on BOTH batch and line (D-10/T-15-01) | ✓ VERIFIED | `BulkImportLineRepository.findByIdAndBatchId(lineId, batchId)` (new derived query) used inside `resolveLine()`, not a plain `findById`. Backend test `shouldReturn404_whenResolvingLineFromDifferentBatch` (same user, wrong batch → 404) and `shouldReturn403_whenDifferentUserResolvesLine` both pass. |
| 12 | Comma-delimited CSV upload (RFC4180 quoting) parsed and matched exactly like semicolon upload (D-12/D-13) | ✓ VERIFIED | `ImportLineParser.parseCsv()` uses `CSVFormat.DEFAULT`; `commons-csv:1.14.1` resolved in `build.gradle.kts:64`. Backend test `shouldParseCommaDelimitedCsv_andSaveUniqueMatch` (end-to-end through the unmodified match/save pipeline) passes; unit test `shouldParseValidCsvLine_withEmptyOriginalTitle` passes. |
| 13 | A quoted comma-containing title parses as one field, no special-casing (D-15) | ✓ VERIFIED | `ImportLineParserTest.shouldParseQuotedCommaContainingTitle_asOneField` passes — `CSVFormat.DEFAULT`'s built-in quoting, zero extra code in `parseCsv()`. |
| 14 | Optional CSV header row (first row's Year not numeric) auto-detected and skipped, no explicit flag (D-14) | ✓ VERIFIED | `BulkImportController.java:124-129` — skip guarded on `title() != null && !valid()`, scoped to `isCsvFormat` only. Backend test `shouldSkipHeaderRow_whenFirstCsvLineHasNonNumericYear` asserts exactly one persisted line (header produced zero rows). |
| 15 | Existing semicolon format keeps working completely unchanged, same parser/behavior/tests green (D-16) | ✓ VERIFIED | `ImportLineParser.parse()` is byte-for-byte unmodified by this phase (confirmed via diff — only `parseCsv()` was added as a new sibling method). All 6 pre-existing `parse()` unit tests and `shouldSaveUniqueMatch_andPersistBulkImportLineRow` (semicolon, unmodified input) still pass. |
| 16 | `saubere_filmliste.txt` (1139 lines, semicolon format) still imports with identical per-line outcomes — real-world backward-compat check | insufficient_spec | Legacy parser path (`ImportLineParser.parse()`) is provably unmodified (truth #15), so this is a low-risk no-op check in principle, but the plan itself tags this truth `verification: backstop` and its own `<verification>` section marks it "manual-only" — no automated test runs the actual 1139-line file against a live stack. Routed to human verification below. |

**Score:** 14/16 truths verified (2 present-and-plausible, human-verification required per each plan's own `backstop` tag)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/.../dto/BulkImportLineResult.java` | carries `id`/`movieId`/`rawLine` | ✓ VERIFIED | Record fields present exactly as specified (read on disk). |
| `frontend/pages/imports/[batchId].vue` | view toggle, movie links, PARSE_ERROR display, resolve widget | ✓ VERIFIED | All four features present, wired to real composable calls, no stubs. |
| `backend/.../dto/ResolveLineRequest.java` | `tmdbId` (@Positive), `posterPath` (nullable) | ✓ VERIFIED | Matches spec exactly. |
| `POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve` | ownership-scoped resolve endpoint | ✓ VERIFIED | Present in `BulkImportController.java:247-257`; ownership on batch via `loadOwnedBatch()`, on line via `findByIdAndBatchId()`. |
| `backend/.../ImportLineParser.java` (`parseCsv()` sibling) | RFC4180-style CSV parser | ✓ VERIFIED | Present, returns identical `ParsedLine` contract, never throws. |
| `org.apache.commons:commons-csv:1.14.1` in `build.gradle.kts` | new dependency | ✓ VERIFIED | `backend/build.gradle.kts:64`. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `BulkImportController.getBatchDetail()` | `MovieRepository.findByUserIdAndTmdbId()` | `movieId` resolution for SAVED lines | ✓ WIRED | Real DB query, scoped by batch owner's `userId` + `tmdbId`, zero cost for non-SAVED lines. |
| `[batchId].vue` `viewMode` ref | `localStorage['bulk-import-view-mode']` | `onMounted()` read, client-only `watch()` write | ✓ WIRED | Both directions present, SSR-guarded. |
| resolve endpoint | `BulkImportLineRepository.findByIdAndBatchId()` → `BulkImportService.resolveLine()` → `MovieService.initiate()` | ownership-scoped save+update | ✓ WIRED | Confirmed by code read and by cross-batch-404 test. |
| frontend resolve widget | `useMovies().searchTmdb()` → `useBulkImport().resolveLine()` → `loadDetail()` refetch | search-pick-save-refetch | ✓ WIRED | Confirmed by code read and by "calls resolveLine ... then refetches the batch" test. |
| `BulkImportController.uploadBulkImport()` format-sniff | `BulkImportService.runImport(..., isCsvFormat)` → `processLine()` dispatch | file-level parser selection | ✓ WIRED | `isCsvFormat` threaded through both method signatures; confirmed by CSV end-to-end test and unchanged-semicolon regression test. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `getBatchDetail()` | `movieId` | `MovieRepository.findByUserIdAndTmdbId()` | Yes | ✓ FLOWING |
| `getBatchDetail()` | `rawLine` | `BulkImportLine.rawLine` (persisted at upload time) | Yes | ✓ FLOWING |
| resolve widget | `results` (candidates) | `useMovies().searchTmdb()` → live TMDB `/movies/search` | Yes | ✓ FLOWING |
| CSV upload | parsed fields | `CSVParser.parse()` on real uploaded bytes | Yes | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Backend CSV parser unit tests exist and pass | `./gradlew test --tests "de.moviearchive.bulkimport.ImportLineParserTest"` | BUILD SUCCESSFUL, 12/12 (verified via test-results XML) | ✓ PASS |
| Backend controller tests (movie links, resolve endpoint, CSV upload) pass | `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportControllerTest"` (with `DOCKER_HOST` set for OrbStack) | BUILD SUCCESSFUL, `tests="23" failures="0" errors="0"` (`TEST-...xml`, ran live in this verification session) | ✓ PASS |
| Frontend batch-detail page tests (view toggle, movie links, PARSE_ERROR, resolve widget) pass | `pnpm vitest run test/unit/pages/imports-batchId.spec.ts` | `Tests 16 passed (16)` (ran live in this verification session) | ✓ PASS |
| `commons-csv` dependency resolves | grep `build.gradle.kts` | `implementation("org.apache.commons:commons-csv:1.14.1")` present | ✓ PASS |
| No debt markers (TODO/FIXME/XXX/TBD) in phase-modified files | `grep -rn` across all `key-files` from the 3 SUMMARYs | No matches | ✓ PASS |

### Requirements Coverage

Phase 15 has no formal `REQUIREMENTS.md` requirement IDs — `ROADMAP.md`'s own Phase 15 entry states this explicitly ("no formal REQUIREMENTS.md IDs yet, to be refined in 15-CONTEXT.md"), and `15-CONTEXT.md` defines D-01 through D-17 as the phase's actual requirement set. All 17 decisions (D-01–D-17) are covered by the 16 truths above (D-16 and D-17 both feed into the "existing format unchanged" / "real-world regression" pair).

| Requirement source | Description | Status | Evidence |
|---------------------|-------------|--------|----------|
| D-01–D-11 (`15-CONTEXT.md`) | View toggle, movie links, inline resolve | ✓ SATISFIED | Truths 1–11 above |
| D-12–D-17 (`15-CONTEXT.md`) | Real CSV parsing, legacy format preserved | ✓ SATISFIED (D-17 needs human confirmation) | Truths 12–16 above |

**Stale REQUIREMENTS.md sections (documentation debt, not a functional gap):** `.planning/REQUIREMENTS.md` still lists **IMPORT-V2-01** ("Manuelle Auflösung mehrdeutiger Treffer...") under `## Future Requirements` and **CSV-Import (SET-06)** under `## Out of Scope`, both described as deferred/excluded from v1.1. This phase's own goal statement and `15-CONTEXT.md` confirm both were explicitly "pulled forward into this milestone at the user's request" and are now implemented (D-08–D-10 for IMPORT-V2-01's intent, D-12–D-17 for SET-06). `REQUIREMENTS.md` was last touched in Phase 10 (commit `504de05`) and has not been updated to reflect this scope change — a future reader of `REQUIREMENTS.md` alone would incorrectly conclude these features are still unbuilt. Recommend a follow-up doc update (move IMPORT-V2-01 out of Future Requirements into the v1.1 table, mark CSV-Import as done with the D-12–D-17 caveat about the 3-column-only scope) before milestone close.

### Anti-Patterns Found

None. Grepped every file listed in all three plans' `key-files`/`files_modified` for `TODO|FIXME|XXX|TBD|HACK|PLACEHOLDER|placeholder|coming soon|not yet implemented` and empty-implementation patterns (`return null`, hardcoded `[]`/`{}` flowing to render) — no matches. `Known Stubs` sections in all three SUMMARYs claim "none," and this was independently confirmed by reading the actual DTO/controller/service/Vue code rather than trusting the claim.

### Code Review Cross-Check (15-REVIEW.md)

`15-REVIEW.md` (standard depth, 13 files, 1 Critical / 5 Warning / 3 Info) was read and cross-referenced against the actual code:

- **CR-01 (Critical) — confirmed real, but pre-existing, not introduced by this phase.** `BulkImportService.findExistingRow()`/`upsertLine()` scope the "reuse this row on re-upload" lookup by `(userId, normalizedTitle, year)` only, never by `batchId` — confirmed by direct code read (`BulkImportService.java:337-367`, `row.setBatch(batch)` unconditional on line 347). Re-uploading an overlapping title/year that is currently AMBIGUOUS/NOT_FOUND/PARSE_ERROR in an older batch silently reassigns that row to the new batch, corrupting the older batch's `totalLines`/status-count accounting. **Traced via `git log -p`: this exact method shape originates in commit `eff92a5` (Phase 10, "tracer - end-to-end single-line bulk import") — none of Plan 15-01/15-02/15-03 touch `findExistingRow()` or `upsertLine()`'s reuse logic.** None of this phase's own must-haves (D-01–D-17) assert anything about cross-batch dedup correctness, so this finding does not FAIL any declared truth for this phase and is not treated as a BLOCKER here. It is, however, a genuine, previously-undiscovered data-integrity bug that directly undermines the "batch report is a complete/trustworthy accounting" premise this phase's own view-toggle/movie-link/PARSE_ERROR-traceability work depends on — flagged as a WARNING requiring a human decision on whether to open a follow-up bugfix phase/todo before relying on batch-detail line counts for anything consequential.
- **WR-01–WR-05, IN-01–IN-03** — all confirmed as described in `15-REVIEW.md` by spot-reading the cited line ranges (`BulkImportService.java`, `BulkImportController.java`, `useBulkImport.ts`, `ImportLineParser.java`, `BulkImportControllerTest.java`, `add.vue`). None of these block any of this phase's declared must-haves; they are legitimate robustness/quality follow-ups (SSE reconnect-on-transient-error, parser duplication, a `Thread.sleep`-based flaky test, a `posterUrl()` duplication, unbounded year magnitude) consistent with the review's own "Warning"/"Info" severity classification.

## Human Verification Required

See frontmatter `human_verification` — 4 items, all traceable to either a `verification: backstop` must_haves truth or a plan's own documented "not automatable" manual spot-check. None of these represent a code gap; they represent genuine judgment/live-environment checks the executor cannot perform in a non-interactive worktree.

### Gaps Summary

No gaps found against this phase's declared must-haves (D-01–D-17) — all 14 automatable truths are VERIFIED with passing tests re-run live during this verification session (backend: `ImportLineParserTest` 12/12, `BulkImportControllerTest` 23/23; frontend: `imports-batchId.spec.ts` 16/16). The 2 remaining truths are correctly self-flagged by the plans as `backstop`-tier (real-browser-reload persistence, and a 1139-line real-file import) and are routed to human verification rather than either a false PASS or a false FAIL.

The one open concern outside this phase's own truth set is **CR-01** (pre-existing cross-batch row-reassignment bug, see Code Review Cross-Check above) — not a gap in this phase's delivered scope, but worth a deliberate human decision (accept as known pre-existing debt vs. open a follow-up fix) before the batch-detail page's line counts are treated as fully trustworthy.

---

_Verified: 2026-08-28T14:25:58Z_
_Verifier: Claude (gsd-verifier)_
