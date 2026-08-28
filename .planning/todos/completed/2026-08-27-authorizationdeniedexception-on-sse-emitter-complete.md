---
created: 2026-08-27T18:52:00.000Z
title: AuthorizationDeniedException logged on SseEmitter.complete() async re-dispatch
area: backend
severity: minor
files:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
  - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
---

## Problem

Observed live during Phase 14 UAT (2026-08-27): when a wiki-reload batch stops (via
`progressService.complete(userId)` calling `emitter.complete()`), Tomcat's async-dispatch
completion re-runs the request through Spring Security's filter chain, and
`AuthorizationFilter` throws `AuthorizationDeniedException`, followed by a second, cascading
`AuthorizationDeniedException` when the container tries to error-dispatch to `/error` (also
denied) and finally fails with "Unable to handle the Spring Security Exception because the
response is already committed."

Confirmed the same `SseEmitter(Long.MAX_VALUE)` pattern is used identically by
`BulkImportController#progress` (Phase 11) — this is a pre-existing, shared architectural
pattern, not something introduced by Phase 14, and would presumably reproduce there too.

Functionally appears harmless: the log confirms the response was already committed by the time
the exception fires, meaning the actual SSE "complete" event data had already reached the
client — the Settings page's progress panel correctly disappeared once stop actually took
effect. This is log noise, not a confirmed user-facing bug, but it pollutes ERROR-level logs on
every SSE-backed progress stream's completion and should be root-caused properly.

## Solution

Resolved via `.planning/debug/resolved/sse-auth-denied-on-complete.md`.

**Root cause:** Spring Boot's default `spring.security.filter.dispatcher-types` (REQUEST, ASYNC,
ERROR) registers Spring Security's `FilterChainProxy` — including `AuthorizationFilter` — to run
again on the ASYNC servlet redispatch that follows `SseEmitter.complete()` (called from a
background executor thread in `WikiReloadProgressService.complete()` /
`BulkImportProgressService.complete()`). `AuthorizationFilter` is not a `OncePerRequestFilter`
and therefore does not skip ASYNC dispatch, but `JwtAuthFilter` IS a `OncePerRequestFilter`
(skips ASYNC dispatch by default) and never persists its `Authentication` to a
`SecurityContextRepository` — combined with `SecurityConfig`'s STATELESS session policy
defaulting to `NullSecurityContextRepository`, the `SecurityContext` is empty on the redispatch,
so `AuthorizationFilter` denies the already-authenticated, already-completed request.

**Fix:** Set `spring.security.filter.dispatcher-types=request,error` in
`backend/src/main/resources/application.properties`, excluding ASYNC from the set of dispatcher
types Spring Boot registers Spring Security's filter chain for. Both SSE endpoints' ownership/
IDOR checks already run synchronously on the initial REQUEST dispatch, unaffected by this
change. Added regression tests to `WikiReloadControllerTest` and `BulkImportControllerTest` that
use MockMvc's `asyncDispatch()` to simulate the real container ASYNC redispatch and assert it no
longer throws `AuthorizationDeniedException`.

**Verified live (2026-08-28):** started/stopped a wiki-reload batch multiple times — no
`AuthorizationDeniedException` or "response already committed" errors in the backend log around
any completion/stop.
