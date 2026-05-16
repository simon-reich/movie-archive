---
phase: 02-settings-api-keys
plan: "01"
subsystem: test-scaffolding
tags: [tdd, scaffolding, settings, backend, frontend]
dependency_graph:
  requires: []
  provides:
    - SettingsIntegrationTest stubs (13 @Disabled) — Wave 2 (02-02) must make them pass
    - SettingsServiceTest stubs (6 @Disabled) — Wave 2 (02-02) must make them pass
    - settingsHandlers MSW registry — Wave 3 (02-03) frontend tests consume these
    - useSettings.spec.ts stubs (7 it.todo) — Wave 3 (02-03) must make them pass
    - settings.spec.ts stubs (8 it.todo) — Wave 3 (02-03) must make them pass
    - useSettings.ts composable stub — minimal implementation for spec module resolution
  affects:
    - frontend/test/mocks/handlers.ts (added settingsHandlers spread)
tech_stack:
  added: []
  patterns:
    - AbstractWireMockTest extension for SettingsIntegrationTest (WireMock + Postgres)
    - GreenMailExtension for email assertions
    - @DynamicPropertySource to inject WireMock base URL into tmdb.base-url / omdb.base-url
    - MSW handler pattern mirroring auth.ts (settingsHandlers array)
    - it.todo stubs for Wave 3 frontend tests (no imports of missing modules at module-load time)
key_files:
  created:
    - backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java
    - backend/src/test/java/de/moviearchive/settings/SettingsServiceTest.java
    - frontend/test/mocks/handlers/settings.ts
    - frontend/test/unit/composables/useSettings.spec.ts
    - frontend/test/unit/pages/settings.spec.ts
    - frontend/composables/useSettings.ts
  modified:
    - frontend/test/mocks/handlers.ts
decisions:
  - "useSettings.ts minimal stub created alongside test scaffolding — top-level await import() in spec resolves at module-load time; missing composable caused ERR_MODULE_NOT_FOUND before any test ran (Rule 3 deviation)"
  - "settings.spec.ts uses no top-level composable import — avoids same resolution problem since no production page exists yet"
metrics:
  duration: "~10 min"
  completed: "2026-05-16T11:32:19Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 6
  files_modified: 1
---

# Phase 02 Plan 01: Test Scaffolding for Settings & API Keys — Summary

Test-first scaffolding for Phase 2 settings feature. All behavior contracts are locked as @Disabled (backend) and it.todo (frontend) stubs before any implementation code is written. Wave 2 and Wave 3 executors must make these stubs pass without re-interpreting requirements.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Backend test scaffolding | 8fd3bc7 | SettingsIntegrationTest.java, SettingsServiceTest.java |
| 2 | Frontend test scaffolding | 46f58e5 | handlers/settings.ts, handlers.ts, useSettings.spec.ts, settings.spec.ts, useSettings.ts |

## What Was Built

**Backend (Task 1):**
- `SettingsIntegrationTest.java` — 13 `@Disabled("stub — implement in plan 02-02")` test stubs covering SET-01 (TMDB key save/get/update/reject), SET-02 (OMDB key), SET-03 (password change + session revoke), SET-04 (email change confirmation + conflict + old-address notify). Extends `AbstractWireMockTest` (WireMock + Postgres via Testcontainers). Includes `@DynamicPropertySource` to override `tmdb.base-url` and `omdb.base-url` to `wireMock.baseUrl()`.
- `SettingsServiceTest.java` — 6 `@Disabled` stubs for `EncryptionService` round-trip (encrypt-then-decrypt, fresh-IV per call) and both key validators (TMDB 401→false / 200→true, OMDB Response:False→false / Response:True→true). Plain JUnit 5 + Mockito, no Spring context.
- Both files compile cleanly: `./gradlew compileTestJava` exits 0.

**Frontend (Task 2):**
- `frontend/test/mocks/handlers/settings.ts` — 4 MSW handlers: `GET /api/settings/api-keys` (returns plaintext keys per D-03), `PUT /api/settings/api-keys/:provider` (422 on sentinel invalid-key / invalid-omdb-key values), `POST /api/settings/password` (400 on wrong-password sentinel), `POST /api/settings/email` (always 200, enumeration protection).
- `frontend/test/mocks/handlers.ts` — `settingsHandlers` imported and spread into global handler registry.
- `useSettings.spec.ts` — 7 `it.todo` stubs for composable contract (saveApiKey tmdb, saveApiKey omdb, 422 throws, loadApiKeys, changePassword, 400 throws, changeEmail).
- `settings.spec.ts` — 8 `it.todo` stubs for page behavior (Account section, API Keys section, inline saved state D-06, 422 error D-10, inbox message D-07, password error D-10, CSV placeholder D-08, nav link D-02).
- `pnpm test` exits 0: 56 existing tests pass, 15 new todos skipped.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created useSettings.ts composable stub alongside test scaffolding**
- **Found during:** Task 2
- **Issue:** `useSettings.spec.ts` uses a top-level `await import('@/composables/useSettings')` which runs at module-load time (Vitest collects tests before running them). With no `useSettings.ts` present, Vitest threw `ERR_MODULE_NOT_FOUND` and the entire test file failed to load — not just the test cases.
- **Fix:** Created `frontend/composables/useSettings.ts` as a minimal but correct stub with the four functions (`saveApiKey`, `loadApiKeys`, `changePassword`, `changeEmail`) matching the API contract from RESEARCH.md Pattern 6. This is the same stub that Wave 3 will expand into the full implementation.
- **Files modified:** `frontend/composables/useSettings.ts` (created)
- **Commit:** 46f58e5

## Known Stubs

| File | Symbol | Reason |
|------|--------|--------|
| `frontend/composables/useSettings.ts` | All 4 functions | Minimal stub — full implementation in plan 02-03. Functions make real $fetch calls but there is no backend yet. |

The composable stub is intentional for this plan. Plan 02-03 will wire it to full production behavior.

## Threat Flags

None. No new network endpoints, auth paths, or schema changes introduced. Test-only files per threat model disposition `accept`.

## Self-Check: PASSED

**Files exist:**
- FOUND: backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java
- FOUND: backend/src/test/java/de/moviearchive/settings/SettingsServiceTest.java
- FOUND: frontend/test/mocks/handlers/settings.ts
- FOUND: frontend/test/unit/composables/useSettings.spec.ts
- FOUND: frontend/test/unit/pages/settings.spec.ts
- FOUND: frontend/composables/useSettings.ts

**Commits exist:**
- FOUND: 8fd3bc7 — test(02-01): add backend test scaffolding for settings phase
- FOUND: 46f58e5 — test(02-01): add frontend test scaffolding for settings phase

**Stub counts verified:**
- SettingsIntegrationTest.java: 13 @Disabled
- SettingsServiceTest.java: 6 @Disabled
- useSettings.spec.ts: 7 it.todo
- settings.spec.ts: 8 it.todo
- handlers/settings.ts: 4 http. handlers
