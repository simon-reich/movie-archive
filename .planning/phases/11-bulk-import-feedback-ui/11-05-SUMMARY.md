---
phase: 11-bulk-import-feedback-ui
plan: 05
subsystem: ui
tags: [nuxt, vue, bulk-import, navigation]

# Dependency graph
requires:
  - phase: 11-03
    provides: "GET /movies/bulk-import/batches — per-batch date/line-count/status-counts summary"
  - phase: 11-04
    provides: "useBulkImport.ts composable (getBatches()) and frontend/pages/imports/[batchId].vue (detail page target for each row)"
provides:
  - "frontend/pages/imports/index.vue — batch list page (date, line count, status distribution per batch)"
  - "AppNav.vue Imports nav entry (desktop + mobile drawer)"
affects: []

# Actuals (#2632)
actuals:
  tokens: 2087
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "List/summary page loading pattern (ref+isLoading+error, try/catch/finally in onMounted) mirrors useDashboard.ts's fetchDashboard() shape exactly, reused for getBatches()"
    - "NuxtLink test stub convention ({ template: '<a :href=\"to\"><slot /></a>', props: ['to'] }) reused from MovieCard.spec.ts to assert row hrefs in a Nuxt-runtime-less vitest environment"

key-files:
  created:
    - frontend/pages/imports/index.vue
    - frontend/test/unit/pages/imports-index.spec.ts
  modified:
    - frontend/components/AppNav.vue

key-decisions:
  - "Status summary renders lowercase labels (\"saved\"/\"ambiguous\"/\"not found\"/\"parse error\") matching the exact wording used in [batchId].vue's statusLabel() function (lowercased), only rendering non-zero counts per the plan's must_haves key_link spec"
  - "Used lucide-vue-next's History icon for the new nav entry (plan suggested History or ListChecks as options)"

patterns-established:
  - "Batch list -> batch detail navigation: each row is a NuxtLink to /imports/{batchId}, landing directly on Plan 11-04's live-progress/results page (which synthesizes an already-complete progress event server-side for a finished batch, so revisiting a completed import shows the results grid immediately, not a stuck connecting state)"

requirements-completed: [IMPORT-06]

coverage:
  - id: D1
    description: "The user can browse a list of past bulk-import batches (date, line count, status distribution) at any time, not just right after uploading"
    requirement: IMPORT-06
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-index.spec.ts#renders each batch row linking to its own detail page, newest first as returned"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-index.spec.ts#renders a compact status summary per batch, omitting zero counts"
        status: pass
    human_judgment: false
  - id: D2
    description: "Clicking a batch in the list opens its full per-line results (persisted-report requirement)"
    requirement: IMPORT-06
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-index.spec.ts#renders each batch row linking to its own detail page, newest first as returned"
        status: pass
    human_judgment: true
    rationale: "The href target (/imports/{batchId}) is unit-verified, and the target page's own rendering was verified in Plan 11-04's test suite, but no test in this plan drives an actual click-and-navigate through Nuxt's router — a human should confirm the full click-through in a real browser (see Known Stubs / human-check note below)."
  - id: D3
    description: "The batch list page is reachable from the app's main navigation, not only via a direct URL"
    requirement: IMPORT-06
    verification:
      - kind: unit
        ref: "frontend/test/unit/components (AppNav.vue changes covered by full suite pass — no dedicated AppNav.spec.ts exists in this codebase)"
        status: pass
      - kind: static
        ref: "vue-tsc --noEmit and eslint both clean on AppNav.vue"
        status: pass
    human_judgment: true
    rationale: "No AppNav.spec.ts exists in this codebase to assert the new link's presence/href programmatically; visual confirmation of the nav entry on both desktop and mobile drawer is part of the still-pending human-check walkthrough."

duration: ~20min
completed: 2026-08-24
status: complete
---

# Phase 11 Plan 5: Batch List Page + Nav Entry Summary

**New `/imports` batch list page (reusing `useDashboard.ts`'s loading/error/empty pattern and `useBulkImport.ts`'s `getBatches()`) plus an "Imports" nav entry in both `AppNav.vue` link lists, closing the loop on D-03's core requirement — users can now find their way back to a past bulk-import's results from anywhere in the app, not just via a link that only exists for a few clicks right after upload.**

## Performance

- **Duration:** ~20 min
- **Tasks:** 2
- **Files modified:** 3 (2 created, 1 modified)

## Accomplishments
- `frontend/pages/imports/index.vue`: `<script setup>` calling `useBulkImport().getBatches()` on mount, with a `batches`/`isLoading`/`error` ref shape and try/catch/finally sequencing that mirrors `useDashboard.ts`'s `fetchDashboard()` exactly
- Loading state shows `SpinnerIcon` (matches `index.vue`'s loading block); error state shows the fetch failure text; empty state shows "No bulk imports yet." plus a `NuxtLink to="/add"`; otherwise renders one row per batch as a `NuxtLink :to="/imports/{batchId}"` showing the formatted `createdAt` date, `totalLines`, and a compact non-zero status summary (e.g. "12 saved · 2 ambiguous · 1 not found") using the same lowercase status wording as `[batchId].vue`'s results grid
- `frontend/components/AppNav.vue`: new `NuxtLink to="/imports"` entry (lucide `History` icon, label "Imports") added to both the desktop `hidden md:flex` link row (after `/search`) and the mobile slide-in drawer link list (same position, same `active-class="text-primary"` + `@click="drawerOpen = false"` pattern as every other drawer link)

## Task Commits

Each task was committed atomically:

1. **Task 1: Batch list page** - `013b155` (feat)
2. **Task 2: Navigation entry for the batch list page** - `c8ee5cd` (feat)

_Note: no separate plan-metadata commit — worktree execution mode excludes STATE.md/ROADMAP.md; this SUMMARY.md commit follows._

## Files Created/Modified
- `frontend/pages/imports/index.vue` - new page: loading/error/empty/list states, batch rows linking to `/imports/{batchId}`
- `frontend/test/unit/pages/imports-index.spec.ts` - mount-based tests (following `imports-batchId.spec.ts`'s convention) for loading, per-row links, status summary, error, and empty states
- `frontend/components/AppNav.vue` - "Imports" `NuxtLink` added to both desktop nav row and mobile drawer

## Decisions Made
- Status summary uses lowercase labels ("saved"/"ambiguous"/"not found"/"parse error") to match `[batchId].vue`'s existing `statusLabel()` wording (lowercased for the compact inline summary), and only renders entries with a non-zero count per the plan's spec
- Reused the `NUXT_LINK_STUB` test convention already established in `MovieCard.spec.ts` (`{ template: '<a :href="to"><slot /></a>', props: ['to'] }`) to assert each batch row's `href` in vitest's Nuxt-runtime-less environment — `NuxtLink` resolves to an unregistered `RouterLink` component under plain `@vue/test-utils` `mount()`, so this stub is required wherever a test needs to read the rendered `href`
- Chose lucide-vue-next's `History` icon (the plan offered `History` or `ListChecks` as options) for the new nav entry

## Deviations from Plan

None - plan executed exactly as written.

### Worktree bootstrap note (not a deviation)
This worktree had no `node_modules`/`.nuxt` yet (fresh worktree, matching the same one-time bootstrap issue documented in Plan 11-04's Summary). Ran `pnpm install` (which triggers `postinstall: nuxt prepare` automatically) before any test/typecheck/lint command — a one-time environment bootstrap step, not a plan deviation.

## Issues Encountered
None beyond the worktree bootstrap noted above.

## User Setup Required
None - no external service configuration required.

## Known Stubs / Pending Human Verification

Per the executor's explicit instructions, the `<human-check>` block in Task 2's `<verify>` — starting the dev environment and manually walking the full upload → track progress → results → revisit-later flow in a real browser — was **not** performed by this autonomous execution. Everything else in Task 2 (the nav entry change itself, plus its automated vitest/typecheck/lint verification) is complete and green.

**Still pending, requires a human:**
1. Start the dev environment (`docker compose up` or equivalent).
2. Upload a bulk-import file on `/add`, confirm "Track progress →" appears.
3. Click it, confirm `/imports/{batchId}` shows a live processed/total count that updates, then transitions to the results grid.
4. Navigate to `/imports` via the nav bar, confirm the just-completed batch appears in the list with the correct date/line count/status counts.
5. Click it again, confirm the same results grid re-renders from persisted data (reload the page to be sure it's not just cached SSE state).
6. Visually confirm "Imports" is visible in both the desktop nav row and the mobile hamburger drawer.

This is the same walkthrough specified in `11-05-PLAN.md` Task 2's `<human-check>` block and in the phase's overall `<verification>` section — it remains the closing verification step for Phase 11 as a whole.

## Next Phase Readiness
- All 5 planned Phase 11 plans (11-01 through 11-05) are now code-complete. The only remaining item before the phase can be considered fully verified is the human browser walkthrough above.
- No blockers for any downstream phase.

## Self-Check: PASSED

Both created/modified files verified present on disk (`frontend/pages/imports/index.vue`, `frontend/test/unit/pages/imports-index.spec.ts`, `frontend/components/AppNav.vue` diff); both task commits (`013b155`, `c8ee5cd`) verified in `git log`; full frontend test suite (175 tests across 25 files) verified green via synchronous foreground `npx vitest run`; `npx vue-tsc --noEmit` and `npx eslint` both clean on all changed files.

---
*Phase: 11-bulk-import-feedback-ui*
*Completed: 2026-08-24*
