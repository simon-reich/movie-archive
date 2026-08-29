---
status: resolved
trigger: "UAT gap G-15-3 (Phase 15, test 3): Expand the resolve widget on a real AMBIGUOUS or NOT_FOUND line, run a live TMDB search, pick a candidate, and confirm the batch report immediately shows SAVED with a working movie link. User reported: 'Da ist leider die Anzeige viel zu klein, der Poster. Man erkennt die Filme nicht. Also ist es gut, dass es inline passiert, Aber das Gritt und damit die Anzeige der Poster muss viel größer sein. Zurzeit wird hier auch nur in das Column gequetscht von dem Poster, was zu resolven ist. Das muss über die volle Breite gehen.'"
created: 2026-08-28T17:51:24Z
updated: 2026-08-28T17:51:24Z
---

## Current Focus
<!-- OVERWRITE on each update - reflects NOW -->

hypothesis: CONFIRMED — the resolve widget (including its candidate-picker grid) is rendered as a nested child inside the same grid-cell card (grid view) / list-row flex column (list view) that it toggles from, rather than as a full-width element spanning outside the parent grid/row. It therefore inherits the width of a single grid cell (1/2 to 1/5 of viewport, depending on breakpoint) or a narrow list-row column, and the candidate posters are further subdivided within that already-narrow space (3 sub-columns in grid view, `w-10` = 40px thumbnails in list view).
test: Read frontend/pages/imports/[batchId].vue completely — outer grid container classes, individual card wrapper classes, and resolve-widget wrapper/candidate-grid classes.
expecting: Confirm no `col-span-full`/breakout mechanism exists to let the resolve widget escape its parent cell's width.
next_action: none — root cause confirmed, this is a diagnose-only session (goal: find_root_cause_only). No code changes made.

## Symptoms
<!-- Written during gathering, then IMMUTABLE -->

expected: Expanding the resolve widget on an AMBIGUOUS/NOT_FOUND line shows a candidate grid with large, recognizable poster thumbnails spanning the full page width, letting the user visually pick the correct movie.
actual: The candidate poster thumbnails render far too small to recognize the films. The candidate grid is squeezed into the width of the single grid column/list row the AMBIGUOUS/NOT_FOUND line occupies, instead of spanning the full container width. Functionally, the inline expand/search/resolve behavior itself works correctly.
errors: None — pure layout/CSS sizing defect, no console/runtime errors.
reproduction: |
  1. Open a bulk-import batch detail page (/imports/{batchId}) with at least one AMBIGUOUS or NOT_FOUND line, in grid view (default) or list view.
  2. Click "Resolve" on that line to expand the inline resolve widget.
  3. Widget performs a live TMDB search and renders candidate results.
  4. Observe: candidate poster thumbnails render inside the same narrow grid-cell (grid view: `grid-cols-2` to `grid-cols-5` depending on breakpoint, further subdivided into `grid-cols-3` for candidates) or list-row column (list view: `w-10` = 40px posters) — never at full page width.
started: Introduced in Phase 15 (bulk-import page completion / view toggle / movie links / inline resolve), discovered during UAT on 2026-08-28 immediately after phase merge.

## Eliminated
<!-- APPEND only - prevents re-investigating -->

(none — root cause found on first read, no false hypotheses required elimination)

## Evidence
<!-- APPEND only - facts discovered -->

- timestamp: 2026-08-28T17:51:24Z
  checked: frontend/pages/imports/[batchId].vue, grid view branch (lines 195-290)
  found: |
    Outer grid container (line 195): `class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4"`.
    Each line is one grid item — a `<component :is="... ? 'NuxtLink' : 'div'">` per line.id (lines 196-204), class `"relative overflow-hidden block"` — this component IS a grid cell, sized to 1/2–1/5 of the container width.
    The resolve widget is nested INSIDE this same per-line grid-cell component: `<div v-if="isResolvable(line)" class="mt-2">` (line 241) → toggle button (242-249) → expanded panel `<div v-if="getResolveState(line).expanded" class="mt-2 space-y-2">` (line 251) → candidate results `<div ... class="grid grid-cols-3 gap-1">` (lines 260-263) containing candidate `<button>` elements with `<img class="w-full aspect-[2/3] object-cover ...">` (lines 273-277).
    No `col-span-full`, no portal/teleport, no absolute/fixed breakout positioning, no restructuring to place the widget as a sibling below/outside the outer grid. The candidate grid's 3 columns are computed relative to the single outer grid cell's width (e.g. 1/5 of 1200px ≈ 240px on lg screens, then split 3 ways ≈ 75px per poster).
  implication: This is the direct root cause of the "too small" complaint — candidates are laid out in a `grid-cols-3` sub-grid whose total available width is capped at one outer grid-cell's width, not the page width.

- timestamp: 2026-08-28T17:51:24Z
  checked: frontend/pages/imports/[batchId].vue, list view branch (lines 292-386)
  found: |
    Outer list container (line 292): `class="divide-y divide-border"`, each line rendered as a flex row (line 299: `class="flex gap-4 py-3"`).
    Resolve widget nested inside the row's text column: `<div class="flex flex-col min-w-0 justify-center gap-1">` (line 328) → toggle (337-344) → expanded panel (346-381) → candidate results `<div class="flex flex-wrap gap-1">` (356-358) with `<img class="w-10 aspect-[2/3] object-cover ...">` (line 371) — a fixed 40px-wide thumbnail, regardless of viewport width.
    Same structural pattern as grid view: the widget is a descendant of the narrow per-row text column, never breaks out to full row/page width.
  implication: Confirms the bug is systemic to the resolve-widget's DOM placement, present in both view modes, not a one-off grid-view-only CSS mistake. The fix must relocate/restructure the expanded widget's container so its width is independent of the parent card/row it's toggled from (e.g. render as a full-width block below the grid/row, or use col-span-full + a query for the widget's own row context, or a modal/full-width panel pattern).

## Resolution
<!-- OVERWRITE as understanding evolves -->

root_cause: |
  The resolve widget's expanded candidate-picker (in both grid view and list view) is rendered as a DOM descendant nested inside the same single grid-cell / list-row-column that the "Resolve" toggle button lives in, rather than as a full-width element rendered outside/below the parent grid or row. As a direct consequence, its own internal candidate layout (`grid-cols-3` in grid view; `flex flex-wrap` with fixed `w-10` posters in list view) is constrained to the available width of one grid cell (1/2 to 1/5 of the viewport, per the `grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5` responsive breakpoints on line 195) or one narrow list-row text column, instead of the full page/container width. No breakout mechanism (col-span-full, portal/teleport, absolute overlay, or restructured full-width row) exists to let the widget escape its parent's width constraint.
fix: Applied in Phase 15, Plan 15-04 — the expanded resolve widget's candidate picker is now given a sibling full-width panel (`col-span-full` in grid view, a full-width block in list view) instead of being nested inside the triggering line's narrow grid-cell/row-column.
verification: Confirmed by reading current frontend/pages/imports/[batchId].vue — `col-span-full` classes present on the resolve panel wrapper (grid view) and candidate grid widened accordingly (`grid-cols-3 sm:grid-cols-4 md:grid-cols-6`).
files_changed: [frontend/pages/imports/[batchId].vue (Phase 15, Plan 15-04)]
