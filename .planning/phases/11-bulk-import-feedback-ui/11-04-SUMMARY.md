---
phase: 11-bulk-import-feedback-ui
plan: 04
subsystem: ui
tags: [nuxt, vue, sse, fetch-event-source, bulk-import]

# Dependency graph
requires:
  - phase: 11-01
    provides: "batchId threaded through the async pipeline, returned in POST /movies/bulk-import's 202 response"
  - phase: 11-02
    provides: "GET /movies/bulk-import/{batchId}/progress SSE endpoint (text/event-stream, progress/complete events)"
  - phase: 11-03
    provides: "GET /movies/bulk-import/batches/{batchId} — per-line title/originalTitle/year/status/posterPath"
provides:
  - "useBulkImport.ts composable: getBatches(), getBatchDetail(batchId), subscribeToProgress(batchId, onProgress)"
  - "frontend/pages/imports/[batchId].vue — live progress + per-line results page"
  - "add.vue links to the new batch's progress page immediately after a successful bulk-import upload"
affects: [11-05-frontend-batch-list]

# Actuals (#2632)
actuals:
  tokens: 8722
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: ["@microsoft/fetch-event-source@2.0.1"]
  patterns:
    - "SSE consumed via fetchEventSource(url, { headers: authHeaders(), onmessage, onerror }) instead of native EventSource, since this app's JWT is header-only (no cookie fallback)"
    - "onerror rethrows to stop the library's default retry-forever behavior on a fatal SSE error (403/404)"
    - "Progress -> complete -> detail-fetch sequencing: the same onProgress callback path handles both a genuinely-just-finished run and reconnecting to an already-finished batch (server-side synthesized complete event from Plan 11-02), so the page never needs to know in advance which case it is"

key-files:
  created:
    - frontend/composables/useBulkImport.ts
    - frontend/pages/imports/[batchId].vue
    - frontend/test/unit/composables/useBulkImport.spec.ts
    - frontend/test/unit/pages/imports-batchId.spec.ts
  modified:
    - frontend/package.json
    - frontend/pnpm-lock.yaml
    - frontend/composables/useMovies.ts
    - frontend/pages/add.vue
    - frontend/test/unit/pages/add.spec.ts

key-decisions:
  - "Added a visible title <p> below every result card (not just the text-only fallback for poster-less rows), matching add.vue's existing poster-grid convention and IMPORT-06's literal requirement (title/poster/status per line) — RESEARCH.md's illustrative markup omitted this for image-having rows, but the full grid requires a title unconditionally"
  - "uploadBulkImport()'s TypeScript return type extended from { status: string } to { status: string, batchId: string } (useMovies.ts, not in this plan's files_modified) — the backend has returned batchId since Plan 11-01, but the frontend type was never updated; add.vue's new response.batchId read would otherwise fail typecheck"

patterns-established:
  - "Live-progress pages: 'Connecting...' (progress === null) -> processed/total text (progress non-null, not complete) -> results grid (batch loaded) — a 3-state sequence driven entirely by the SSE composable's callback, no manual polling"

requirements-completed: [IMPORT-05, IMPORT-06]

coverage:
  - id: D1
    description: "While an import is running, the user sees processed/total update live on /imports/{batchId} without a manual refresh"
    requirement: IMPORT-05
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#shows processed/total text while progress is incomplete"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/composables/useBulkImport.spec.ts#subscribeToProgress opens an SSE connection with auth headers and parses progress payloads"
        status: pass
    human_judgment: false
  - id: D2
    description: "Once the import completes, the same page shows the per-line results list (title, poster or fallback, status)"
    requirement: IMPORT-06
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders each line title and status once progress completes and detail has loaded"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders a text-only fallback card (no broken img) for a line with posterPath null"
        status: pass
    human_judgment: false
  - id: D3
    description: "A user who navigates to /imports/{batchId} for an already-completed batch sees the results immediately, not a stuck 'connecting' state"
    requirement: IMPORT-05
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders each line title and status once progress completes and detail has loaded"
        status: pass
    human_judgment: false
  - id: D4
    description: "After a successful bulk-import upload on the Add Film page, the user can reach the new batch's progress page"
    requirement: IMPORT-06
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/add.spec.ts#a successful bulk import upload resolves a batchId the Add Film page can link to"
        status: pass
    human_judgment: true
    rationale: "The NuxtLink's visibility and correct href are code-reviewed and typechecked but not asserted via a full component mount+click in this plan's test suite (add.spec.ts follows a composable-level assertion convention, not DOM mounting, for this file) — a human should visually confirm 'Track progress ->' appears and navigates correctly."

duration: ~40min
completed: 2026-08-24
status: complete
---

# Phase 11 Plan 4: Live Progress + Results Page Summary

**New `useBulkImport.ts` composable (SSE progress subscription via `@microsoft/fetch-event-source` + batch-detail fetch) backing a new `/imports/{batchId}` page that shows a live processed/total indicator while an import runs and a per-line poster/status results grid once it completes, reachable from the Add Film page immediately after a successful upload.**

## Performance

- **Duration:** ~40 min
- **Tasks:** 2
- **Files modified:** 9 (4 created, 5 modified)

## Accomplishments
- `frontend/composables/useBulkImport.ts`: `getBatches()`, `getBatchDetail(batchId)`, `subscribeToProgress(batchId, onProgress)` — all reusing `useMovies.ts`'s exact `authHeaders()`/`accessTokenCookie` pattern; `subscribeToProgress` uses `@microsoft/fetch-event-source` (not native `EventSource`, which cannot attach the required `Authorization: Bearer` header) and returns an `AbortController.abort()`-backed unsubscribe function
- `frontend/pages/imports/[batchId].vue`: three-state page — "Connecting..." spinner before the first SSE event, live `{processed} / {total} processed` text + progress bar while incomplete, then a results grid once the batch detail loads (triggered by the SSE `complete` event, whether from a genuinely-just-finished run or an already-finished batch synthesized server-side by Plan 11-02)
- Results grid reuses `add.vue`'s poster-card/status-overlay markup exactly (`aspect-[2/3] object-cover bg-card border border-border`, `bg-background/70` overlay, `CheckCircle2`/`XCircle` icons), mapped from the 4 `BulkImportLineStatus` values, with a text-only fallback card for any line whose `posterPath` is `null`
- `add.vue`'s `handleBulkImport()` now captures the upload response's `batchId` into `lastBulkImportBatchId`, rendering a `Track progress →` link to `/imports/{batchId}` right after a successful upload

## Task Commits

Each task was committed atomically:

1. **Task 1: useBulkImport composable + live progress/results page** - `42a2459` (feat)
2. **Task 2: Reach the progress page from the Add Film upload flow** - `aeae1f9` (feat)

_Note: no separate plan-metadata commit — worktree execution mode excludes STATE.md/ROADMAP.md; this SUMMARY.md commit follows._

## Files Created/Modified
- `frontend/composables/useBulkImport.ts` - new composable: `getBatches`, `getBatchDetail`, `subscribeToProgress`, and the 4 exported TypeScript interfaces
- `frontend/pages/imports/[batchId].vue` - new page: connecting/live-progress/results-grid states
- `frontend/test/unit/composables/useBulkImport.spec.ts` - direct-`$fetch`-mock tests + a mocked `fetchEventSource` import for the SSE subscription behavior
- `frontend/test/unit/pages/imports-batchId.spec.ts` - `vi.mock`'d composable, `mount()`-based DOM assertions (`movies-id.spec.ts` convention)
- `frontend/package.json` / `frontend/pnpm-lock.yaml` - `@microsoft/fetch-event-source@2.0.1` added
- `frontend/composables/useMovies.ts` - `uploadBulkImport()`'s return type extended to include `batchId` (deviation, see below)
- `frontend/pages/add.vue` - `lastBulkImportBatchId` ref + `Track progress →` link
- `frontend/test/unit/pages/add.spec.ts` - new test asserting the upload response's `batchId` is available to the component

## Decisions Made
- Added a title `<p>` below every result card (not only the poster-less fallback rows), since IMPORT-06 requires title/poster/status per line for every row, and `add.vue`'s existing poster-grid convention already does this — RESEARCH.md's illustrative markup only showed it for the fallback case
- Extended `uploadBulkImport()`'s TypeScript return type in `useMovies.ts` to include `batchId` (see Deviations) rather than duplicating a second upload function in the new composable, since Task 2 explicitly reuses the existing `uploadBulkImport()` call site in `add.vue`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Extended `uploadBulkImport()`'s return type to include `batchId`**
- **Found during:** Task 2 (Reach the progress page from the Add Film upload flow)
- **Issue:** `add.vue`'s `handleBulkImport()` needed to read `response.batchId` from `uploadBulkImport()`'s resolved value (the backend has returned `batchId` in the 202 response since Plan 11-01), but `useMovies.ts`'s TypeScript signature still declared `Promise<{ status: string }>` — accessing `.batchId` would fail `vue-tsc` typecheck. `useMovies.ts` was not in this plan's `files_modified` list.
- **Fix:** Changed `uploadBulkImport()`'s return type (and the `$fetch` generic) to `Promise<{ status: string, batchId: string }>` in `frontend/composables/useMovies.ts`.
- **Files modified:** `frontend/composables/useMovies.ts`
- **Verification:** `npx vue-tsc --noEmit -p tsconfig.json` passes with no errors; `frontend/test/unit/pages/add.spec.ts` and `frontend/test/unit/composables/useMovies.spec.ts` both pass.
- **Committed in:** `aeae1f9` (Task 2 commit)

**2. [Rule 1 - Bug] `pnpm add` accidentally targeted the shared main-repo checkout instead of the worktree**
- **Found during:** Task 1, immediately after the first install attempt
- **Issue:** The first `pnpm add @microsoft/fetch-event-source` was run via `cd /Users/simonreich/git/private/movie-archive/frontend && pnpm add ...` — an absolute path copied from the plan's `${PROJECT_ROOT}`-style required-reading paths without first resolving `PROJECT_ROOT` via `git rev-parse --show-toplevel` from inside the worktree. This path resolves to the shared main-repo checkout (`/Users/simonreich/git/private/movie-archive`), not the worktree (`.../.claude/worktrees/agent-a0add93464eba72ef`), so the install modified the main checkout's `package.json`/`pnpm-lock.yaml`/`node_modules` instead.
- **Fix:** Ran `pnpm remove @microsoft/fetch-event-source` in the main-repo checkout to revert the accidental install (confirmed via `grep`: no remaining references in `package.json`/`pnpm-lock.yaml`, `node_modules/@microsoft` removed). The package.json key ordering in the main checkout differs cosmetically from before (pnpm re-sorted `devDependencies` on install/remove) — the sandbox's write-guard for the shared checkout blocked a further attempt to restore exact key order via `Write`, so a trivial ordering diff remains there; no dependency versions or content changed. Re-ran the install correctly inside the worktree (relative `cd frontend && pnpm add ...`, cwd defaults to the worktree root between Bash calls) — confirmed via `git status --short` showing the change in the worktree's own git index.
- **Files modified:** none in the worktree from this specific mistake (the worktree's `frontend/package.json` was never touched by the bad install); `frontend/package.json` in the main-repo checkout has a cosmetic `devDependencies` key-order diff as a residual side effect.
- **Verification:** `git status --short frontend/package.json frontend/pnpm-lock.yaml` in the worktree showed the expected `M`/`M` only after the corrected install; `grep -n "fetch-event-source"` confirmed the main-repo checkout has zero references post-revert.
- **Committed in:** N/A (main-repo-checkout state, not part of any worktree commit) — the correct worktree install is committed in `42a2459` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking type fix, 1 tooling-path bug caught and corrected before any worktree commit)
**Impact on plan:** No scope creep. The `useMovies.ts` type fix was unavoidable for a green typecheck. The path mistake was caught and reverted before any worktree file was ever touched incorrectly — no worktree commit was affected.

## Issues Encountered
- `.nuxt/tsconfig.json` did not exist yet in this freshly-created worktree (Nuxt's build-time type generation had never run there), causing `vitest run` to fail with a TSConfck parse error before any test executed. Resolved by running `npx nuxt prepare` once, which regenerates `.nuxt/` (a build artifact, not committed) — not a plan deviation, a one-time worktree bootstrap step.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- `useBulkImport.ts` and `/imports/{batchId}.vue` are live, tested, and reachable from the Add Film page — Plan 11-05's batch-list page can reuse the same composable's `getBatches()` and link into this page.
- No blockers.

## Self-Check: PASSED

All created files verified present on disk (`useBulkImport.ts`, `useBulkImport.spec.ts`, `pages/imports/[batchId].vue`, `imports-batchId.spec.ts`); both task commits (`42a2459`, `aeae1f9`) verified in `git log`; full frontend test suite (170 tests across 24 files) verified green via a synchronous foreground `npx vitest run`; `npx vue-tsc --noEmit` and `npx eslint` both clean on all changed files.

---
*Phase: 11-bulk-import-feedback-ui*
*Completed: 2026-08-24*
