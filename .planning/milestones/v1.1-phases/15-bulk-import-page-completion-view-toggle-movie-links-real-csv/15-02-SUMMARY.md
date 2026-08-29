---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
plan: 02
subsystem: ui
tags: [vue, nuxt, spring-boot, bulk-import, tmdb-search]

requires:
  - phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
    provides: "Plan 15-01's grid/list toggle, movie links, and PARSE_ERROR display on /imports/{batchId}"
provides:
  - "POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve — ownership-scoped on both batchId and lineId"
  - "BulkImportLineRepository.findByIdAndBatchId() — the IDOR mitigation for scoping a line to its batch"
  - "BulkImportService.resolveLine() — saves via the idempotent MovieService.initiate() pipeline, updates the specific line row in place"
  - "useBulkImport().resolveLine(batchId, lineId, tmdbId, posterPath)"
  - "Inline search-and-pick resolve widget on AMBIGUOUS/NOT_FOUND cards/rows in [batchId].vue"
affects: [15-03-real-csv-parsing]

actuals:
  tokens: 9067
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Per-line reactive widget state keyed by line.id (Record<string, ResolveWidgetState> via reactive()), surviving a full-batch D-09 refetch since the resolved row keeps its id"
    - "Controller resolve endpoint mirrors MovieController.saveMovie()'s enrich-after-commit sequencing exactly (CR-01): the @Transactional service method never calls enrichmentService.enrich() itself"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/bulkimport/dto/ResolveLineRequest.java
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
    - frontend/composables/useBulkImport.ts
    - "frontend/pages/imports/[batchId].vue"
    - frontend/test/unit/pages/imports-batchId.spec.ts

key-decisions:
  - "Treated Task 1 (backend resolve endpoint) as a tracer slice: committed and automated-<verify>-confirmed (20/20 backend tests green) before starting Task 2 (frontend widget), per the plan's own tracer/expansion task typing. This plan runs autonomous:true in a non-interactive worktree, so the passed automated <verify> stood in for the interactive human-verify checkpoint the tracer gate calls for outside auto-mode — documented here rather than silently skipped."
  - "No new MethodArgumentNotValidException handler added to BulkImportController for the @Positive tmdbId validation — Spring's default validation-error handling already returns 400 for a failed @Valid without a custom handler (confirmed via the new shouldReturn400_whenResolveTmdbIdNotPositive test), matching the plan's minimal-surface intent."

patterns-established:
  - "isResolvable(line) / getResolveState(line) / toggleResolve(line) / pickCandidate(line, candidate) — the shared per-line widget helpers both grid and list template branches call, mirroring 15-01's movieLinkTarget()/statusLabel() single-source-of-truth pattern"

requirements-completed: [D-08, D-09, D-10, D-11]

coverage:
  - id: D1
    description: "POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve saves the picked TMDB candidate via the existing idempotent MovieService.initiate() pipeline and updates the specific BulkImportLine row to SAVED with the picked tmdbId/posterPath"
    requirement: "D-08"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldResolveAmbiguousLine_savingMovieAndUpdatingLineStatus"
        status: pass
    human_judgment: false
  - id: D2
    description: "A lineId from a different batch (even one owned by the same user) is rejected with 404 — the line-level IDOR mitigation via findByIdAndBatchId()"
    requirement: "D-10"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn404_whenResolvingLineFromDifferentBatch"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn404_whenResolvingUnknownLineId"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn403_whenDifferentUserResolvesLine"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldReturn400_whenResolveTmdbIdNotPositive"
        status: pass
    human_judgment: false
  - id: D3
    description: "AMBIGUOUS/NOT_FOUND lines render a resolve-toggle that expands into a fresh TMDB search prefilled with the line's title and a poster grid of candidates; PARSE_ERROR lines never render it"
    requirement: "D-08"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders a resolve-toggle on an AMBIGUOUS line but not on a PARSE_ERROR line"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#does not render a resolve-toggle on a PARSE_ERROR line"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#expanding the resolve widget runs a fresh TMDB search prefilled with the line title and renders candidates"
        status: pass
    human_judgment: false
  - id: D4
    description: "Picking a candidate calls resolveLine with the picked tmdbId/posterPath, then refetches the full batch via getBatchDetail() — never a local optimistic patch of line.status/movieId/tmdbId"
    requirement: "D-09"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#clicking a candidate calls resolveLine with the picked tmdbId/posterPath, then refetches the batch"
        status: pass
    human_judgment: false
  - id: D5
    description: "Manual spot-check on a real AMBIGUOUS/NOT_FOUND line: expand widget, see live TMDB search results, pick a candidate, confirm the batch report immediately shows SAVED with a working movie link"
    verification: []
    human_judgment: true
    rationale: "Live browser interaction with the real TMDB API and real batch-detail refetch is a judgment call the plan's own <verification> section marks as 'not automatable' — requires opening the app in a real browser session, which this executor cannot do."

duration: ~20min
completed: 2026-08-28
status: complete
---

# Phase 15 Plan 02: Inline Ambiguous/Not-Found Resolution Summary

**New POST resolve endpoint (ownership-scoped on both batchId and lineId) plus an inline TMDB search-and-pick widget on AMBIGUOUS/NOT_FOUND bulk-import lines, updating that specific line's row to SAVED and refetching the batch**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-08-28T12:03:19Z (approx)
- **Tasks:** 2
- **Files modified:** 8 (1 created)

## Accomplishments
- `BulkImportLineRepository.findByIdAndBatchId()` — the actual T-15-01 IDOR mitigation: proves a lineId belongs to THIS batch, not merely that it exists anywhere (even for the same user, in a different batch)
- New `ResolveLineRequest` DTO (`tmdbId` `@Positive`, `posterPath` nullable) and `BulkImportService.resolveLine()` — saves via the existing idempotent `MovieService.initiate()` pipeline, then updates the specific `BulkImportLine` row in place to `SAVED` with the picked `tmdbId`/`posterPath`
- `POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve` on `BulkImportController` — ownership-checked via `loadOwnedBatch()` for `batchId`, then `resolveLine()`'s `findByIdAndBatchId` for `lineId`; mirrors `MovieController.saveMovie()`'s enrich-after-commit sequencing exactly (CR-01: `enrichmentService.enrich()` only fires after `resolveLine()`'s transaction has committed)
- `useBulkImport().resolveLine(batchId, lineId, tmdbId, posterPath)` — same `authHeaders()`/`$fetch` convention as `getBatchDetail()`
- `[batchId].vue` gets a `data-testid="resolve-toggle"` on every AMBIGUOUS/NOT_FOUND card (grid) and row (list) that expands into a FRESH `searchTmdb()` call prefilled with the line's title, renders a `data-testid="resolve-candidate"` poster grid, and on pick calls `resolveLine()` then the existing `loadDetail()` to refetch the full batch (D-09) — never a local optimistic patch
- PARSE_ERROR/SAVED lines never render the widget, confirming the D-11 boundary Plan 15-01 already established for the display-only half

## Task Commits

Each task was committed atomically:

1. **Task 1: Backend resolve endpoint — ownership-scoped, transactional save + line-status update** - `c4294df` (feat)
2. **Task 2: Frontend inline resolve widget (search, pick, refetch)** - `1c4056d` (feat)

**Plan metadata:** (this commit, follows)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/bulkimport/dto/ResolveLineRequest.java` - new DTO: `tmdbId` (`@Positive`), `posterPath` (nullable)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` - `findByIdAndBatchId(UUID id, UUID batchId)` derived query
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - `resolveLine(String email, UUID batchId, UUID lineId, ResolveLineRequest request)`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - `EnrichmentService` dependency; `POST .../lines/{lineId}/resolve` endpoint
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - 5 new tests (happy path, cross-batch IDOR, cross-user 403, unknown lineId 404, invalid tmdbId 400)
- `frontend/composables/useBulkImport.ts` - `resolveLine(batchId, lineId, tmdbId, posterPath)`
- `frontend/pages/imports/[batchId].vue` - `isResolvable()`, per-line `resolveState` (reactive), `toggleResolve()`, `pickCandidate()`; resolve widget markup in both grid and list template branches
- `frontend/test/unit/pages/imports-batchId.spec.ts` - 3 new tests (toggle presence boundary, fresh-search candidate rendering, pick → resolveLine → refetch); `useMovies` now mocked alongside `useBulkImport`

## Decisions Made
- Task 1 is `type="tracer"` per the plan — treated its passed automated `<verify>` (20/20 backend tests) as satisfying the tracer feedback gate before starting Task 2's expansion work, since this plan runs `autonomous: true` inside a non-interactive worktree where an interactive human-verify checkpoint isn't a fit; documented explicitly rather than silently skipped.
- No custom `MethodArgumentNotValidException` handler was added to `BulkImportController` for the new endpoint's `@Positive` validation — Spring's default validation-error handling already returns 400 without one, confirmed by the new `shouldReturn400_whenResolveTmdbIdNotPositive` test. Kept the endpoint's surface area minimal rather than adding an unrequested handler.
- Reused `robin-hood-ambiguous-search.json`'s two-candidate fixture (tmdbId 1001/1002) for all five new backend resolve tests instead of adding a new fixture — it already produces a genuine AMBIGUOUS line via the existing `shouldMarkAmbiguous_whenMultipleYearMatchesNoOriginalTitle` pattern, so no new WireMock stub file was needed.

## Deviations from Plan

None - plan executed exactly as written. (Task 1's tracer-gate handling above is a documented execution-flow interpretation, not a deviation from the plan's own action/acceptance-criteria text.)

## Issues Encountered
- One background async warning appeared in the backend test run (`ObjectOptimisticLockingFailureException` inside `EnrichmentService.enrich()`, logged via `SimpleAsyncUncaughtExceptionHandler`) — caused by a concurrent enrichment race between two rapidly-sequential tests' fire-and-forget `@Async` calls touching the same `Movie` row shortly before test-DB cleanup. This is the codebase's existing swallow-and-degrade `@Async` failure pattern (enrichment failures never propagate to the caller); it did not fail any test (0 failures/errors across all 20 tests) and is not new surface introduced by this plan — not fixed, matches established behavior.

## Known Stubs
None — the resolve widget renders real TMDB search results from `useMovies().searchTmdb()` and real batch state from `useBulkImport().getBatchDetail()` after a successful resolve; no hardcoded/mock data flows to the UI.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Plan 15-03 (real CSV parsing) touches `BulkImportController.java` again — this plan's new `resolve` endpoint and its `EnrichmentService` constructor dependency are additive and don't conflict with CSV-parsing changes to the upload path.
- Manual spot-check (live browser: expand an AMBIGUOUS line, run a real TMDB search, pick a candidate, confirm the batch report reflects SAVED + a working movie link immediately after) remains genuinely un-automatable per the plan's own `<verification>` section — flagged as `human_judgment: true` (D5) for UAT.
- All D-08–D-11 requirements for this plan are now implemented and test-covered; the 2026-08-25 "inline ambiguous resolve" todo this plan closes can be marked resolved once UAT confirms D5.

---
*Phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv*
*Completed: 2026-08-28*

## Self-Check: PASSED

- `backend/src/main/java/de/moviearchive/bulkimport/dto/ResolveLineRequest.java` — FOUND on disk
- Commit `c4294df` (Task 1) — FOUND in `git log --oneline --all`
- Commit `1c4056d` (Task 2) — FOUND in `git log --oneline --all`
- All 5 new backend acceptance-criteria tests pass (`BulkImportControllerTest`, 20/20 total)
- All 3 new frontend acceptance-criteria tests pass (`imports-batchId.spec.ts`, 16/16; full suite 194/194)
- `pnpm typecheck` clean; `pnpm lint` clean for all files this plan modified (1 pre-existing, out-of-scope lint error in an untouched file, same as logged in 15-01's deferred-items.md)
