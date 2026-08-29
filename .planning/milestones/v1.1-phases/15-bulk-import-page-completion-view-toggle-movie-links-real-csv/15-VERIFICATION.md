---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
verified: 2026-08-28T22:40:00Z
status: passed
score: 21/21 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 14/16
  gaps_closed:
    - "SAVED bulk-import line's card/row navigates to /movies/{movieId} in a real browser (was broken by a Nuxt :is-string-ternary component-resolution bug invisible to stubbed tests — fixed in 15-04 via resolveComponent('NuxtLink'), confirmed by human UAT test 2)"
    - "PARSE_ERROR lines are visually distinct, always render as a row with untruncated raw text, in both grid and list view (fixed in 15-04, confirmed by human UAT test 2)"
    - "Results are grouped into four fixed, ordered, separately-headed sections: Saved -> Ambiguous -> Not found -> Parse error (new requirement surfaced during UAT, implemented in 15-04, confirmed by human UAT test 2)"
    - "Resolve widget's candidate picker renders at full container width with recognizable poster sizes (fixed in 15-04, confirmed indirectly by the fact the follow-up UAT re-test found only a missing-label issue, not a sizing issue)"
    - "Resolve widget candidates show a visible title+year label, not poster-only (new requirement surfaced during UAT as G-15-4, implemented in 15-05)"
    - "Resolve widget candidate labels wrap instead of truncating so long titles + years remain fully visible (new requirement surfaced during UAT as G-15-5, implemented in 15-06, confirmed by human UAT test 3)"
    - "List view mode persists across a genuine full-page browser reload (previously insufficient_spec/backstop — closed by human UAT test 1, live hard reload, pass)"
    - "saubere_filmliste.txt (1139-line real-world file) imports with identical per-line outcomes under the new dual-format parser (previously insufficient_spec/backstop — closed by human UAT test 4, live app stack, pass)"
  gaps_remaining: []
  regressions: []
gaps: []
deferred: []
human_verification: []
---

# Phase 15: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing Verification Report

**Phase Goal:** Close out the two loose bulk-import todos left over from Phases 10-11: the batch detail page needs a view toggle, movie links, and inline ambiguous-match resolution (todo from 2026-08-25), and bulk-import parsing needs to move from the strict `Title;OriginalTitle;Year` format to real CSV parsing with proper quoting (todo from 2026-08-24, was v2 candidate SET-06, pulled forward into this milestone at the user's request).
**Verified:** 2026-08-28T22:40:00Z
**Status:** passed
**Re-verification:** Yes — after gap closure (plans 15-04, 15-05, 15-06) and confirmed live human UAT (15-UAT.md, status: complete, 4/4 passed, 0 open issues)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A SAVED line's card/row is a real, working navigable `NuxtLink` to `/movies/{movieId}` (D-05, G-15-2 fix) | ✓ VERIFIED | `[batchId].vue:22,240,341` — `const NuxtLink = resolveComponent('NuxtLink')`, both `:is` bindings use the captured reference, not a bare string. Source-level regression test `resolves NuxtLink via resolveComponent() instead of a bare string` passes (ran live: 27/27). Human UAT test 2 confirmed live-browser click navigates to `/movies/{id}` — pass. |
| 2 | AMBIGUOUS/NOT_FOUND/PARSE_ERROR lines render no movie link (D-07) | ✓ VERIFIED | `movieLinkTarget()` (`[batchId].vue:111-113`) returns `null` for every non-SAVED status; PARSE_ERROR is structurally excluded from `orderedCards` entirely. Test `does not render a movie link for an AMBIGUOUS/NOT_FOUND line` passes. |
| 3 | PARSE_ERROR always renders as a row (icon + full untruncated raw text), never a poster card, identical in grid and list view (D-11, G-15-2 fix) | ✓ VERIFIED | `[batchId].vue:433-456` — `<section data-testid="parse-error-section">` rendered unconditionally, outside the `viewMode` toggle. `raw-line-text` uses `break-all`, no `truncate`. Tests `renders a PARSE_ERROR line as an always-row...` and `renders the identical PARSE_ERROR row content after toggling to list view` pass. Human UAT test 2 confirmed live — pass. |
| 4 | Grid view is the default on load (D-01) | ✓ VERIFIED | `[batchId].vue:35` — `const viewMode = ref<'grid'\|'list'>('grid')`. |
| 5 | List view shows small thumbnail + text, same status vocabulary as grid, inline not overlay (D-03/D-04) | ✓ VERIFIED | `[batchId].vue:340-378` — `w-16 aspect-[2/3]` thumbnail, shared `statusLabel()`/icon usage. |
| 6 | List view mode persists across a genuine full-page browser reload (D-02) | ✓ VERIFIED | Code: `onMounted()` reads `localStorage.getItem(...)`, `watch(viewMode, ...)` writes it (`[batchId].vue:56-74`). **Previously flagged `insufficient_spec`/backstop** — now closed by live human confirmation: 15-UAT.md test 1 "List view persists across a real hard browser reload (D-02)" — result: pass. |
| 7 | Results are grouped into four fixed, ordered, separately-headed sections — Saved, Ambiguous, Not found, Parse error — NOT_FOUND never merged with AMBIGUOUS (new requirement, G-15-2) | ✓ VERIFIED | `[batchId].vue:124-137` — `savedLines`/`ambiguousLines`/`notFoundLines`/`parseErrorLines` computeds, fixed-order `orderedCards` concatenation, `isGroupStart()` inserts one heading per status transition. Test `renders four section headings in Saved -> Ambiguous -> Not found -> Parse error order` passes. Human UAT test 2 confirmed live — pass. |
| 8 | AMBIGUOUS/NOT_FOUND line resolves in-place: expand, fresh TMDB search prefilled with title, pick candidate, save (D-08) | ✓ VERIFIED | `toggleResolve()` (`[batchId].vue:167-182`) calls `searchTmdb(line.title)` fresh on every expand. Backend `resolveLine()` saves via `MovieService.initiate()`. Backend test `shouldResolveAmbiguousLine_savingMovieAndUpdatingLineStatus` passes (ran live, 7/7 `BulkImportServiceTest`, part of 23/23 `BulkImportControllerTest`). |
| 9 | Resolve widget's candidate picker renders at full container width with meaningfully larger posters, in both grid and list view (G-15-3 fix) | ✓ VERIFIED | `[batchId].vue:282-286,297,384-387,399` — `resolve-panel` is a sibling of `result-card`/`view-list-row` (`col-span-full` in grid; natural full-row width in list), candidate grids widened (`grid-cols-3 sm:grid-cols-4 md:grid-cols-6` / `grid-cols-4 sm:grid-cols-6 md:grid-cols-8`), posters `w-full`. Tests `renders the expanded resolve panel as a sibling of result-card...` and `...view-list-row...` pass. Indirectly human-confirmed: the follow-up UAT re-test (which produced G-15-4) explicitly described posters as "appropriately sized" — only a missing label was reported, not a sizing/layout regression. |
| 10 | Resolve widget candidates show a visible title+year text label, not poster-only (new requirement, G-15-4) | ✓ VERIFIED | `candidateLabel()` (`[batchId].vue:90-92`) and `resolve-candidate-label` `<p>` in both grid (`~line 319`) and list (`~line 421`) blocks. Tests for grid/list label rendering pass. |
| 11 | A candidate with unknown (null) year degrades gracefully to title-only — never a dangling `()` or the literal string `null` (G-15-4) | ✓ VERIFIED | `candidateLabel()` ternary: `candidate.year ? \`${title} (${year})\` : title`. Null-year test asserts no `(` character present. |
| 12 | Long candidate labels wrap onto multiple lines instead of being clipped by ellipsis — full title AND year always visible (new requirement, G-15-5) | ✓ VERIFIED | `resolve-candidate-label` class list no longer contains `truncate` (`[batchId].vue:321,423` — `text-[10px] text-muted-foreground text-center leading-tight mt-1`). Tests assert `truncate` absent and long-title+year text renders in full, both views. Human UAT test 3 "Resolve widget candidate labels wrap instead of truncating" — result: pass. |
| 13 | After a successful resolve, the page refetches the full batch — never an optimistic local patch (D-09) | ✓ VERIFIED | `pickCandidate()` (`[batchId].vue:186-199`) calls `resolveLine()` then `await loadDetail()`; never assigns to `line.status`/`line.movieId` directly. Test `clicking a candidate calls resolveLine..., then refetches the batch` passes. |
| 14 | Resolving a line updates that specific `BulkImportLine` row's status/tmdbId/posterPath (D-10) | ✓ VERIFIED | `BulkImportService.resolveLine()` sets `status=SAVED`, `tmdbId`, `posterPath`, saves the row. Backend test confirms `findById(lineId)` reflects the update (ran live, part of 7/7 `BulkImportServiceTest`). |
| 15 | PARSE_ERROR lines never show a resolve widget (D-11 boundary) | ✓ VERIFIED | `isResolvable()` (`[batchId].vue:117-119`) returns true only for AMBIGUOUS/NOT_FOUND; PARSE_ERROR is structurally excluded from the resolvable render path entirely (its own always-row section has no resolve markup at all). |
| 16 | A lineId not belonging to the batchId in the URL is rejected — ownership on both batch and line (D-10/T-15-01) | ✓ VERIFIED | `BulkImportLineRepository.findByIdAndBatchId(lineId, batchId)` used inside `resolveLine()` (`BulkImportService.java:163`), not a plain `findById`. Backend tests `shouldReturn404_whenResolvingLineFromDifferentBatch` / `shouldReturn403_whenDifferentUserResolvesLine` pass (ran live, part of 23/23). |
| 17 | Comma-delimited CSV (RFC4180 quoting) parsed and matched exactly like semicolon upload (D-12/D-13) | ✓ VERIFIED | `ImportLineParser.parseCsv()` uses `CSVFormat.DEFAULT`; `commons-csv:1.14.1` in `build.gradle.kts:64`. Backend test `shouldParseCommaDelimitedCsv_andSaveUniqueMatch` passes (ran live, part of 12/12 `ImportLineParserTest`). |
| 18 | A quoted comma-containing title parses as one field, no special-casing (D-15) | ✓ VERIFIED | `ImportLineParserTest.shouldParseQuotedCommaContainingTitle_asOneField` passes. |
| 19 | Optional CSV header row (non-numeric Year) auto-detected and skipped (D-14) | ✓ VERIFIED | `BulkImportController.java:124-129` skip guarded on non-numeric year, scoped to `isCsvFormat`. Test `shouldSkipHeaderRow_whenFirstCsvLineHasNonNumericYear` passes. |
| 20 | Existing semicolon format keeps working completely unchanged (D-16) | ✓ VERIFIED | `ImportLineParser.parse()` unmodified since Phase 15 began (confirmed: no diff in this method across 15-01 through 15-06); all 6 pre-existing `parse()` unit tests still pass. |
| 21 | `saubere_filmliste.txt` (1139 lines, semicolon format) imports with identical per-line outcomes — real-world backward-compat check (D-17) | ✓ VERIFIED | **Previously flagged `insufficient_spec`/backstop** — now closed by live human confirmation: 15-UAT.md test 4 "Real-world regression import of saubere_filmliste.txt (D-17)" — result: pass, run against the live app stack (TMDB key, DB, SSE progress). |

**Score:** 21/21 truths verified (0 present-and-plausible / behavior-unverified — every previously-outstanding item now has either a passing automated test or a documented, passing live human UAT confirmation)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `frontend/pages/imports/[batchId].vue` | view toggle, movie links, 4-section grouping, always-row PARSE_ERROR, full-width resolve widget with title/year wrap-labels | ✓ VERIFIED | All features present, wired, no stubs. Re-read in full during this verification (460 lines). |
| `frontend/test/unit/pages/imports-batchId.spec.ts` | full coverage of all above, including source-level NuxtLink regression guard | ✓ VERIFIED | 27 tests, all passing (ran live). |
| `backend/.../dto/BulkImportLineResult.java` | carries `id`/`movieId`/`rawLine` | ✓ VERIFIED | Unchanged since 15-01, still correct. |
| `POST .../lines/{lineId}/resolve` | ownership-scoped resolve endpoint | ✓ VERIFIED | Present, unaffected by the unrelated post-verification backend commit (see Regression Check below). |
| `backend/.../ImportLineParser.java` (`parseCsv()`) | RFC4180-style CSV parser | ✓ VERIFIED | Present, unchanged since 15-03. |
| `.planning/debug/bulk-import-saved-card-link-broken.md` | root-cause diagnosis referenced by 15-04-PLAN.md | ✓ VERIFIED | File exists, confirmed on disk. |
| `.planning/debug/resolve-widget-narrow-grid.md` | root-cause diagnosis referenced by 15-04-PLAN.md | ✓ VERIFIED | File exists, confirmed on disk. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `movieLinkTarget(line)` | real navigation | `:is="movieLinkTarget(line) ? NuxtLink : 'div'"` where `NuxtLink = resolveComponent('NuxtLink')` | ✓ WIRED | Fixes the exact Nuxt 3 bug documented in the debug session; source-level test guards against regression to the bare-string pattern. |
| `savedLines`/`ambiguousLines`/`notFoundLines` | rendered sections | `orderedCards` computed + `isGroupStart()` | ✓ WIRED | Single `v-for` fragment, heading inserted once per status transition, confirmed by 4-section-ordering test. |
| `parseErrorLines` | always-row section | rendered outside `viewMode` toggle entirely | ✓ WIRED | `v-if="parseErrorLines.length"` section is a sibling of the grid/list containers, not nested inside either. |
| `getResolveState(line).expanded` | full-width resolve panel | sibling `resolve-panel` div (`col-span-full` in grid, natural full-row in list) | ✓ WIRED | Confirmed by DOM-sibling assertions in both view modes. |
| `candidate.title`/`candidate.year` | visible label | `candidateLabel()` → `resolve-candidate-label` `<p>` | ✓ WIRED | Rendered in both grid and list resolve-candidate blocks, `truncate` removed so full text is always visible. |
| `pickCandidate()` | server truth refetch | `resolveLine()` then `await loadDetail()` | ✓ WIRED | Unchanged since 15-02; never an optimistic patch. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `[batchId].vue` `NuxtLink` href | `movieId` | `MovieRepository.findByUserIdAndTmdbId()` (server, ownership-scoped) | Yes | ✓ FLOWING |
| resolve widget candidates | `title`/`year` for label | live `useMovies().searchTmdb()` → TMDB `/movies/search` | Yes | ✓ FLOWING |
| PARSE_ERROR row | `rawLine` | `BulkImportLine.rawLine` persisted at upload time | Yes | ✓ FLOWING |
| CSV upload | parsed fields | `CSVParser.parse()` on real uploaded bytes | Yes | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Frontend batch-detail page tests (view toggle, movie links, 4-section grouping, always-row PARSE_ERROR, full-width resolve panel, title/year labels, wrap-not-truncate) | `pnpm vitest run test/unit/pages/imports-batchId.spec.ts` | `Tests 27 passed (27)` (ran live in this verification session) | ✓ PASS |
| Backend controller tests (movie links, resolve endpoint, CSV upload, ownership checks) | `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportControllerTest"` | `tests="23" failures="0" errors="0"` (ran live, TEST-*.xml re-checked) | ✓ PASS |
| Backend service tests (resolveLine, dual-format dispatch — includes the unrelated post-verification Wikipedia-skip change) | `./gradlew test --tests "de.moviearchive.bulkimport.BulkImportServiceTest"` | `tests="7" failures="0" errors="0"` (ran live) | ✓ PASS |
| Backend CSV parser unit tests | `./gradlew test --tests "de.moviearchive.bulkimport.ImportLineParserTest"` | `tests="12" failures="0" errors="0"` (ran live) | ✓ PASS |
| ESLint on both modified frontend files | `npx eslint "pages/imports/[batchId].vue" "test/unit/pages/imports-batchId.spec.ts"` | No output, exit clean | ✓ PASS |
| No debt markers (TODO/FIXME/XXX/TBD/HACK/PLACEHOLDER) in phase-modified files | `grep -rn -i` across page + spec file | No matches (one harmless `/placeholder-poster.svg` filename literal, not a debt marker) | ✓ PASS |

### Regression Check — Unrelated Backend Commit Since Prior Verification

Between the prior verification (2026-08-28T14:25:58Z) and this re-verification, one unrelated backend commit landed: `635a744` ("BulkImportService — Pass 2 skips Wikipedia; remove dead SPARQL prefetch", a separate `/gsd-quick` task, not part of Phase 15's plan sequence). It touches `BulkImportService.java` but not `resolveLine()`, `findByIdAndBatchId()`, or the `isCsvFormat`-gated `processLine()` dispatch — confirmed by direct grep of the current file (all three still present and unchanged in signature) and by re-running `BulkImportServiceTest`/`BulkImportControllerTest` live (7/7 and 23/23 pass respectively). No regression to any Phase 15 must-have.

### Requirements Coverage

Phase 15 has no formal `REQUIREMENTS.md` IDs — `15-CONTEXT.md` defines D-01 through D-17 as the phase's requirement set, and `15-UAT.md` adds gap IDs G-15-2 through G-15-5 discovered during live UAT and closed by plans 15-04/15-05/15-06.

| Requirement source | Description | Status | Evidence |
|---------------------|-------------|--------|----------|
| D-01–D-11 (`15-CONTEXT.md`) | View toggle, movie links, inline resolve | ✓ SATISFIED | Truths 1–16 above |
| D-12–D-17 (`15-CONTEXT.md`) | Real CSV parsing, legacy format preserved | ✓ SATISFIED | Truths 17–21 above |
| G-15-2 (`15-UAT.md`) | NuxtLink navigation broken, PARSE_ERROR truncated/miscategorized, no status grouping | ✓ RESOLVED | Closed by 15-04, confirmed by human UAT test 2 |
| G-15-3 (`15-UAT.md`) | Resolve widget candidate posters too small (nested in narrow grid cell) | ✓ RESOLVED | Closed by 15-04 |
| G-15-4 (`15-UAT.md`) | Resolve candidates show poster only, no title/year | ✓ RESOLVED | Closed by 15-05 |
| G-15-5 (`15-UAT.md`) | Candidate label truncates long titles, cutting off the year | ✓ RESOLVED | Closed by 15-06, confirmed by human UAT test 3 |

**Stale REQUIREMENTS.md sections (documentation debt, carried forward — not a functional gap, unchanged since prior verification):** `.planning/REQUIREMENTS.md` still lists **IMPORT-V2-01** under `## Future Requirements` and **CSV-Import (SET-06)** under `## Out of Scope`, though both are now fully implemented by this phase (last touched in Phase 10, commit `504de05`). Recommend a follow-up doc update before milestone close — non-blocking.

### Anti-Patterns Found

None. Grepped `frontend/pages/imports/[batchId].vue` and `frontend/test/unit/pages/imports-batchId.spec.ts` for `TODO|FIXME|XXX|TBD|HACK|PLACEHOLDER|placeholder|coming soon|not yet implemented` — only match is the literal filename `/placeholder-poster.svg` (a real fallback image asset, not a stub marker).

### Carried-Forward Advisory (Non-Blocking)

**CR-01** (pre-existing, first identified in the prior verification's code-review cross-check): `BulkImportService.findExistingRow()`/`upsertLine()` scope row-reuse lookup by `(userId, normalizedTitle, year)`, not `batchId` — re-uploading an overlapping title/year currently in a different batch can silently reassign that row, corrupting the older batch's line-count accounting. This predates Phase 15 entirely (traced to commit `eff92a5`, Phase 10) and none of Phase 15's plans (01–06) touch this method. Not a Phase 15 must-have, does not block this phase's `passed` status — flagged again here only so it is not lost, per the prior verification's own recommendation to consider a follow-up bugfix phase.

## Human Verification Required

None. All items previously requiring human verification are now closed with documented, passing evidence in `15-UAT.md` (status: complete, 4/4 tests passed, 0 open issues, 0 pending, 0 blocked):
1. SAVED-card navigation + PARSE_ERROR distinctiveness + 4-section grouping — pass (test 2)
2. Resolve-widget candidate label wrap (full title + year visible) — pass (test 3)
3. List-view localStorage persistence across a real hard reload (D-02) — pass (test 1)
4. Real-world regression import of `saubere_filmliste.txt` (D-17) — pass (test 4)

### Gaps Summary

No gaps. All 21 observable truths for this phase are VERIFIED — 16 via automated tests re-run live during this verification session (frontend: 27/27; backend: 23/23 controller + 12/12 parser + 7/7 service), and the remaining previously-outstanding items closed by documented, passing live human UAT (`15-UAT.md`). The three gap-closure plans (15-04, 15-05, 15-06) are each confirmed present in the current codebase, correctly wired, test-covered, and free of regressions from the one unrelated backend commit that landed since the prior verification. `15-SECURITY.md` confirms `threats_open: 0` across all 13 registered threats spanning plans 15-01 through 15-06.

---

_Verified: 2026-08-28T22:40:00Z_
_Verifier: Claude (gsd-verifier)_
