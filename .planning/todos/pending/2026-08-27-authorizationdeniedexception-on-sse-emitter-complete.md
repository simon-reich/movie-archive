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

TBD — likely needs to either exclude the async re-dispatch from Spring Security's filter chain
for these SSE completion paths (e.g. via `shouldNotFilterAsyncDispatch` behavior verification,
or a custom `AsyncRequestNotUsableException`-aware exception handler), or investigate why the
JWT auth filter's context isn't available/re-derivable on the async completion dispatch.
Investigate both `WikiReloadController` and `BulkImportController`'s progress endpoints together
since they share the exact same pattern — a fix for one should apply to both.
