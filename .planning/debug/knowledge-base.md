---
audit_acknowledged:
  milestone: v1.1
  at: 2026-08-29
  status: unknown
---

# GSD Debug Knowledge Base

Resolved debug sessions. Used by `gsd-debugger` to surface known-pattern hypotheses at the start of new investigations.

---

## phase1-uat-failures — CSS variables hex vs HSL, missing auth guard, missing logout UI

- **Date:** 2026-05-16
- **Error patterns:** palette, colors, background, terracotta, hsl, css variables, hex, redirect, authenticated, login, logout, sign out, nav, navbar, single-use, consumed
- **Root cause:** (1) CSS custom properties used raw hex values but Tailwind wraps them in hsl() — invalid CSS, colors invisible. (2) Global middleware allowed all public routes unconditionally — no reverse guard for authenticated users. (3) Backend token single-use was correctly implemented — UAT false positive. (4) No navbar component in default layout — logout composable existed but had no UI entry point.
- **Fix:** Convert CSS vars to HSL space-separated components; add isAuthenticated redirect in middleware for public routes; create AppNav.vue with Sign out button and mount it conditionally in default.vue.
- **Files changed:** frontend/assets/css/main.css, frontend/middleware/auth.global.ts, frontend/layouts/default.vue, frontend/components/AppNav.vue, frontend/test/unit/middleware/auth.spec.ts

---

## auth-routing-state-bug — Access token lost on reload, stale session_email, same-email validation

- **Date:** 2026-05-16
- **Error patterns:** F5 reload, direct URL, Authorization header, access token, Pinia store, session_email cookie, useCookie, same email validation, stale cookie, email change, confirmEmail, redirect JSON, raw JSON browser
- **Root cause:** (1) Access token stored only in Pinia (in-memory) — lost on F5 or direct URL. SSR renders before client plugin restores token → race condition → API calls fire without Authorization header → silent failure. (2) No frontend guard comparing entered email to current email → same-email change sent a confirmation email; backend `confirmEmail` returned JSON 409 directly in browser instead of redirecting. (3) After successful email confirmation, `session_email` cookie was never updated in redirect response → `useCookie('session_email')` in auth store stayed stale indefinitely.
- **Fix:** (1) Backend sets non-httpOnly `access_token` cookie on login/refresh; auth store reads via `useCookie()` — works synchronously on SSR and client. (2) Frontend compares entered email to `authStore.userEmail` before submit; backend `confirmEmail` redirects `EmailAlreadyExistsException` to `/login?emailError=email-unavailable`. (3) Backend sets updated `session_email` cookie in confirmation redirect response.
- **Files changed:** AuthService.java, AuthController.java, SettingsService.java, SettingsController.java, stores/auth.ts, middleware/auth.global.ts, pages/settings.vue, pages/login.vue, plugins/auth.client.ts

---

## api-key-delete-always-fails — DELETE endpoint returns 204, ofetch parses empty body as JSON and throws

- **Date:** 2026-05-17
- **Error patterns:** delete, api key, 204, no content, ofetch, fetch error, syntax error, could not delete key, catch block, settings, tmdb, omdb
- **Root cause:** Backend DELETE /settings/api-keys/{provider} returned 204 No Content. Nuxt's $fetch (ofetch) attempted to parse the empty response body as JSON when Spring negotiated Content-Type: application/json, throwing a SyntaxError that was caught by the UI error handler and displayed as "Could not delete key. Please try again."
- **Fix:** Change backend to return ResponseEntity.ok().build() (200 OK, consistent with changePassword and other mutation endpoints). Added MSW DELETE handler and deleteApiKey unit test.
- **Files changed:** backend/src/main/java/de/moviearchive/settings/SettingsController.java, frontend/test/mocks/handlers/settings.ts, frontend/test/unit/composables/useSettings.spec.ts

---

## jacoco-coverage-below-threshold — JaCoCo line coverage 72%, threshold 75%

- **Date:** 2026-05-18
- **Error patterns:** jacocoTestCoverageVerification, lines covered ratio, Rule violated, 0.72, 0.75, threshold, coverage, DashboardService, SearchService, facets, autocomplete
- **Root cause:** DashboardService and DashboardController had zero test coverage (95+12 uncovered lines). SearchService facets/autocomplete paths (getFacets, autocomplete) were never exercised by SearchControllerTest. Pure record DTOs (AutocompleteResponse, DashboardMovieItem, DashboardResponse, FacetsResponse, HistogramBucket) were counted by JaCoCo despite having no branching logic. Secondary: DashboardService threw 500 on index_not_found_exception when archive was empty (valid state).
- **Fix:** (1) Added JaCoCo exclusion list in build.gradle.kts for pure DTO records, simple exception subclasses, and application entry point. (2) Created DashboardControllerTest with 6 integration tests (real OpenSearch + Postgres). (3) Added 8 tests to SearchControllerTest covering facets, autocomplete, page overflow, year sort. (4) Fixed DashboardService to catch index_not_found_exception and return empty dashboard.
- **Files changed:** backend/build.gradle.kts, backend/src/main/java/de/moviearchive/search/DashboardService.java, backend/src/test/java/de/moviearchive/search/DashboardControllerTest.java, backend/src/test/java/de/moviearchive/search/SearchControllerTest.java

---

## sse-auth-denied-on-complete — AuthorizationDeniedException on SseEmitter async redispatch after complete()

- **Date:** 2026-08-29
- **Error patterns:** AuthorizationDeniedException, SseEmitter, emitter.complete, async dispatch, ASYNC redispatch, response already committed, Unable to handle the Spring Security Exception, AuthorizationFilter, SSE, progress endpoint, wiki reload, bulk import
- **Root cause(s):** config: Spring Boot's default `spring.security.filter.dispatcher-types` (REQUEST, ASYNC, ERROR) registers Spring Security's FilterChainProxy — including AuthorizationFilter (not a OncePerRequestFilter, does not skip ASYNC) — to re-run on the ASYNC servlet redispatch that Tomcat performs after `SseEmitter.complete()` (called from a background executor thread); code: `JwtAuthFilter` (a `OncePerRequestFilter`, skips ASYNC dispatch by default) never persists its `Authentication` to a `SecurityContextRepository`, and `SecurityConfig`'s STATELESS session policy defaults to `NullSecurityContextRepository` — so the SecurityContext is empty on the redispatch, and AuthorizationFilter denies the already-authenticated, already-completed request.
- **Fix:** Set `spring.security.filter.dispatcher-types=request,error` in application.properties, excluding ASYNC from Spring Security's registered dispatcher types. The app's only async-controller-return-type usage is SseEmitter (WikiReloadController#progress, BulkImportController#progress); their ownership/IDOR checks already run synchronously on the initial REQUEST dispatch, unaffected by the change.
- **Files changed:** backend/src/main/resources/application.properties, backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java, backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
- **Why not caught:** No gate existed for this class — the existing 403/IDOR tests on both SSE progress endpoints only exercised the initial REQUEST dispatch (via a controller-local `@ExceptionHandler`, which cannot intercept filter-chain exceptions on a later container-level redispatch); nothing in the suite simulated the ASYNC servlet redispatch that Tomcat performs after `SseEmitter.complete()`.
- **Recurrence guard:** Regression tests `WikiReloadControllerTest#shouldNotDenyAuthorization_onAsyncRedispatch_afterEmitterComplete` and `BulkImportControllerTest#shouldNotDenyAuthorization_onAsyncRedispatch_afterEmitterComplete` use MockMvc's `asyncDispatch(mvcResult)` to simulate the real container ASYNC redispatch through the full Spring Security filter chain and assert it completes without `AuthorizationDeniedException`. The `application.properties` change is also documented inline with a comment explaining why ASYNC is excluded, so a future change re-adding it is self-documenting.

---

## wiki-reload-progress-blind-window — SSE emitter closed+evicted on every run completion, permanently killing the page's one subscription

- **Date:** 2026-08-29
- **Error patterns:** progress panel, Stop button, no progress bar, blind window, SSE, fetchEventSource, emitter.complete, wiki reload, silent no-op, broadcast, page refresh, stuck at prior state, in the dark
- **Root cause(s):** WikiReloadProgressService.complete(userId) called emitter.complete() and unconditionally removed the userId entry from the `emitters` registry (and `lastKnown`) at the end of every batchReload run (success or Stop), permanently closing the page's single fetchEventSource() SSE subscription — `@microsoft/fetch-event-source` does not auto-reconnect after a clean server-initiated stream close (only after a thrown/network error). The registry is scoped per-userId (page-lifetime, meant to cover all future runs), but its cleanup logic was copied wholesale from `BulkImportProgressService`'s genuinely per-batch-id-scoped model — an architectural scope mismatch, not merely a code typo.
- **Fix:** `complete(userId)` now broadcasts the terminal `{complete:true}` state without calling `emitter.complete()` or evicting the `emitters`/`lastKnown` registry entries; the `emitters` registry is now only cleaned up by an actual client disconnect (`register()`'s `onCompletion`/`onTimeout` wiring, or a failed send in `sendEvent()`/`removeEmitter()`). `stopFlags`/`durationWindowsMs` (genuinely per-run state) are still cleared each run. Javadoc updated to document the corrected lifecycle and the incident.
- **Files changed:** backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java, backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
- **Why not caught:** Existing unit tests actively pinned the buggy behavior as the intended design — `publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andCompletesEmitter` asserted `emitter.complete()` was called, and `register_afterComplete_getsSyntheticCompleteFallback_notReplayOfRealCompletion` asserted `lastKnown` was evicted by `complete()` (its own docstring: "proving eviction rather than a stale replay"). No test exercised a SECOND run over the SAME persistent registration — the exact multi-run-per-page-session pattern the frontend actually uses — so the suite validated only a single-run lifecycle cloned wholesale from BulkImportProgressService's per-batch-id test model.
- **Recurrence guard:** New driving test `secondRun_afterComplete_broadcastsToStillRegisteredEmitter_noReReg` in `WikiReloadProgressServiceTest.java` performs one `register()` call followed by two full start/publish/complete run cycles, asserting all 7 events reach the one persistent emitter and it is never closed — directly covering this class of regression. The two previously-buggy-pinning tests were rewritten to assert the corrected behavior instead of the bug.

---

## e2e-login-redirect-flake — Docker Compose empty-string env passthrough shadows Spring property default, seeding E2E test user with email=''

- **Date:** 2026-08-29
- **Error patterns:** toHaveURL timeout, Invalid email or password, 401, BadCredentialsException, login redirect, E2E happy-path, Playwright, docker compose env, TEST_USER_EMAIL, TEST_USER_PASSWORD, empty string default, Spring placeholder default
- **Root cause(s):** config: `docker-compose.yml` passed `TEST_USER_EMAIL`/`TEST_USER_PASSWORD` to the backend container using map-style `KEY: ${KEY:-}` substitution, which forces the container env var present-but-empty whenever the invoking shell (the GH Actions "Start full Docker Compose stack" step) doesn't set it — which it never did. Spring's `${KEY:default}` placeholder only applies the default when the property is entirely absent, not when it resolves to `''`, so `application-test.properties`' `test.user.email=${TEST_USER_EMAIL:e2e@moviearchive.test}` resolved to `''`, and `TestSetupController` seeded the E2E user with `email=''`/`password=bcrypt('')` while the Playwright spec correctly submitted the real hardcoded credentials — a lookup miss, 401 on every attempt, deterministically.
- **Fix:** Changed `docker-compose.yml`'s `TEST_USER_EMAIL`/`TEST_USER_PASSWORD` entries from map-style `KEY: ${KEY:-}` (always-present, empty default) to list-style passthrough `- KEY` (Compose omits the key entirely — not empty — when the host shell doesn't set it), letting `application-test.properties`' own non-empty defaults be the single source of truth.
- **Files changed:** docker-compose.yml
- **Why not caught:** No gate existed for this class — the empty-default line was added and committed during local v1.1 development but never exercised against a real GitHub Actions run (SPRING_PROFILES_ACTIVE=test dockerized stack) until the first real push to `origin/main`; no CI job had ever run this workflow end-to-end before.
- **Recurrence guard:** Live CI confirmation (run 33270852363) that login now succeeds and the test progresses past the redirect into the downstream "add film" step. No local test harness exists for `docker-compose.yml`'s variable-resolution semantics (not source code); the KB entry itself is the recurrence guard for this specific `${VAR:-}` vs `- VAR` Compose passthrough pattern — check env-var passthrough style in `docker-compose.yml` whenever a Spring-side `${ENV:default}` placeholder is unexpectedly empty in a containerized environment.

**Related, separately-tracked issues surfaced by this session's fix (out of scope for this KB entry):**
- Missing `TEST_TMDB_KEY` GitHub repo secret blocks the E2E "add film" step (no fallback default in `application-test.properties` for that key, unlike the user email/password) — tracked at `.planning/todos/pending/2026-08-29-configure-test-tmdb-key-github-secret.md`, owner-only fix.
- Cosmetic "Stop Docker Compose" cleanup step missing env vars (`DB_PASSWORD is missing a value` warning on teardown) — fixed independently in `.github/workflows/e2e-ci.yml` (commit e9cac14).

---

## backend-ci-tests-hang — Stale MockMvc regression test hangs entire Gradle test run indefinitely after SSE emitter-completion semantics changed

- **Date:** 2026-08-29
- **Error patterns:** Run tests step stuck, CI hang, no timeout, asyncDispatch, awaitAsyncDispatch, CountDownLatch, DefaultMvcResult, SSE, emitter.complete, MockMvc async redispatch, gradlew test never finishes, jstack TIMED_WAITING
- **Root cause(s):** code: `WikiReloadControllerTest.shouldNotDenyAuthorization_onAsyncRedispatch_afterEmitterComplete()` called `wikiReloadProgressService.complete(userId)` expecting it to trigger a real MockMvc async completion, but the 2026-08-28 `wiki-reload-progress-blind-window` fix (see that KB entry above) intentionally removed `complete()`'s `emitter.complete()` call so the SSE connection survives across multiple future runs — leaving `mockMvc.perform(asyncDispatch(mvcResult))`'s `CountDownLatch` waiting forever, hanging Gradle's single-threaded test executor and therefore the entire `./gradlew test` task indefinitely; config: no `timeout-minutes` set anywhere in `.github/workflows/backend-ci.yml`, so nothing auto-recovered from the hang (GitHub Actions' own default job timeout is 360 minutes).
- **Fix:** Added package-private `WikiReloadProgressService.completeAllEmittersForTest(UUID)` that completes the actual registered `SseEmitter` directly (simulating a real client disconnect — the only mechanism that still completes this SSE connection in production); updated the stale test to call it instead of `complete(userId)`, restoring the test's original async-redispatch regression coverage without reverting the blind-window fix's correct production behavior. Also added `timeout-minutes: 20` to the backend CI job as an independent defense-in-depth safety net.
- **Files changed:** backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java, backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java, .github/workflows/backend-ci.yml
- **Why not caught:** The 2026-08-28 blind-window debug session's own verification ran only the pure-unit `WikiReloadProgressServiceTest` (its target/driving test), not the full backend suite and not the MockMvc-level `WikiReloadControllerTest` — so the now-stale assumption in that sibling test went unexercised until the first real CI push. Additionally, no `timeout-minutes` gate existed on the CI workflow to bound a genuine hang, so even a caught failure would have blocked the pipeline for hours instead of failing fast.
- **Recurrence guard:** `WikiReloadControllerTest.shouldNotDenyAuthorization_onAsyncRedispatch_afterEmitterComplete()` (backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java) now exercises the corrected completion path and passes in ~0.47s (verified in isolation, full local suite, and live CI run 33271162681 — "Run tests" step completed in 2m 18s, no hang). `timeout-minutes: 20` in `.github/workflows/backend-ci.yml` bounds any future hang of any cause. Process guard: whenever a service method's side-effect contract changes (e.g. `complete()` no longer closing an emitter), grep for ALL MockMvc/integration-level callers of that method across the suite, not just its own pure-unit test, before considering the change fully verified.

---

## fullsuite-fk-isolation-flakiness — Missing ON DELETE CASCADE + unbounded per-context Hikari pools crash the full backend suite non-deterministically

- **Date:** 2026-08-31
- **Error patterns:** DataIntegrityViolationException, bulk_import_line_user_id_fkey, FK constraint violation, deleteAll, cross-class isolation, shared Testcontainers Postgres, FATAL sorry too many clients already, PSQLException, FlywaySqlException, BeanCreationException, HikariPool, maximum-pool-size, dynamicPort, WireMockExtension, /test/setup, TestSetupController, flaky full suite, gradlew test flakiness, non-deterministic class order
- **Root cause(s):** config: `movies.user_id` / `bulk_import_line.user_id` / `bulk_import_batch.user_id` FKs to `users(id)` (migrations V6/V9/V10) lacked `ON DELETE CASCADE`, unlike every other user-owned child table — any `userRepository.deleteAll()` throws `DataIntegrityViolationException` whenever residual bulk-import/movie rows survive a test-class boundary in the one shared, JVM-lifetime Testcontainers Postgres instance, and identically in production's `TestSetupController` `/test/setup` E2E-reset endpoint; config + environment: no `spring.datasource.hikari.maximum-pool-size` cap for the `test` Spring profile, combined with ~12 test classes each registering a uniquely-keyed `WireMockExtension.dynamicPort()` Spring context (never torn down mid-run) against the ONE shared, fixed-capacity Postgres container (default `max_connections=100`) — total potential connections across concurrently-cached contexts (~100-120) exceeds the ceiling, causing `PSQLException: FATAL: sorry, too many clients already` for whichever context is created last in that run's JVM/JUnit-determined class order. Both causes independently produce "flaky full-suite failure" depending purely on which class lands where in that run's non-deterministic order — not a single linear cause, and not an AND-gate (each alone is sufficient to crash a subset of the suite).
- **Fix:** (A) Flyway migration `V11__add_cascade_delete_to_user_owned_tables.sql` — drops and re-adds `movies_user_id_fkey`, `bulk_import_line_user_id_fkey`, `bulk_import_batch_user_id_fkey`, `bulk_import_line_batch_id_fkey` with `ON DELETE CASCADE`, matching the pattern already established on `user_api_keys`/token tables (V3/V5). (B) `spring.datasource.hikari.maximum-pool-size=5` + `spring.datasource.hikari.minimum-idle=1` added to `backend/src/test/resources/application-test.properties`, bounding per-context connection footprint so ~12+ concurrently-cached test contexts stay under Postgres's connection ceiling.
- **Files changed:** backend/src/main/resources/db/migration/V11__add_cascade_delete_to_user_owned_tables.sql, backend/src/test/resources/application-test.properties, backend/src/test/java/de/moviearchive/UserCascadeDeleteIntegrationTest.java
- **Why not caught:** No gate existed for either cause. Cause A: no test ever asserted cascade-delete behavior on `movies`/`bulk_import_*` (unlike an equivalent gate that would exist if a "does every user-owned FK cascade?" schema-level check existed); code review on the V6/V9/V10 migration PRs didn't cross-reference the CASCADE convention already established in V3/V5. Cause B: an emergent interaction across ~12 independently-authored test classes (no single class's review could see the aggregate connection-pool footprint); additionally, no full-suite CI run had ever completed end-to-end before this session — a sibling issue (`backend-ci-tests-hang`, see entry above) masked this failure class entirely until its own fix landed on 2026-08-29, so "run the full suite in CI to completion" was itself a broken gate for months.
- **Recurrence guard:** Regression test `UserCascadeDeleteIntegrationTest` (backend/src/test/java/de/moviearchive/UserCascadeDeleteIntegrationTest.java — `shouldCascadeDeleteMoviesAndBulkImportRows_whenUserIsDeleted`, `shouldCascadeDeleteBulkImportLines_whenBatchIsDeleted`) directly covers cause A and fails if the CASCADE is ever removed. Config-default change `spring.datasource.hikari.maximum-pool-size=5` (backend/src/test/resources/application-test.properties) directly bounds cause B's per-context footprint. This KB entry is the recurrence guard for the underlying pattern (any future `user_id`/owning-FK added to a new table should default to `ON DELETE CASCADE` unless there's an explicit reason not to — check this entry whenever a new user-owned child table migration is authored).

**Related, separately-tracked issue surfaced by this session's verification (out of scope for this KB entry):** `AuthIntegrationTest` intermittently fails with `Status expected:<200> but was:<429>` because it never calls `rateLimitService.resetAll()` in `@BeforeEach`/`@AfterEach` (unlike `BulkImportControllerTest`/`MovieControllerTest`/`SearchControllerTest`) — confirmed reproduced during this session's checkpoint verification; not fixed here, recommended as a separate debug session/todo.

---
