---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
plan: 04
subsystem: ui
tags: [nuxt, vue, resolveComponent, tailwindcss, bulk-import, gap-closure]

# Dependency graph
requires:
  - phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv (plans 01-03)
    provides: "The batch-detail page ([batchId].vue), its grid/list view toggle, and the inline resolve widget this plan repairs and restructures"
provides:
  - "Working SAVED-line navigation via resolveComponent('NuxtLink') instead of a bare-string :is binding"
  - "Four fixed, ordered status sections (Saved -> Ambiguous -> Not found -> Parse error) with per-status headings"
  - "An always-row PARSE_ERROR treatment, independent of grid/list view mode, that never truncates the raw line text"
  - "A full-container-width resolve-candidate panel rendered as a DOM sibling of its triggering card/row"
affects: [bulk-import, imports-batchId-page]

# Actuals (#2632)
actuals:
  tokens: 6531
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Nuxt built-in component :is bindings must resolve via `const X = resolveComponent('X')` (literal-string argument, statically detectable by Nuxt's compiler) rather than a bare string ternary, which the compiler cannot see and which silently falls back to an inert unresolved custom element at runtime."
    - "Status-grouped list rendering: build ordered per-status arrays, concatenate into one fixed-order array, and flag the first item of each run (isGroupStart) to insert a single heading per status transition inside one v-for/template fragment."
    - "Breakout panels (col-span-full in a CSS grid) render as a DOM *sibling* of the triggering per-item element inside the same v-for fragment, not a nested descendant, to escape a single grid-cell's width constraint."

key-files:
  created: []
  modified:
    - "frontend/pages/imports/[batchId].vue - NuxtLink resolveComponent fix, four-section status grouping, always-row PARSE_ERROR section, full-width resolve panel breakout"
    - "frontend/test/unit/pages/imports-batchId.spec.ts - source-level NuxtLink regression guard, four-section ordering tests, PARSE_ERROR always-row tests, resolve-panel DOM-sibling tests"

key-decisions:
  - "Used the Nuxt-documented resolveComponent('NuxtLink') workaround (literal string argument) rather than adding a literal <NuxtLink> tag elsewhere in the file, per the debug session's confirmed root cause and fix direction."
  - "PARSE_ERROR lines are excluded entirely from orderedCards/the viewMode toggle and rendered in their own always-row <section>, so the same markup renders identically regardless of grid/list selection (per G-15-2's explicit user requirement)."
  - "The resolve-toggle button stays nested inside its triggering card/row; only the expanded panel (search state, error banner, candidate grid) moves to a sibling position, keeping the toggle's click target unchanged."

patterns-established:
  - "Source-level regression guard: for bugs invisible to a stubbed component mount (VTU global.stubs resolves by name regardless of the SFC's own resolution mechanism), assert on the compiled source text itself via readFileSync, not just rendered output."

requirements-completed: [D-05, D-07, D-08, D-11]

coverage:
  - id: D1
    description: "A SAVED bulk-import line's card/row is a real, working navigable link to /movies/{movieId}, guarded by a source-level regression test against the specific Nuxt component-resolution anti-pattern that broke it"
    requirement: "D-05"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#resolves NuxtLink via resolveComponent() instead of a bare string (source-level regression guard)"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#wraps a SAVED line in a whole-card link to /movies/{movieId}"
        status: pass
    human_judgment: true
    rationale: "The debug session (.planning/debug/bulk-import-saved-card-link-broken.md) confirmed this specific bug class is invisible to a Vue Test Utils stubbed mount and only reproduces in a real compiled Nuxt runtime — the plan's own <verification> section requires a manual browser spot-check to fully confirm the fix, which the automated suite alone cannot prove."
  - id: D2
    description: "A PARSE_ERROR line never renders inside a poster/thumbnail card — it always renders as a row (small icon + full untruncated raw line text) regardless of grid/list view"
    requirement: "D-11"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders a PARSE_ERROR line as an always-row, never inside a result-card/view-list-row, in grid view"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders the identical PARSE_ERROR row content after toggling to list view"
        status: pass
    human_judgment: false
  - id: D3
    description: "Bulk-import results are grouped into four fixed, ordered sections — Saved, Ambiguous, Not found, Parse error — each its own distinct, separately-headed group"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders four section headings in Saved -> Ambiguous -> Not found -> Parse error order"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders no heading for a status missing from the batch"
        status: pass
    human_judgment: false
  - id: D4
    description: "Expanding the inline resolve widget renders its candidate picker as a full page/container-width panel independent of the triggering card/row's width, with larger candidate poster thumbnails, in both grid and list view"
    requirement: "D-08"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders the expanded resolve panel as a sibling of result-card, not a descendant, in grid view"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders the expanded resolve panel as a sibling of view-list-row, not a descendant, in list view"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#sizes candidate posters to fill their now-wider grid cell, not the old fixed 40px list thumbnail"
        status: pass
    human_judgment: true
    rationale: "The plan's own <verification> section calls for a manual browser spot-check confirming candidate posters are 'actually recognizable' at real size — a subjective visual-quality judgment the automated DOM/class assertions cannot make."
  - id: D5
    description: "Grid/list toggle, localStorage persistence, and the resolve search/pick/save flow are unaffected by the layout restructuring"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#switches to list view when the ViewToggle list button is clicked"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders list view immediately when localStorage has bulk-import-view-mode=list"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#expanding the resolve widget runs a fresh TMDB search prefilled with the line title and renders candidates"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#clicking a candidate calls resolveLine with the picked tmdbId/posterPath, then refetches the batch"
        status: pass
    human_judgment: false

duration: 40min
completed: 2026-08-28
status: complete
---

# Phase 15 Plan 04: Bulk-Import Gap Closure — NuxtLink Navigation, Status Grouping, Resolve-Panel Breakout Summary

**Fixed SAVED-card navigation via `resolveComponent('NuxtLink')`, grouped bulk-import results into four ordered status sections with an always-row PARSE_ERROR treatment, and broke the inline resolve widget's candidate picker out to full container width.**

## Performance

- **Duration:** 40 min
- **Completed:** 2026-08-28T18:17:16Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- SAVED bulk-import lines are now real, working links to `/movies/{movieId}` — fixed the documented Nuxt 3 limitation where a bare-string `:is="'NuxtLink'"` ternary is invisible to Nuxt's build-time component-registration scan, by capturing `const NuxtLink = resolveComponent('NuxtLink')` and binding `:is` to that reference instead.
- Bulk-import results now render in four fixed, ordered, separately-headed sections (Saved → Ambiguous → Not found → Parse error), driven by `savedLines`/`ambiguousLines`/`notFoundLines`/`parseErrorLines` computeds, an `orderedCards` concatenation, and an `isGroupStart()` helper that inserts exactly one `section-heading-{status}` per status transition.
- PARSE_ERROR lines are removed entirely from the grid/list `viewMode` toggle and rendered in their own always-row `<section data-testid="parse-error-section">`, so the full raw line text is never truncated by a poster-shaped card, identically in both view modes.
- The inline resolve widget's expanded candidate picker (search spinner, error banner, candidate grid) now renders as a DOM sibling of the triggering card/row (`data-testid="resolve-panel"`) instead of a nested descendant — `col-span-full` in grid view, natural full-row width in list view — with candidate posters widened from a cramped 3-column/40px-thumbnail layout to `w-full` posters in a `grid-cols-3 sm:grid-cols-4 md:grid-cols-6` (grid) / `grid-cols-4 sm:grid-cols-6 md:grid-cols-8` (list) grid.
- Added a source-level regression guard (`readFileSync` on the page file's own text) asserting `resolveComponent('NuxtLink')` is present — the one part of this bug class a stubbed Vue Test Utils mount can never prove, since `global.stubs` resolves `NuxtLink` by name regardless of how the real SFC would have resolved it.

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix NuxtLink navigation, force PARSE_ERROR to always render as a row, group results into four ordered status sections (G-15-2)** - `52af012` (fix)
2. **Task 2: Break the inline resolve widget's candidate picker out to full container width (G-15-3)** - `39beb9b` (fix)

_No separate plan-metadata commit — this is a worktree-isolated gap-closure plan; STATE.md/ROADMAP.md updates and the final metadata commit are owned by the orchestrator after merge._

## Files Created/Modified
- `frontend/pages/imports/[batchId].vue` - `resolveComponent('NuxtLink')` fix, `orderedCards`/`isGroupStart()`-driven four-section grouping, always-row `parse-error-section`, sibling `resolve-panel` breakout in both grid and list view
- `frontend/test/unit/pages/imports-batchId.spec.ts` - source-level NuxtLink regression guard, `MOCK_DETAIL_ALL_STATUSES` fixture, four-section-ordering tests, PARSE_ERROR always-row tests, resolve-panel DOM-sibling and candidate-sizing tests (24 tests total, all passing)

## Decisions Made
- Used the Nuxt-documented `resolveComponent('NuxtLink')` workaround (literal string argument, statically detectable by Nuxt's compiler) rather than adding a literal `<NuxtLink>` tag elsewhere in the file — matches the debug session's confirmed root cause and recommended fix exactly.
- PARSE_ERROR lines are structurally excluded from `orderedCards` and the `viewMode` toggle entirely (rather than conditionally styled within it), guaranteeing the always-row requirement holds regardless of future changes to the grid/list branches.
- Kept the resolve-toggle button nested inside its triggering card/row and moved only the expanded panel to a sibling position, preserving the existing click target and toggle behavior unchanged.

## Deviations from Plan

None - plan executed exactly as written. The `<read_first>` and `<action>` steps in both tasks matched the actual file structure precisely (this plan was itself the output of two prior diagnose-only debug sessions with confirmed root causes), so no Rule 1-4 auto-fixes were needed.

One environment-only adjustment (not a plan deviation): this worktree had no `node_modules`/`.nuxt` present (a fresh git worktree checkout does not inherit these gitignored build artifacts from the main working tree). Ran `pnpm install --offline` (resolved entirely from the existing local pnpm content-addressable store, no network access) and `nuxt prepare` to generate a working local toolchain before running `pnpm vitest run test/unit/pages/imports-batchId.spec.ts`. Neither directory is tracked by git and neither appears in `git status` for any commit in this plan.

## Issues Encountered
None - both tasks completed as specified; all 24 tests in `imports-batchId.spec.ts` pass (16 pre-existing + 8 new), and `npx eslint` reports no issues on either modified file.

## Next Phase Readiness
- Both G-15-2 and G-15-3 gaps are closed at the automated-verification level (source-level regression guard + full grouping/breakout test coverage).
- Per the plan's own `<verification>` section and this SUMMARY's `coverage` block, two deliverables (D1: SAVED-link navigation, D4: resolve-panel poster recognizability) still require a human-in-a-real-browser spot-check — the debug sessions that diagnosed both gaps independently confirmed neither bug class is provable by an automated Vitest/happy-dom mount alone. Recommend running the manual checklist in the plan's `<verification>` section (open `/imports/{batchId}` for a real mixed-status batch, click a SAVED card, confirm the four sections and PARSE_ERROR row treatment, expand a resolve widget and confirm candidate posters are recognizable) before closing out G-15-2/G-15-3 in `15-UAT.md`.
- No blockers for subsequent phase work.

---
*Phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv*
*Completed: 2026-08-28*

## Self-Check: PASSED

- FOUND: `frontend/pages/imports/[batchId].vue`
- FOUND: `frontend/test/unit/pages/imports-batchId.spec.ts`
- FOUND: `.planning/phases/15-bulk-import-page-completion-view-toggle-movie-links-real-csv/15-04-SUMMARY.md`
- FOUND commit: `52af012` (Task 1)
- FOUND commit: `39beb9b` (Task 2)
- FOUND commit: `1cbfe71` (SUMMARY docs)
