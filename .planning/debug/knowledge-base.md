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
