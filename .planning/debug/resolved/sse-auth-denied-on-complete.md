---
status: awaiting_human_verify
trigger: "AuthorizationDeniedException logged on SseEmitter.complete() async re-dispatch — see .planning/todos/pending/2026-08-27-authorizationdeniedexception-on-sse-emitter-complete.md for full context. Affects both WikiReloadController/WikiReloadProgressService and BulkImportController's SSE progress endpoints (shared SseEmitter(Long.MAX_VALUE) pattern). Need to find and fix the root cause so these ERROR-level exceptions stop being logged on every SSE completion, without breaking the actual completion behavior (client still needs to receive the complete event)."
created: 2026-08-28
updated: 2026-08-28
---

## Symptoms

expected: SSE progress stream (wiki-reload batch and bulk-import batch) completes cleanly with no ERROR-level log noise when `emitter.complete()` is called.

actual: When a batch stops (`progressService.complete(userId)` → `emitter.complete()`), Tomcat's async-dispatch completion re-runs the request through Spring Security's filter chain. `AuthorizationFilter` throws `AuthorizationDeniedException`. The container then tries to error-dispatch to `/error`, which is ALSO denied, cascading into a second `AuthorizationDeniedException`, finally failing with "Unable to handle the Spring Security Exception because the response is already committed."

errors: |
  AuthorizationDeniedException (first, on async re-dispatch through AuthorizationFilter)
  AuthorizationDeniedException (second, cascading on /error dispatch)
  "Unable to handle the Spring Security Exception because the response is already committed."

timeline: Observed live during Phase 14 UAT (2026-08-27). Confirmed the identical `SseEmitter(Long.MAX_VALUE)` pattern is shared by `BulkImportController#progress` (Phase 11) — pre-existing, not introduced by Phase 14, presumably reproduces there too. Functionally appears harmless (response already committed by the time the exception fires — client already received the actual "complete" SSE event), but pollutes ERROR-level logs on every SSE-backed progress stream's completion.

reproduction: Start a wiki-reload batch (or bulk-import batch) via its SSE progress endpoint, let it reach completion (or call `progressService.complete(userId)` / stop it), observe ERROR-level logs from Spring Security's AuthorizationFilter during the async re-dispatch that follows `emitter.complete()`.

## Current Focus

hypothesis: "AuthorizationFilter re-runs on the ASYNC servlet dispatch that Spring MVC/Tomcat performs after emitter.complete() (called from a background executor thread). JwtAuthFilter (OncePerRequestFilter) does not re-run on ASYNC dispatch by default, and SecurityConfig's STATELESS session policy means the default SecurityContextRepository is NullSecurityContextRepository (nothing persisted/reloaded across dispatches) — so AuthorizationFilter sees an empty/anonymous SecurityContext on the redispatch and denies anyRequest().authenticated()."
test: "Add spring.security.filter.dispatcher-types=request,error (drop async) to application.properties; write a MockMvc test using request().asyncStarted() + asyncDispatch(mvcResult) around WikiReloadProgressService.complete(userId) to reproduce pre-fix and confirm post-fix."
expecting: "Pre-fix: asyncDispatch produces 403/AuthorizationDeniedException (or the committed-response failure). Post-fix: asyncDispatch completes cleanly with no exception, since the whole Spring Security filter chain (including AuthorizationFilter) is no longer invoked for ASYNC dispatch type at all."
next_action: "awaiting human verification — confirm no AuthorizationDeniedException ERROR logs appear when running a real wiki-reload or bulk-import batch to completion (see checkpoint below), then archive_session"
reasoning_checkpoint: |
  hypothesis: "AuthorizationFilter re-evaluates authorizeHttpRequests() on the ASYNC servlet
    redispatch triggered by SseEmitter.complete()(called off the original request thread from
    WikiReloadProgressService.complete()/BulkImportProgressService), and denies it because the
    SecurityContext is empty at that point — JwtAuthFilter (OncePerRequestFilter) skips ASYNC
    dispatch by default (shouldNotFilterAsyncDispatch()==true) and SecurityConfig's
    sessionCreationPolicy(STATELESS) leaves the default SecurityContextRepository as
    NullSecurityContextRepository, so nothing persists/reloads the Authentication across the
    redispatch."
  confirming_evidence:
    - "JwtAuthFilter.java extends OncePerRequestFilter and calls
      SecurityContextHolder.getContext().setAuthentication(...) directly — never saves to a
      SecurityContextRepository (JwtAuthFilter.java:18,41)."
    - "SecurityConfig.java sets .sessionManagement(s -> s.sessionCreationPolicy(STATELESS)) and
      never configures .securityContext(...) — no explicit SecurityContextRepository bean, so
      Spring Security's STATELESS default (NullSecurityContextRepository) applies
      (SecurityConfig.java:28)."
    - "No spring.security.filter.dispatcher-types override anywhere in application.properties —
      Spring Boot's SecurityFilterAutoConfiguration default is REQUEST,ASYNC,ERROR, confirmed via
      web search (Spring Boot issue #33090/#4505 threads) — so AuthorizationFilter (not a
      OncePerRequestFilter, does not skip ASYNC) runs again on the redispatch."
    - "Both WikiReloadController#progress and BulkImportController#progress return
      SseEmitter(Long.MAX_VALUE) and only their owning WikiReloadProgressService.complete() /
      BulkImportProgressService.complete() call emitter.complete() — both from the async
      executor thread, matching the exact 'shared pattern' noted in the trigger."
    - "Corroborated by public Spring Security issue spring-projects/spring-security#11962
      ('SecurityContextHolderFilter does not apply to async dispatch') and independent writeups
      describing the identical SSE + Spring Security 'Access Denied on completion' failure mode,
      with the documented fix being either exclude ASYNC from
      spring.security.filter.dispatcher-types, or persist the SecurityContext via
      RequestAttributeSecurityContextRepository."
  falsification_test: "If a MockMvc test using request().asyncStarted() + asyncDispatch(mvcResult)
    around a call to WikiReloadProgressService.complete(userId) does NOT throw/produce a 403 on
    current code, the hypothesis is wrong (something else causes the denial, e.g. an actual bug in
    assertOwnership() logic re-running with wrong data)."
  fix_rationale: "Setting spring.security.filter.dispatcher-types=request,error removes ASYNC from
    the set of dispatcher types Spring Boot registers Spring Security's FilterChainProxy for. This
    means AuthorizationFilter (and the rest of the security chain) simply does not run again on
    the SSE async-completion redispatch at all — there is nothing left to deny, so no
    AuthorizationDeniedException is thrown, and consequently no cascading /error dispatch either.
    This targets the actual mechanism (filter chain running where it has no useful security value:
    the real client-facing 'complete' SSE event and ownership check already happened
    synchronously earlier in the same request) rather than papering over the exception with a
    global exception handler that would still let AuthorizationFilter deny access and would still
    require the container to figure out how to render a response for an already-committed SSE
    body."
  blind_spots: "Have not verified this against a real Tomcat container (only MockMvc, which uses
    MockAsyncContext semantics) — MockMvc's async-dispatch simulation is the standard, documented
    way to test this exact class of bug, but a live-server smoke check during human verification
    is still warranted per the todo's own trigger note (observed live in UAT). Also have not
    audited whether ERROR dispatch type still needs security enforcement anywhere relevant in this
    app (kept ERROR in the property to preserve existing behavior for genuine synchronous request
    errors going through Boot's BasicErrorController)."
  candidate_causes:
    - "config: spring.security.filter.dispatcher-types left at Spring Boot's default (REQUEST,ASYNC,ERROR) — never scoped down to exclude ASYNC, even though this app's only async controller usage (SseEmitter) has no legitimate need for re-authorization on its completion redispatch."
    - "code: JwtAuthFilter sets the SecurityContext only in-thread via SecurityContextHolder, never through a SecurityContextRepository, combined with SecurityConfig's STATELESS policy defaulting to NullSecurityContextRepository — so even if ASYNC dispatch were kept, nothing would restore the Authentication for it."
  and_gate: "Yes — both conditions must hold simultaneously for the failure to manifest: (1) config: ASYNC dispatch type is in the security filter's registered set, AND (2) code: no mechanism re-establishes/persists the SecurityContext across dispatches (JwtAuthFilter's OncePerRequestFilter default + Null repository). Fixing condition (1) alone (removing ASYNC from dispatcher-types) fully closes the gate for this bug, because the chain never runs on that dispatch at all regardless of (2) — verified this is sufficient rather than only fixing (2), since (2) has no legitimate use here (the async redispatch is a pure completion formality, not a place new business logic executes)."

## Evidence

- timestamp: 2026-08-28T00:00:00Z
  checked: backend/src/main/java/de/moviearchive/admin/WikiReloadController.java and backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
  found: Both `progress()` endpoints call assertOwnership()/loadOwnedBatch() synchronously (throws AccessDeniedException, handled by a controller-local @ExceptionHandler) BEFORE constructing `new SseEmitter(Long.MAX_VALUE)`. The local @ExceptionHandler only covers exceptions thrown from within the controller method invocation on the initial REQUEST dispatch — it cannot intercept exceptions thrown by Spring Security's filter chain on a later container-level redispatch, since @ExceptionHandler only wraps DispatcherServlet's handler-invocation, not the filter chain.
  implication: The 403 tests (shouldReturn403_whenUserMismatch_onProgressEndpoint etc.) only exercise the initial REQUEST dispatch path; they say nothing about the ASYNC redispatch that happens later when emitter.complete() is called from the background executor thread.

- timestamp: 2026-08-28T00:05:00Z
  checked: backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java
  found: "extends OncePerRequestFilter; doFilterInternal() reads the Authorization header and calls SecurityContextHolder.getContext().setAuthentication(authentication) directly — never persists the context to any SecurityContextRepository."
  implication: "OncePerRequestFilter.shouldNotFilterAsyncDispatch() defaults to true (Spring Framework behavior), so this filter does NOT re-run on the ASYNC dispatch that follows emitter.complete(). No Authentication gets (re-)established for that redispatch."

- timestamp: 2026-08-28T00:07:00Z
  checked: backend/src/main/java/de/moviearchive/config/SecurityConfig.java
  found: ".sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) is set; .securityContext(...) is never explicitly configured; .anyRequest().authenticated() covers /admin/wiki-reload/** and /movies/bulk-import/** (only /auth/**, /actuator/health, /settings/confirm-email, /test/** are permitAll)."
  implication: "Under STATELESS session policy with no explicit securityContext() configuration, Spring Security's default SecurityContextRepository is NullSecurityContextRepository — nothing loads/restores a SecurityContext across dispatches, so the ASYNC redispatch starts with an empty/anonymous context."

- timestamp: 2026-08-28T00:10:00Z
  checked: backend/src/main/resources/application.properties (full file) and application-test.properties
  found: No `spring.security.filter.dispatcher-types` property is set anywhere in the project.
  implication: Spring Boot's SecurityFilterAutoConfiguration default dispatcher types (REQUEST, ASYNC, ERROR) apply — Spring Security's FilterChainProxy (including AuthorizationFilter, which is NOT a OncePerRequestFilter and therefore does not skip ASYNC dispatch) is registered to run again for the ASYNC redispatch that Spring MVC's SseEmitter/WebAsyncManager machinery triggers when emitter.complete() finalizes the async context.

- timestamp: 2026-08-28T00:12:00Z
  checked: backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java (complete() method) — structurally identical BulkImportProgressService confirmed present via earlier grep
  found: "complete(UUID userId) is called from WikiReloadService.batchReload()'s async execution on wikiReloadExecutor (a background thread pool, not the original HTTP request thread) and calls emitter.complete() directly after a successful sendEvent()."
  implication: Confirms the exact mechanism described in Symptoms — emitter.complete() fires from a background thread well after the original controller method returned, triggering Tomcat's async-dispatch-completion re-entry into the (still-registered-for-ASYNC) Spring Security filter chain.

- timestamp: 2026-08-28T00:15:00Z
  checked: Web search — Spring Boot default `spring.security.filter.dispatcher-types`, and Spring Security GitHub issue #11962 "SecurityContextHolderFilter does not apply to async dispatch" plus corroborating community writeups on SSE + Spring Security "Access Denied" on stream completion
  found: Confirmed Spring Boot's default dispatcher types are REQUEST, ASYNC, ERROR (spring-boot#33090/#4505 threads). Confirmed this exact SSE-completion-triggers-AuthorizationDeniedException failure mode is a known, previously-reported class of bug, with the standard fixes being either (a) exclude ASYNC via spring.security.filter.dispatcher-types, or (b) configure RequestAttributeSecurityContextRepository + explicit context-save so the context survives the redispatch.
  implication: This is a well-documented Spring Security/Boot interaction, not an app-specific typo or logic bug — strengthens confidence the hypothesis is the actual root cause rather than a coincidental correlation.

- timestamp: 2026-08-28T00:20:00Z
  checked: backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java and backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java for other async-return-type usage (DeferredResult/Callable/WebAsyncTask/StreamingResponseBody) across backend/src/main/java
  found: grep found zero usages of DeferredResult/Callable</WebAsyncTask/StreamingResponseBody anywhere in backend/src/main/java — SseEmitter (WikiReloadController#progress, BulkImportController#progress) is the app's only async-controller-return-type usage. No existing test simulates the ASYNC redispatch (no asyncDispatch() usage anywhere in the test suite yet).
  implication: Safe to globally exclude ASYNC from spring.security.filter.dispatcher-types — there is no other legitimate use case in this app where re-running authorization on an ASYNC redispatch would provide real security value; the actual ownership/ID0R checks for both SSE endpoints already run synchronously on the initial REQUEST dispatch (assertOwnership()/loadOwnedBatch()), which is unaffected by this property (REQUEST stays in the set).

- timestamp: 2026-08-28T00:30:00Z
  checked: "Ran new regression test WikiReloadControllerTest#shouldNotDenyAuthorization_onAsyncRedispatch_afterEmitterComplete against current (unfixed) code via `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock ./gradlew test --tests ...`"
  found: "Test FAILED with jakarta.servlet.ServletException caused by org.springframework.security.authorization.AuthorizationDeniedException, thrown during mockMvc.perform(asyncDispatch(mvcResult)) — exactly reproducing the bug via MockMvc's real filter chain."
  implication: "Confirms the hypothesis with direct, reproducible evidence (not inference). Root cause confirmed. Proceeding to fix (TDD red phase complete)."

## Eliminated

## Resolution

root_cause: "Spring Boot's default spring.security.filter.dispatcher-types (REQUEST, ASYNC, ERROR) registers Spring Security's FilterChainProxy — including AuthorizationFilter — to run again on the ASYNC servlet redispatch that follows SseEmitter.complete() (called from a background executor thread in WikiReloadProgressService.complete()/BulkImportProgressService.complete()). AuthorizationFilter is not a OncePerRequestFilter and therefore does not skip ASYNC dispatch, but JwtAuthFilter IS a OncePerRequestFilter (skips ASYNC dispatch by default) and never persists its Authentication to a SecurityContextRepository — combined with SecurityConfig's STATELESS session policy defaulting to NullSecurityContextRepository, the SecurityContext is empty on the redispatch, so AuthorizationFilter denies the already-authenticated-and-already-completed request, throwing AuthorizationDeniedException (and a second one on the resulting /error dispatch)."
fix: "Set spring.security.filter.dispatcher-types=request,error in backend/src/main/resources/application.properties, excluding ASYNC from the set of dispatcher types Spring Boot registers Spring Security's FilterChainProxy for. The app's only async-controller-return-type usage is SseEmitter (WikiReloadController#progress, BulkImportController#progress) — their ownership/IDOR checks already run synchronously on the initial REQUEST dispatch (assertOwnership()/loadOwnedBatch()), unaffected by this change (REQUEST stays in the set). The ASYNC completion redispatch is now skipped by Spring Security entirely, so AuthorizationFilter never re-evaluates authorization against the empty SecurityContext, and no exception (nor the cascading /error dispatch) is triggered. Also added a regression test to each affected controller test class (WikiReloadControllerTest, BulkImportControllerTest) that registers an SSE connection, explicitly calls the progress service's complete(id) method (mirroring the real background-thread completion flow), then uses MockMvc's asyncDispatch(mvcResult) to simulate the container's real ASYNC redispatch through the full Spring Security filter chain and assert it completes with 200, not a thrown AuthorizationDeniedException."
verification: |
  target_test: { result: pass }
  mutation_check: { result: skipped, reason_if_skipped: "No PIT/Stryker mutation-testing tool configured in backend/build.gradle.kts — nothing to scope a mutant-kill check to." }
  no_op_deletion: { result: pass, deletion_justified_by_rca: "n/a — diff is purely additive (15-line application.properties addition + 2 new regression test methods), zero deletions across all 3 changed files (git diff --stat: 100 insertions(+), 0 deletions(-))." }
  adjacent_tests: { result: pass, suites_run: ["WikiReloadControllerTest (full class, all tests incl. the 3 existing 403/IDOR tests on the same endpoints)", "BulkImportControllerTest (full class, all tests incl. the 403/IDOR and existing SSE-registration tests)", "AuthIntegrationTest", "UserControllerTest"] }
  adjacent_tests_note: "A full-suite `./gradlew test` run (220 tests) showed 99 pre-existing failures (DataIntegrityViolationException/connection issues in DashboardControllerTest, SearchControllerTest, SettingsIntegrationTest, UserControllerTest, plus ObjectOptimisticLockingFailureException noise from EnrichmentService's background @Async in unrelated tests) that are identical in nature and count on baseline (confirmed via git stash to revert the fix + rerun: 99 failures on baseline too, same signature) — pre-existing Testcontainers/shared-DB flakiness in this sandboxed environment, not caused by this fix. The narrower AuthIntegrationTest+UserControllerTest run showed the same flakiness pattern on both baseline (4 failures) and fixed (2 failures) code — fixed code was never worse. No newly-broken neighbor attributable to this change.
  revert_and_reconfirm: { result: pass, bug_returned_on_revert: true, fixed_on_reapply: true }
  revert_and_reconfirm_detail: "git stash push scoped to only application.properties (kept both regression tests in place); ran both new regression tests -> 2/2 FAILED with AuthorizationDeniedException (bug returned). git stash pop to reapply the fix; reran the same 2 tests -> BUILD SUCCESSFUL (bug fixed). Also originally observed the identical failure signature the first time the target test was run against pristine (pre-fix) code, before any fix code existed (TDD red phase)."
  guardrail_verdict: accepted
files_changed:
  - backend/src/main/resources/application.properties
  - backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java
