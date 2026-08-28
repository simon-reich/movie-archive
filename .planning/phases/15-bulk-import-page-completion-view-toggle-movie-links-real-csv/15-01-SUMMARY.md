---
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
plan: 01
subsystem: ui
tags: [vue, nuxt, spring-boot, bulk-import, localStorage]

requires:
  - phase: 11-bulk-import-feedback-ui
    provides: "/imports/{batchId} batch-detail page reading GET /movies/bulk-import/batches/{batchId}"
provides:
  - "BulkImportLineResult DTO carrying id/movieId/rawLine, resolved server-side for SAVED lines only"
  - "Whole-card/whole-row NuxtLink from SAVED bulk-import lines to their saved movie"
  - "Visually distinct PARSE_ERROR card/row showing the exact raw uploaded line text"
  - "Grid/list view toggle for /imports/{batchId}, persisted via localStorage, grid default"
affects: [15-02-inline-resolve, 15-03-real-csv-parsing]

actuals:
  tokens: 6747
  tasks: 2
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Dynamic <component :is=\"...\"> (NuxtLink vs div) for a conditionally-linkable card/row, matching MovieCard.vue's NuxtLink-wraps-poster pattern"
    - "localStorage read/write guarded entirely inside onMounted()/a client-only watch() to avoid SSR hydration mismatch, per explicit deviation_note in 15-01-PLAN.md (this page intentionally diverges from stores/search.ts's useCookie-based fix)"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
    - frontend/composables/useBulkImport.ts
    - "frontend/pages/imports/[batchId].vue"
    - frontend/test/unit/pages/imports-batchId.spec.ts

key-decisions:
  - "Kept D-02's localStorage requirement exactly as locked in 15-CONTEXT.md rather than silently switching to the useCookie fix already shipped in stores/search.ts — accepted one-frame SSR hydration flash as documented, low-severity tradeoff for this low-traffic report page"
  - "Used a dynamic <component :is=\"linkTarget ? 'NuxtLink' : 'div'\"> per line instead of duplicating markup in two full v-if/v-else card blocks, to keep grid and list view card bodies each in one place"

patterns-established:
  - "movieLinkTarget(line) and statusLabel(line.status) are the shared helpers both grid and list views call — any future view mode reuses the same link/label logic instead of re-deriving it"

requirements-completed: [D-01, D-02, D-03, D-04, D-05, D-06, D-07, D-11]

coverage:
  - id: D1
    description: "BulkImportLineResult DTO carries id/movieId/rawLine; getBatchDetail() resolves movieId server-side for SAVED lines only, at zero extra query cost for every other status"
    requirement: "D-06"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldGetBatchDetail_forOwner_withLinesAndPosterPath"
        status: pass
    human_judgment: false
  - id: D2
    description: "SAVED lines are whole-card/whole-row links to /movies/{movieId}; AMBIGUOUS/NOT_FOUND/PARSE_ERROR never link anywhere"
    requirement: "D-05"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#wraps a SAVED line in a whole-card link to /movies/{movieId}"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#does not render a movie link for an AMBIGUOUS/NOT_FOUND line"
        status: pass
    human_judgment: false
  - id: D3
    description: "PARSE_ERROR lines display the exact raw uploaded line text and a visually distinct (error-family, no red) treatment, independently queryable from generic AMBIGUOUS/NOT_FOUND cards"
    requirement: "D-11"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java#shouldExposeRawLine_forParseErrorLines"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders a PARSE_ERROR line with its distinct testid and raw line text"
        status: pass
    human_judgment: false
  - id: D4
    description: "Grid view is the default on page load; list view shows the same status vocabulary inline per row (thumbnail-left/text-right) instead of an image overlay; the chosen view persists via localStorage across reload"
    requirement: "D-01"
    verification:
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders grid view by default when no localStorage entry is present"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#switches to list view when the ViewToggle list button is clicked"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/imports-batchId.spec.ts#renders list view immediately when localStorage has bulk-import-view-mode=list"
        status: pass
    human_judgment: false
  - id: D5
    description: "Manual spot-check on a real batch with mixed statuses: SAVED card navigates to /movies/{id}, PARSE_ERROR reads as a distinct category, and the view toggle survives a hard reload"
    verification: []
    human_judgment: true
    rationale: "Live browser navigation and visual distinctiveness are judgment calls the plan's own <verification> section marks as 'not automatable' — requires opening the app in a real browser session, which this executor cannot do."

duration: 15min
completed: 2026-08-28
status: complete
---

# Phase 15 Plan 01: Bulk Import Movie Links, PARSE_ERROR Display & View Toggle Summary

**Whole-card movie links and PARSE_ERROR raw-line display on the bulk-import batch-detail page, plus a localStorage-persisted grid/list view toggle**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-08-28T13:35:00Z (approx)
- **Completed:** 2026-08-28T13:49:47+02:00
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- `BulkImportLineResult` DTO now carries `id`, `movieId`, `rawLine` — `getBatchDetail()` resolves `movieId` server-side for SAVED lines only (a single `findByUserIdAndTmdbId` lookup), zero extra query cost for AMBIGUOUS/NOT_FOUND/PARSE_ERROR
- SAVED lines are whole-card (grid) / whole-row (list) `NuxtLink`s to `/movies/{movieId}`; every other status renders as a plain non-link wrapper — never linked anywhere
- PARSE_ERROR lines render with a visually distinct error-family treatment (`border-[#7A3520]`, matching `FormErrorBanner.vue` — no red anywhere per this app's design contract) and show their exact original raw line text via `data-testid="raw-line-text"`, auto-escaped by Vue's `{{ }}` interpolation (no `v-html`)
- Grid/list view toggle added via the existing `ViewToggle.vue` component — grid is the default, the chosen mode persists in `localStorage['bulk-import-view-mode']`, read/written only inside `onMounted()`/a client-only `watch()` per the explicit deviation_note in the plan (this page intentionally keeps `localStorage` instead of the `useCookie` fix already shipped in `stores/search.ts`)
- List view shows thumbnail-left/text-right rows using the identical status vocabulary, SAVED-link, and PARSE_ERROR-distinction logic as grid — factored through shared `movieLinkTarget()`/`statusLabel()` helpers rather than duplicated conditionals

## Task Commits

Each task was committed atomically:

1. **Task 1: Extend BulkImportLineResult DTO + whole-card movie links + PARSE_ERROR raw-line display** - `5e8a34e` (feat)
2. **Task 2: Grid/list view toggle for /imports/{batchId}** - `78a7232` (feat)

**Deferred-items log:** `d7caa50` (docs — logs an out-of-scope pre-existing lint error, not part of a task commit)

**Plan metadata:** (this commit, follows)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java` - added `id`, `movieId`, `rawLine` record fields
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` - `MovieRepository` dependency; `getBatchDetail()` resolves `movieId` for SAVED lines
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` - extended existing detail test with `id`/`movieId` assertions; new `shouldExposeRawLine_forParseErrorLines` test
- `frontend/composables/useBulkImport.ts` - `BulkImportLineResult` interface gains `id`, `movieId`, `rawLine`
- `frontend/pages/imports/[batchId].vue` - whole-card/row `NuxtLink`, PARSE_ERROR distinct treatment + raw-line text, `viewMode` ref + `localStorage`, grid/list template branches
- `frontend/test/unit/pages/imports-batchId.spec.ts` - new tests for movie links, PARSE_ERROR display, and view-toggle behavior (localStorage stub added)

## Decisions Made
- Kept `localStorage` for this page exactly as locked by D-02 in `15-CONTEXT.md`, instead of silently reusing the `useCookie`-based fix `stores/search.ts` already shipped for the identical grid/list persistence problem on `/search`. The plan's own `deviation_note` calls this out explicitly — accepted the documented one-frame SSR hydration flash as a low-severity tradeoff for this low-traffic batch-report page; not something to silently resolve here.
- Used a dynamic `<component :is="linkTarget ? 'NuxtLink' : 'div'">` per line (both grid and list views) instead of two fully duplicated `v-if`/`v-else` card blocks — keeps each card body defined once while still producing a real `<a>` element only when a movie link target exists.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Test environment Docker socket path mismatch**
- **Found during:** Task 1 verification (`./gradlew test`)
- **Issue:** Testcontainers' `UnixSocketClientProviderStrategy` looks for `/var/run/docker.sock` by default; this machine's OrbStack daemon only exposes `~/.orbstack/run/docker.sock`, with no symlink at the default path — `./gradlew test` failed with "Could not find a valid Docker environment" before any of my code changes ran.
- **Fix:** Ran the Gradle test task with `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` set for the invocation only — no repo files changed, no system/global config touched.
- **Files modified:** None (environment variable only, not committed)
- **Verification:** Full `BulkImportControllerTest` suite (15 tests, including the 2 new ones) ran and passed once `DOCKER_HOST` was set correctly.

**2. [Rule 1 - Bug] Fixed a flaky new test — TMDB detail endpoint left unstubbed caused a 5s+ retry delay**
- **Found during:** Task 1, first run of `shouldExposeRawLine_forParseErrorLines`
- **Issue:** The test only stubbed `/3/search/movie`; `resolveAndPersistImdbId()`'s call to `/3/movie/{id}` hit WireMock's default 404, triggering `@Retryable`'s exponential backoff (~3s) before the paced "BadLine" line was processed — pushing it past the test's 5-second poll timeout and causing a flaky `pollForLineByTitle` null.
- **Fix:** Stubbed `/3/movie/\d+` with the existing `fixtures/tmdb/inception-detail.json` fixture so the detail fetch succeeds immediately, matching how other tests in the class avoid the same retry delay.
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java`
- **Verification:** Re-ran the test — passed consistently, no more borderline timing.
- **Committed in:** `5e8a34e` (Task 1 commit)

**3. [Rule 3 - Blocking] Frontend dependencies not installed in this worktree**
- **Found during:** Task 1 verification (`pnpm vitest run`)
- **Issue:** Fresh worktree checkout had no `frontend/node_modules` — `pnpm exec vitest` failed with "Command not found".
- **Fix:** Ran `pnpm install --frozen-lockfile` against the committed `pnpm-lock.yaml` (no lockfile changes).
- **Files modified:** None (installed into gitignored `node_modules/`, not committed)
- **Verification:** `pnpm vitest run` then ran successfully.

**4. [Rule 1 - Bug] localStorage global unavailable in the Vitest/happy-dom test environment**
- **Found during:** Task 2, first run of the new view-toggle tests
- **Issue:** `localStorage` is not a real global in this project's happy-dom-based Nuxt test environment (`node --trace-warnings` confirmed "localStorage is not available"), so every test — including the pre-existing ones — threw `Cannot read properties of undefined (reading 'removeItem')` once the page's `onMounted()` started reading/writing it.
- **Fix:** Added an in-memory `Storage`-shaped stub in the spec file and applied it via `vi.stubGlobal('localStorage', ...)` in `beforeEach` — exactly the pattern the plan's own acceptance criteria suggested (`vi.stubGlobal` or `Object.defineProperty`).
- **Files modified:** `frontend/test/unit/pages/imports-batchId.spec.ts`
- **Verification:** Full spec file (12 tests) and full suite (190 tests) pass.
- **Committed in:** `78a7232` (Task 2 commit)

---

**Total deviations:** 4 auto-fixed (2 environment/blocking, 2 test bugs)
**Impact on plan:** All four were necessary to get the plan's own `<verify>` commands actually running and green in this sandboxed worktree; none touched production code behavior beyond what the plan specified. No scope creep.

## Issues Encountered
- Pre-existing ESLint error in `frontend/pages/movies/[id].vue` (unused `PlusCircleIcon` import, phase 09, commit `3006964`) is unrelated to this plan's file scope — confirmed out-of-scope via `git log`, logged to `deferred-items.md`, not fixed (scope boundary rule).

## Known Stubs
None — no stubs introduced. Both the grid and list views render real data from `getBatchDetail()`; no hardcoded/mock data flows to the UI.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Plan 15-02 (inline resolve for AMBIGUOUS/NOT_FOUND, the interactive half of D-11) can build directly on this plan's `movieLinkTarget()`/`statusLabel()` helpers and the grid/list template structure.
- Plan 15-03 (real CSV parsing) touches `BulkImportController.java` again — sequenced after this plan per the phase's file-overlap wave ordering, no blockers left behind.
- Manual spot-check (live browser: SAVED card navigation, PARSE_ERROR visual distinctiveness, view-toggle persistence across a hard reload) remains genuinely un-automatable per the plan's own `<verification>` section — flagged as `human_judgment: true` (D5) for UAT.

---
*Phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv*
*Completed: 2026-08-28*
