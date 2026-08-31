---
created: 2026-08-31T00:00:00.000Z
title: Backend full-suite CI still flaky after hang/FK/pool fixes — Hikari pool sizing + Bucket4j rate-limiter state
area: backend/testing
severity: major
files:
  - backend/src/test/resources/application-test.properties
  - backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java
  - backend/src/test/java/de/moviearchive/enrichment/EnrichmentIntegrationTest.java
  - backend/src/main/java/de/moviearchive/ratelimit/RateLimitService.java
---

## Problem

Between 2026-08-29 and 2026-08-31, three consecutive debug sessions closed real, confirmed bugs
in the full backend test suite (`./gradlew test`) run against CI:

1. `.planning/debug/resolved/backend-ci-tests-hang.md` — a stale `WikiReloadControllerTest`
   assertion hung the entire single-threaded Gradle test run indefinitely (fixed).
2. `.planning/debug/resolved/e2e-login-redirect-flake.md` — unrelated E2E docker-compose env-var
   bug (fixed; not a backend-suite issue, listed here only for the shared timeline).
3. `.planning/debug/resolved/fullsuite-fk-isolation-flakiness.md` — missing `ON DELETE CASCADE`
   on `movies`/`bulk_import_line`/`bulk_import_batch` FKs, plus unbounded per-test-class Hikari
   pools exceeding Postgres `max_connections` (fixed: V11 migration + `maximum-pool-size=5`).

After all three fixes, `origin/main` (head `16ab790`) still shows:

- **Backend CI (run 33382587179): FAILED.** `EnrichmentIntegrationTest` — 4 failures
  (`shouldTransitionToSuccess_afterEnrichment`, `shouldPersistTmdbData_afterEnrichment`,
  `shouldFetchWikipedia_whenSingleArgEnrichUsed`, `shouldSkipWikipedia_...`), a mix of
  `AssertionFailedError` and a WireMock `VerificationException`. These did NOT reproduce in a
  local `./gradlew test` run on a 10-core dev machine (232 completed, only the pre-existing
  `WikiReloadServiceIntegrationTest` cooldown-timing flake + 2 `AuthIntegrationTest` 429s — see
  below) — working hypothesis: the new `spring.datasource.hikari.maximum-pool-size=5` cap
  (fullsuite-fk-isolation-flakiness fix, applied to fix connection-count exhaustion) may be too
  tight under GitHub Actions' 2-core standard runner combined with `EnrichmentService`'s
  concurrent `@Async` TMDB→OMDB→Wikipedia calls, causing pool-wait timeouts or WireMock
  request-ordering races that don't surface with more CPU headroom locally.

- **Local full-suite run (10-core machine, same commit): 232/235 completed, 3 failed** —
  `WikiReloadServiceIntegrationTest.shouldRespectCooldownWindow_excludingRecentAttempts`
  (pre-existing, documented, timing-based) plus two NEW failures:
  `AuthIntegrationTest.shouldRotateRefreshToken_revokesOldAndIssuesNew` and
  `AuthIntegrationTest.shouldLogout_setsTokenRevoked`, both `Status expected:<200> but
  was:<429>` — Bucket4j rate-limiter state (see `RateLimitService`) persisting across the
  shared JVM test run with no per-test/per-class reset. `RateLimitService` already exposes a
  `resetAll()` method (confirmed during the fullsuite-fk-isolation-flakiness investigation) —
  `AuthIntegrationTest` simply never calls it.

Both failure modes are the same underlying class of bug the last three debug sessions kept
finding: **shared, JVM-lifetime state (DB rows, connection pools, rate-limiter buckets) with no
cross-class or cross-test reset**, surfacing differently depending on machine/runner resources
and test execution order — a whack-a-mole pattern. Not blocking day-to-day work (E2E Tests and
Frontend CI are both green as of 2026-08-31; only the full backend suite's CI job is affected),
but the backend CI job itself is not reliably green yet.

## Solution (not yet designed — starting points only)

- `AuthIntegrationTest`: call `rateLimitService.resetAll()` in `@BeforeEach`/`@AfterEach`,
  mirroring the pattern already used elsewhere for other shared state.
- `EnrichmentIntegrationTest` CI-only failures: reproduce on a resource-constrained environment
  (or via `--max-workers=1`/explicit CPU/memory limits locally) before concluding it's really
  the Hikari pool cap; consider whether `maximum-pool-size=5` needs to be higher, or whether
  `EnrichmentIntegrationTest`'s async assertions need more generous polling/await timeouts under
  slower CI hardware instead.
- Longer-term: consider whether the shared JVM-lifetime Testcontainers Postgres pattern itself
  (one static container for the whole suite, no `@DirtiesContext`/`@AfterAll` cleanup convention)
  is worth revisiting suite-wide, rather than continuing to patch each newly-discovered leak
  individually.

Start a new `/gsd-debug` session when picked back up — do not fold into the three now-closed
sessions above, per each of those sessions' own scope notes.
