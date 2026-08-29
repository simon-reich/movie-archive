---
phase: 09-manual-wiki-retry
plan: 02
subsystem: api
tags: [spring-boot, spring-security, jwt, nuxt, vue, msw, vitest, junit5]

# Dependency graph
requires:
  - phase: 08-wiki-enrichment-tracking-batch-reload
    provides: "POST /admin/wiki-reload/{userId} batch-reload endpoint (503-on-conflict, fire-and-forget @Async)"
provides:
  - "GET /users/me — minimal authenticated-identity endpoint, first controller in de.moviearchive.user"
  - "useSettings().getCurrentUserId() / triggerWikiReload() composable functions"
  - "Settings page 'Wikipedia Data' section with a working batch-reload trigger button"
affects: [10-bulk-import-engine, 11-bulk-import-feedback-ui]

actuals:
  tokens: 4096
  tasks: 2
  commits: 4

tech-stack:
  added: []
  patterns:
    - "JWT -> email -> UserRepository.findByEmail -> id resolution, replicated verbatim from MovieDetailController.resolveUserId"
    - "Fetch-once-and-cache composable pattern (currentUserId ref, mirrors onMounted fetch-once idiom already used for API keys)"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/user/UserController.java
    - backend/src/test/java/de/moviearchive/user/UserControllerTest.java
  modified:
    - frontend/composables/useSettings.ts
    - frontend/pages/settings.vue
    - frontend/test/mocks/handlers/settings.ts
    - frontend/test/unit/composables/useSettings.spec.ts
    - frontend/test/unit/pages/settings.spec.ts

key-decisions:
  - "UserController returns Map.of(\"id\", id) only — never the User entity, which has no @JsonIgnore guard on passwordHash (T-09-04)"
  - "No SecurityConfig change — /users/me falls under the existing anyRequest().authenticated() catch-all"
  - "triggerWikiReload() catches only 503 (already-running); every other error is rethrown for the page's generic-failure backstop"

patterns-established:
  - "Fetch-once-cache-in-ref for per-session identity data that never changes mid-session"

requirements-completed: [ENRICH-04, ENRICH-05]

coverage:
  - id: D1
    description: "GET /users/me returns only { id } for the authenticated caller, never the raw User entity or its password hash"
    requirement: "ENRICH-04"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/user/UserControllerTest.java#me_returnsAuthenticatedUsersOwnId"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/user/UserControllerTest.java#me_returnsDifferentIdForDifferentUser"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/user/UserControllerTest.java#me_requiresAuthentication"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/user/UserControllerTest.java#me_responseContainsOnlyIdField"
        status: pass
    human_judgment: false
  - id: D2
    description: "Settings page fetches the user id once (cached) and triggers the existing batch-reload endpoint, showing 202/503/generic-failure outcomes with correct button loading/disabled state"
    requirement: "ENRICH-05"
    verification:
      - kind: unit
        ref: "frontend/test/unit/composables/useSettings.spec.ts#getCurrentUserId fetches GET /api/users/me once and caches the result"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/composables/useSettings.spec.ts#triggerWikiReload calls POST /api/admin/wiki-reload/:userId with the resolved id and returns 'started' on success"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/composables/useSettings.spec.ts#triggerWikiReload returns 'already-running' when the POST rejects with a 503 response"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/composables/useSettings.spec.ts#triggerWikiReload rethrows non-503 errors"
        status: pass
      - kind: unit
        ref: "frontend/test/unit/pages/settings.spec.ts#useSettings.triggerWikiReload rejects with a non-503 error — page catch-all is reachable"
        status: pass
    human_judgment: true
    rationale: "Visual button states ('Starting...', disabled, inline message copy, section placement) are asserted only at the composable/module level per this file's existing style (no full DOM mounting) — a human should confirm the rendered Settings page looks and behaves correctly in the browser."

duration: 35min
completed: 2026-08-23
status: complete
---

# Phase 9 Plan 2: Manual Wiki Retry — Settings Batch-Reload Trigger Summary

**Added `GET /users/me` (first controller in `de.moviearchive.user`) and wired a "Reload missing Wikipedia data" button on Settings that triggers Phase 8's existing batch-reload endpoint, with full 202/503/generic-failure feedback.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-08-23T14:40:00Z (approx.)
- **Completed:** 2026-08-23T14:49:10Z
- **Tasks:** 2/2 completed
- **Files modified:** 7 (2 created, 5 modified)

## Accomplishments

- New `UserController` with `GET /users/me`, returning only `{ id }` derived from the JWT — the entity itself is never serialized, closing the T-09-04 information-disclosure threat with an explicit regression-guard test.
- `useSettings()` composable gained `getCurrentUserId()` (fetch-once, cached) and `triggerWikiReload()` (202 → `'started'`, 503 → `'already-running'`, anything else rethrown).
- Settings page has a new "Wikipedia Data" section: a `ButtonPrimary` that shows "Starting..." + disabled while in flight, and an inline message distinguishing all three outcomes (started / already-running / generic failure) — no code path leaves the button silently stuck.
- Backend and frontend test suites (141 frontend tests, full `./gradlew test` backend suite) pass with no regressions.

## Task Commits

Each task was committed atomically (TDD RED → GREEN per task):

1. **Task 1 (tracer): Wire GET /users/me end-to-end — new UserController + Settings button (202 path)**
   - `066dca4` (test) — failing tests for GET /users/me and settings reload trigger
   - `099b9f6` (feat) — UserController, useSettings additions, settings.vue "Wikipedia Data" section
2. **Task 2: 503 conflict message + generic-failure backstop + response-shape hardening**
   - `d6407b3` (test) — password-hash-leak guard + 503/rethrow coverage
   - `e7cc73e` (feat) — generic-failure catch-all in `onTriggerWikiReload`

_Note: TDD tasks had RED → GREEN commit pairs; Task 2's RED-phase tests passed against the existing Task 1 implementation for the backend/composable (no behavior gap there) — only the `settings.vue` catch-all required a code change, confirming the plan's own annotation that "no change needed if Task 1 was implemented as specified."_

## Files Created/Modified

- `backend/src/main/java/de/moviearchive/user/UserController.java` - New: `GET /users/me`, returns `Map.of("id", id)` only
- `backend/src/test/java/de/moviearchive/user/UserControllerTest.java` - New: own-id, per-user distinctness, unauthenticated-401/403, no-password-hash-leak
- `frontend/composables/useSettings.ts` - Added `getCurrentUserId()` (cached) and `triggerWikiReload()` (503/rethrow mapping)
- `frontend/pages/settings.vue` - New "Wikipedia Data" section + `onTriggerWikiReload()` handler with full try/catch/finally outcome mapping
- `frontend/test/mocks/handlers/settings.ts` - MSW handlers for `GET /api/users/me` and `POST /api/admin/wiki-reload/:userId`
- `frontend/test/unit/composables/useSettings.spec.ts` - Caching, 202, 503, and rethrow coverage
- `frontend/test/unit/pages/settings.spec.ts` - Catch-all reachability check (matches file's existing no-DOM-mount style)

## Decisions Made

- Followed the plan's exact `resolveUserId` replication from `MovieDetailController` — no new abstraction introduced for a single-method controller.
- Kept the 503-detection check as `err.response?.status === 503` (ofetch/`FetchError` shape) per the plan's explicit action text, matching the mocking convention used in the new tests.
- No `SecurityConfig` change — verified `/users/**` is absent from the `permitAll()` list, so it already falls under `anyRequest().authenticated()`.

## Deviations from Plan

None — plan executed exactly as written. The one adjustment (moving `me_responseContainsOnlyIdField` fully into Task 2's test commit rather than Task 1, as the plan's task-by-task `<action>` text specifies) was already the plan's own intended split, not a deviation.

## Issues Encountered

- **Environment:** This worktree's `frontend/node_modules` was absent (gitignored, not present after worktree creation) — resolved with `pnpm install --frozen-lockfile` before running any frontend tests/lint/typecheck.
- **Environment:** Testcontainers' default Unix-socket strategy looked for `/var/run/docker.sock`, which doesn't exist under this machine's OrbStack setup (socket lives at `~/.orbstack/run/docker.sock`). Backend Gradle test runs required `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` in the environment to let Testcontainers connect. This is a local-machine Docker context quirk, not a code or plan issue — no source files were touched to work around it.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

`GET /users/me` is now available for any future phase needing the current user's id client-side (e.g. Phase 10/11 bulk-import UI, if it ever needs to reference the user id directly rather than relying on JWT-scoped backend endpoints). No blockers for Phase 10 (Bulk Import Engine).

---
*Phase: 09-manual-wiki-retry*
*Completed: 2026-08-23*

## Self-Check: PASSED

All created files verified present on disk; all 4 task commits (066dca4, 099b9f6, d6407b3, e7cc73e) verified present in git log.
