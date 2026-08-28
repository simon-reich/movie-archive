---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
plan: 05
subsystem: ui
tags: [vue, nuxt, tmdb, bulk-import, gap-closure]

# Dependency graph
requires:
  - phase: 15-04
    provides: full-width resolve-candidate picker with appropriately sized poster thumbnails
provides:
  - candidateLabel() helper that formats a TMDB search candidate as "Title (Year)" or title-only when year is unknown
  - resolve-candidate-label text node under every candidate poster in both grid-view and list-view resolve widgets
affects: [bulk-import-page-completion, movie-links, uat-verification]

# Actuals (#2632)
actuals:
  tokens: 1400
  tasks: 1
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Narrow-cell text labels reuse the existing text-[10px] ... text-center leading-tight convention already established for poster-fallback text"

key-files:
  created: []
  modified:
    - frontend/pages/imports/[batchId].vue
    - frontend/test/unit/pages/imports-batchId.spec.ts

key-decisions:
  - "candidateLabel() is a plain template-local helper (not exported) — tested via rendered-output assertions, matching the file's existing pattern for posterUrl()/statusLabel()"
  - "Same markup/classes/testid applied to both grid-view and list-view resolve-candidate blocks since 15-04's full-width fix already made both structurally equivalent shapes"

patterns-established: []

requirements-completed: [D-08]

coverage:
  - id: D1
    description: "Every resolve-widget candidate shows a visible 'Title (Year)' text label under its poster, in both grid and list view; unknown year degrades to title-only with no dangling '()' or 'null'"
    requirement: D-08
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders a title + year label under each candidate poster in grid view, degrading gracefully for a null year"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders the identical title + year label under each candidate poster in list view"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#does not alter the existing poster img src/alt/class or the candidate button testid/click handler"
        status: pass
    human_judgment: true
    rationale: "Closes a UAT gap (G-15-4) originally found via live human re-test of the resolve widget against real TMDB data — a final human re-verification in the live app is the appropriate closing signal for this specific gap, even though the automated coverage above is fully passing."

# Metrics
duration: 4min
completed: 2026-08-28
status: complete
---

# Phase 15 Plan 05: Resolve-Candidate Title/Year Label Summary

**Each TMDB resolve-candidate now shows a visible "Title (Year)" text label under its poster (title-only when year is unknown), in both grid and list view — closing UAT gap G-15-4.**

## Performance

- **Duration:** 4 min
- **Started:** 2026-08-28T20:29:51+02:00
- **Completed:** 2026-08-28T20:33:42+02:00
- **Tasks:** 1
- **Files modified:** 2

## Accomplishments
- Added `candidateLabel(candidate: TmdbSearchResult): string` helper in `[batchId].vue`'s `<script setup>` — returns `"Title (Year)"` when `year` is truthy, `"Title"` alone otherwise (never a dangling `()` or the literal string `"null"`).
- Wired a new `resolve-candidate-label` `<p>` text node into both the grid-view and list-view resolve-candidate button blocks, directly below each poster `<img>` / resolving-spinner overlay, using the file's existing narrow-cell text sizing convention (`text-[10px] ... text-center leading-tight truncate mt-1`).
- Left the poster `<img>`'s `:src`/`:alt`/`class`, the `resolve-candidate` button's `data-testid`, `:disabled` binding, and `@click="pickCandidate(...)"` handler completely untouched — pure additive template change.
- Extended `imports-batchId.spec.ts` with three new tests (grid-view label + null-year degradation, list-view label, and a guard test confirming the pre-existing poster/button attributes are unchanged), following the RED → GREEN TDD cycle. All 27 tests in the file pass.

## Task Commits

Each task was committed atomically (TDD RED → GREEN):

1. **Task 1 (RED): failing tests for candidate title+year label** - `cdafd83` (test)
2. **Task 1 (GREEN): candidateLabel() helper + template wiring** - `a46a333` (feat)

**Plan metadata:** (this commit, docs: complete plan)

_No refactor commit was needed — the GREEN implementation was already minimal and consistent with the file's existing helper-function/class conventions._

## Files Created/Modified
- `frontend/pages/imports/[batchId].vue` - Added `candidateLabel()` helper and a `resolve-candidate-label` text node in both grid-view and list-view resolve-candidate blocks.
- `frontend/test/unit/pages/imports-batchId.spec.ts` - Added 3 tests covering grid-view rendering + null-year graceful degradation, list-view rendering, and a regression guard on the pre-existing poster/button attributes.

## Decisions Made
- `candidateLabel()` was kept as a template-local (non-exported) helper, consistent with `posterUrl()`/`statusLabel()` in the same file — tested indirectly via rendered `resolve-candidate-label` text assertions rather than a direct unit import, per the plan's explicit fallback instruction.
- Identical markup, classes, and `data-testid="resolve-candidate-label"` were applied to both grid-view and list-view blocks without introducing any new shared component, since 15-04's full-width fix already made both candidate grids structurally equivalent (only the responsive `grid-cols-*` breakpoints differ).

## Deviations from Plan

None - plan executed exactly as written. (Frontend dependencies (`pnpm install`) had to be installed in this worktree before `pnpm vitest` could run, since the worktree checkout does not carry `node_modules` — this is a one-time environment-setup step, not a code deviation.)

## Issues Encountered
None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- G-15-4 is closed at the code level; all automated coverage (unit tests) passes.
- Per the `coverage` block's `human_judgment: true` rationale, a final live human re-test of the resolve widget (expand on a real AMBIGUOUS/NOT_FOUND line, run a live TMDB search, confirm each candidate now shows a title/year label under its poster) is the appropriate closing signal for this UAT gap, matching how G-15-2/G-15-3 were closed in 15-04.
- No blockers for the remaining phase 15 plans.

---
*Phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv*
*Completed: 2026-08-28*
