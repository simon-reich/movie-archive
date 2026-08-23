---
phase: 10-bulk-import-engine
plan: 02
subsystem: frontend
tags: [nuxt, vue, bulk-import, file-upload, add-film]
requires:
  - phase: 10-bulk-import-engine (plan 01)
    provides: "POST /movies/bulk-import backend endpoint (multipart upload, 202 Accepted, 422 on missing TMDB key)"
provides:
  - "uploadBulkImport(file) composable function on useMovies() — multipart POST to /api/movies/bulk-import"
  - "Bulk Import section on the Add Film page (add.vue) — file input + Import button + inline success/error message"
affects: [bulk-import-feedback-ui, add-film-page]
actuals:
  tokens: 1715
  tasks: 2
  commits: 2
tech-stack:
  added: []
  patterns:
    - "Multipart FormData upload via ofetch — no manual Content-Type header, ofetch auto-generates the boundary"
    - "Inline success/error message pattern for a fire-and-forget async trigger (mirrors existing searchTmdb/saveMovie error handling in add.vue)"
key-files:
  created: []
  modified:
    - frontend/composables/useMovies.ts
    - frontend/pages/add.vue
    - frontend/test/unit/composables/useMovies.spec.ts
    - frontend/test/unit/pages/add.spec.ts
key-decisions:
  - "Reused the exact Search-button class string and FormErrorBanner component for the Import button/error UI instead of introducing new styling, per UI-SPEC's reuse-first convention"
  - "No progress bar or per-line results list rendered here — explicitly deferred to Phase 11 per 10-CONTEXT.md D-13 boundary"
patterns-established:
  - "uploadBulkImport composable pattern: builds FormData, omits Content-Type, includes authHeaders() — reusable template for any future file-upload endpoint"
requirements-completed: [IMPORT-01]
coverage:
  - id: D1
    description: "uploadBulkImport composable function posts multipart FormData to /api/movies/bulk-import with Authorization header, no Content-Type override"
    requirement: "IMPORT-01"
    verification:
      - kind: unit
        ref: "test/unit/composables/useMovies.spec.ts#uploadBulkImport sends POST multipart to /api/movies/bulk-import and returns status"
        status: pass
      - kind: unit
        ref: "test/unit/composables/useMovies.spec.ts#uploadBulkImport throws on 422 when no TMDB key configured"
        status: pass
    human_judgment: false
  - id: D2
    description: "Add Film page has a Bulk Import section with file input and Import button, calling uploadBulkImport on submit and showing inline success/error feedback"
    requirement: "IMPORT-01"
    verification:
      - kind: unit
        ref: "test/unit/pages/add.spec.ts#useMovies.uploadBulkImport sends file and returns started status"
        status: pass
      - kind: unit
        ref: "test/unit/pages/add.spec.ts#shows 422 error when no TMDB key configured for bulk import"
        status: pass
    human_judgment: true
    rationale: "Visual rendering of the new section (file input styling, button disabled state, message placement) is not exercised by DOM-mount tests in this file's established composable-level testing convention — confirmed via source read and lint pass, but a visual check would give higher confidence."
duration: 8min
completed: 2026-08-23
status: complete
---

# Phase 10 Plan 02: Bulk Import Upload Trigger Summary

**Bulk import upload trigger added to Add Film page via new uploadBulkImport composable — file input, Import button, and inline success/error feedback, no progress UI (Phase 11 scope).**

## Performance
- **Duration:** 8min
- **Started:** 2026-08-23T20:17:06Z
- **Completed:** 2026-08-23T20:25:15Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Added `uploadBulkImport(file)` to the `useMovies()` composable — builds `FormData`, posts to `/api/movies/bulk-import` with `authHeaders()`, no manual `Content-Type` override
- Added a new "Bulk Import" section to `add.vue`: native file input (`.txt,.csv` accept hint), Import button (disabled until a file is selected or while uploading), and inline acknowledgement/error messaging
- Wired the full request → response flow: success shows "Import started — this runs in the background.", 422 reuses the existing "No TMDB key configured. Add your key in Settings." copy, any other error shows a generic "Import failed. Please try again." — the upload never fails silently
- Extended both composable and page test suites following each file's established testing convention (direct `$fetch` mock for the composable, composable-level assertions for the page)

## Task Commits
1. **Task 1: Add uploadBulkImport to useMovies composable** - `ff01083` (feat)
2. **Task 2: Add Bulk Import section to the Add Film page** - `4096b61` (feat)

## Files Created/Modified
- `frontend/composables/useMovies.ts` - Added `uploadBulkImport(file)` function and exported it from the composable's return object
- `frontend/pages/add.vue` - Added Bulk Import section (state, handlers, template) after the existing poster grid
- `frontend/test/unit/composables/useMovies.spec.ts` - Added two tests for `uploadBulkImport` (success + 422)
- `frontend/test/unit/pages/add.spec.ts` - Added two tests mirroring the file's existing composable-level assertion convention

## Decisions Made
- Reused the exact Search button class string and the shared `FormErrorBanner` component rather than inventing new styling — keeps the new section visually consistent with the rest of the page and the UI-SPEC's "no rounded corners" rule (`rounded-none` only, verified via grep).
- No progress bar or results list — explicitly out of scope per `10-CONTEXT.md` (Phase 11 boundary, D-13). This plan only builds the trigger and a single inline acknowledgement/error message.

## Deviations from Plan

None - plan executed exactly as written.

**Note (environment, not a plan deviation):** `frontend/node_modules` was absent in this fresh worktree (expected — `node_modules` is gitignored and worktrees don't inherit it). Ran `pnpm install --frozen-lockfile` before executing any tests; this is standard environment setup, not a code change, and is not tracked as a deviation.

**Total deviations:** 0 auto-fixed. **Impact:** None — plan matched the existing codebase conventions exactly (composable shape, error-handling pattern, button styling).

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
Phase 11 (Bulk Import Feedback UI) can build directly on this trigger: the `uploadBulkImport` composable function and the `bulk-import` section on `add.vue` are the integration points for adding live progress and a per-line results list. The backend contract (Plan 10-01: `POST /movies/bulk-import`, 202/422 responses) is fully exercised by this plan's frontend wiring — no blockers.

---
*Phase: 10-bulk-import-engine*
*Completed: 2026-08-23*
