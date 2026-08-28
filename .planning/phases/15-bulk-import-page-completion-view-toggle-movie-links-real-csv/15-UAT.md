---
status: diagnosed
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
source: [15-VERIFICATION.md]
started: 2026-08-28T14:25:58Z
updated: 2026-08-28T20:25:00Z
---

## Current Test

[testing complete]

## Tests

### 1. List view persists across a real hard browser reload (D-02)
expected: View mode persists across a genuine full-page reload, not just a re-mounted component with pre-seeded localStorage.
result: pass

### 2. SAVED-card navigation + PARSE_ERROR visual distinctiveness in a real browser
expected: Open a real bulk-import batch with mixed statuses (SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR). Click a SAVED card → navigates to /movies/{id}. PARSE_ERROR reads as a clearly distinct category (not just another status icon).
result: issue
reported: "Saved Cards verlinken nicht zur Detailpage. Das gibt nichts, wo man draufklicken kann und dann weitergeleitet wird. Ich weiß nicht, was Distinct Category bei Part Error heißen soll, aber die Ansicht von Path Error ist furchtbar: Wir haben da auch diese Postercard oder sowas, Der wirkliche String ist zum Teil beschnitten und nicht einsehbar dadurch, Wobei das natürlich das Wichtige ist bei einem Pass Error, dass man sehen kann, wo der Fehler dort liegt. Pass Arrows sollen nicht als Poster angezeigt werden, sondern als Zeile. Generell sollte auf jeden Fall sortiert werden nach erst saved, also completed movies, Wo alles geklappt hat, Dann sollen die Ambiguous kommen. Neue Zeile. Dann fangen die Ambiguous an. Die können ruhig auch als Poster sein. Dann neue Zeile, kommen die Path Errors, aber dort einfach nur String als Zeile mit einem Icon davor oder so, aber gelistet, die brauchen keine Poster, da müssen wir vor allem den kompletten String sehen, der falsch formatiert ist."
severity: major

### 3. End-to-end inline resolve against the real TMDB API
expected: Expand the resolve widget on a real AMBIGUOUS or NOT_FOUND line, run a live TMDB search, pick a candidate, and confirm the batch report immediately shows SAVED with a working movie link.
result: issue
reported: "Da ist leider die Anzeige viel zu klein, der Poster. Man erkennt die Filme nicht. Also ist es gut, dass es inline passiert, Aber das Gritt und damit die Anzeige der Poster muss viel größer sein. Zurzeit wird hier auch nur in das Column gequetscht von dem Poster, was zu resolven ist. Das muss über die volle Breite gehen."
severity: major

### 4. Real-world regression import of saubere_filmliste.txt (D-17)
expected: Run a real bulk import against saubere_filmliste.txt (repo root, untracked, 1139 lines, semicolon format) using the live app stack (TMDB key, DB, SSE progress) and confirm every line resolves to the identical per-line outcome it would have produced before this phase — a no-op regression check.
result: pass

## Summary

total: 4
passed: 2
issues: 2
pending: 0
skipped: 0
blocked: 0

## Gaps

- gap_id: G-15-2
  truth: "SAVED bulk-import line's card/row is entirely clickable and navigates to /movies/{movieId} (D-05); PARSE_ERROR lines are visually distinct from AMBIGUOUS/NOT_FOUND (D-11 display half)"
  status: failed
  reason: |
    User reported: SAVED cards do not link to the movie detail page at all — nothing clickable, no navigation happens.
    PARSE_ERROR is rendered as a poster card, which truncates the raw line text so the actual malformed string (the whole point of a PARSE_ERROR) isn't fully visible.
    Additional design feedback bundled with this report (scope beyond the original D-05/D-11 truths — treat as new requirements, not just a bug fix), clarified via follow-up Q&A with the user:
      1. PARSE_ERROR lines should never render as poster cards — they should render as list rows (icon + full raw string), regardless of the page's grid/list view toggle, since seeing the complete malformed string is the point.
      2. SAVED, AMBIGUOUS, and NOT_FOUND may keep their existing card/poster-style rendering (no row-list requirement for these three).
      3. The page must be grouped into four sections, in this fixed order: SAVED (completed) → AMBIGUOUS → NOT_FOUND → PARSE_ERROR. Each status gets its OWN section (NOT_FOUND must not be merged into the same section as AMBIGUOUS) — not interleaved in one flat grid/list as it is today. User confirmed explicitly: "Das soll alles nach dem Outcome sortiert sein" (everything should be sorted/grouped by outcome/status) — this is a sort-by-status grouping requirement, not a cosmetic tweak.
  severity: major
  test: 2
  root_cause: "frontend/pages/imports/[batchId].vue wraps SAVED cards in <component :is=\"movieLinkTarget(line) ? 'NuxtLink' : 'div'\" ...>. Nuxt 3 only auto-registers built-in components like NuxtLink into a file's compiled output when it detects a LITERAL <NuxtLink> tag in that file's template AST — this file never uses <NuxtLink> as a literal tag anywhere, only the bare string inside a JS ternary bound to :is. Because of that, Nuxt's compiler never injects the registration; at runtime resolveDynamicComponent('NuxtLink') fails and falls back to rendering an inert unknown custom element <nuxtlink to=\"...\"> — not an <a> — with no href and no click/navigation. This is a documented Nuxt 3 limitation (nuxt/nuxt#13659, #23450, #10545, #22206). The existing Vitest suite (imports-batchId.spec.ts) passed anyway because it explicitly stubs NuxtLink via Vue Test Utils' global.stubs, which masks exactly this runtime-resolution gap — explaining why 15-VERIFICATION.md's code read + passing test diverged from live-browser reality."
  artifacts:
    - path: "frontend/pages/imports/[batchId].vue"
      issue: "Lines ~196-197 (grid) and ~293-294 (list): <component :is=\"movieLinkTarget(line) ? 'NuxtLink' : 'div'\"> — string-literal :is binding is never statically detected by Nuxt's compiler, so NuxtLink is never registered for this SFC."
    - path: "frontend/test/unit/pages/imports-batchId.spec.ts"
      issue: "global.stubs registers a NuxtLink stub by name, which passes regardless of literal-tag usage — masks the exact gap that breaks the real app. Needs hardening alongside the fix."
  missing:
    - "Resolve the component reference via `const NuxtLink = resolveComponent('NuxtLink')` in <script setup> (Nuxt's documented workaround — the literal string argument to resolveComponent IS statically detectable), then bind `:is=\"movieLinkTarget(line) ? NuxtLink : 'div'\"` in both the grid and list wrappers using the resolved component object."
    - "Add a real-browser/E2E-style check (or explicitly re-run human UAT for this specific truth) since the unit test's stub cannot itself prove the fix — the whole point of the bug is that the stub was hiding it."
  debug_session: ".planning/debug/bulk-import-saved-card-link-broken.md"

- gap_id: G-15-3
  truth: "AMBIGUOUS or NOT_FOUND line can be resolved in-place: expand, fresh TMDB search prefilled with title, pick candidate, save (D-08)"
  status: failed
  reason: |
    User reported: the resolve widget works functionally (inline expand behavior is good), but the candidate poster thumbnails are rendered far too small — user cannot actually recognize/distinguish the movies from the tiny posters, which defeats the purpose of a visual pick-a-candidate UI.
    Root cause per user's own diagnosis: the candidate grid is currently squeezed into the width of a single column (of whatever grid/list the parent line sits in), rather than spanning the full available width of the page/container. Fix: the expanded resolve widget's candidate grid must break out to full container width, with correspondingly larger poster thumbnails.
  severity: major
  test: 3
  root_cause: "The resolve widget's expanded candidate-picker (in both grid view and list view) is rendered as a DOM descendant INSIDE the same single grid-cell / list-row-column its 'Resolve' toggle button lives in, rather than as a full-width element outside/below the parent grid or row. Grid view: outer container is `grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4`; each line is one cell, and the resolve widget's own `grid grid-cols-3 gap-1` candidate grid is nested inside that same cell (so on a lg viewport, candidates are split into thirds of a fifth of the page). List view: the widget lives inside the row's narrow text column with hardcoded `w-10` (40px) poster thumbnails regardless of viewport. No breakout mechanism (col-span-full, teleport/portal, absolute overlay, or a restructured full-width row) exists anywhere in the file."
  artifacts:
    - path: "frontend/pages/imports/[batchId].vue"
      issue: "Grid-view resolve panel (~lines 241-287, candidate grid at 260-286) and list-view resolve panel (~lines 336-382, candidates at 355-380) are both nested inside their single parent card/row instead of breaking out to full container width."
  missing:
    - "Restructure the expanded resolve widget to render as a full-width block independent of the triggering card/row's width — e.g. a sibling element positioned via col-span-full immediately after its row in grid view (CSS Grid supports a full-width item mid-grid), or a dedicated full-width slot/overlay below the entire results list/grid (keyed by which line is currently expanded)."
    - "Drop the fixed w-10 / grid-cols-3 sub-constraints on candidate posters once the container is full-width, so poster thumbnails render meaningfully larger."
  debug_session: ".planning/debug/resolve-widget-narrow-grid.md"
