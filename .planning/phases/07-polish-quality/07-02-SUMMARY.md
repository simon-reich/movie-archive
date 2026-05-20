---
phase: 07-polish-quality
plan: 02
subsystem: e2e-testing
tags: [e2e, playwright, spring-boot, test-profile, docker-compose]
dependency_graph:
  requires: []
  provides: [test-seed-endpoint, happy-path-e2e-spec]
  affects: [backend-security, docker-compose, frontend-e2e]
tech_stack:
  added: []
  patterns:
    - Spring @Profile("test") — bean-level gating for test-only endpoints
    - Playwright serial mode — prevent beforeAll race across parallel test projects
    - Docker Compose ENV passthrough — SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}
key_files:
  created:
    - backend/src/main/java/de/moviearchive/controller/TestSetupController.java
    - backend/src/main/resources/application-test.properties
    - frontend/test/e2e/happy-path.spec.ts
  modified:
    - backend/src/main/java/de/moviearchive/settings/UserApiKeyRepository.java
    - backend/src/main/java/de/moviearchive/config/SecurityConfig.java
    - docker-compose.yml
decisions:
  - "TestSetupController gated by @Profile(\"test\") — bean does not exist in production; permitAll(\"/test/**\") in SecurityConfig is safe because the controller has no target to reach when not in test profile"
  - "Serial mode on Playwright describe block — two projects (Desktop/Mobile Chrome) share same test user, serial avoids beforeAll race while idempotent setup handles re-runs"
  - "deleteByUserId added to UserApiKeyRepository — Spring Data derives the DELETE query; no custom @Query needed"
metrics:
  duration: ~8 min
  completed: 2026-05-20
  tasks_completed: 2
  files_changed: 6
---

# Phase 7 Plan 02: E2E Test Infrastructure and Happy-Path Spec Summary

**One-liner:** Spring Boot `@Profile("test")` seed endpoint + Playwright serial happy-path spec covering login → add film → search → view detail on Desktop Chrome and Mobile Chrome.

## What Was Built

### Task 1: Spring Boot test seed endpoint + SecurityConfig + docker-compose passthrough

- **TestSetupController** (`backend/src/main/java/de/moviearchive/controller/TestSetupController.java`) — `@Profile("test")`-gated `POST /test/setup` endpoint. Idempotent: deletes existing test user and API keys, creates a new ACTIVE user, seeds encrypted TMDB key via `EncryptionService.encrypt()`. Returns `{"email": "...", "password": "..."}` for test consumption.
- **UserApiKeyRepository** — Added `void deleteByUserId(UUID userId)` method for clean test teardown.
- **application-test.properties** (`backend/src/main/resources/application-test.properties`) — Test profile config reading `TEST_USER_EMAIL`, `TEST_USER_PASSWORD`, `TEST_TMDB_KEY` from environment.
- **SecurityConfig** — Added `/test/**` to `permitAll()` list. Safe: the controller bean only exists in the test profile.
- **docker-compose.yml** — Added `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}` to backend service environment block so CI can activate the test profile by setting the env var.

### Task 2: Playwright happy-path E2E spec

- **happy-path.spec.ts** (`frontend/test/e2e/happy-path.spec.ts`) — Full happy-path covering D-13: login → add film → search → view detail.
  - `test.describe.configure({ mode: 'serial' })` prevents beforeAll races across Desktop Chrome and Mobile Chrome projects.
  - `beforeAll` calls `POST /test/setup` to seed test user (D-12, D-14).
  - 30s timeout on search results assertion to account for async enrichment pipeline (TMDB → OMDB → Wikipedia → Postgres → OpenSearch).
  - `data-testid` selectors: `poster-card`, `save-status`, `movie-card`, `movie-title`.
  - Runs on both Playwright projects defined in `playwright.config.ts` (Desktop Chrome + Mobile Chrome Pixel 5).

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | ac899bf | feat(07-02): test seed endpoint, security config, docker-compose passthrough |
| 2 | 7b993e2 | feat(07-02): Playwright happy-path E2E spec covering D-13 |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — the seed endpoint and E2E spec are complete implementations.

## Threat Flags

None — threat model in plan covers all introduced surfaces (T-7-02-01 through T-7-02-04). Primary mitigation for elevation of privilege is `@Profile("test")` — controller bean does not exist in production.

## Self-Check: PASSED

- `backend/src/main/java/de/moviearchive/controller/TestSetupController.java` — exists
- `backend/src/main/resources/application-test.properties` — exists
- `frontend/test/e2e/happy-path.spec.ts` — exists
- `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` — contains `/test/**`
- `docker-compose.yml` — contains `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}`
- `./gradlew compileJava` — BUILD SUCCESSFUL
- Commits ac899bf and 7b993e2 verified in git log
