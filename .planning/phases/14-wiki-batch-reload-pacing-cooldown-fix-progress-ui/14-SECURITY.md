---
phase: 14
slug: wiki-batch-reload-pacing-cooldown-fix-progress-ui
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: 2026-08-28
---

# Phase 14 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Browser -> `GET /admin/wiki-reload/{userId}/progress` | Untrusted client opens a long-lived SSE stream identified by a client-supplied `userId` path variable | Live per-movie title/status, processed/total counts, ETA |
| Browser -> `POST /admin/wiki-reload/{userId}/stop` | Untrusted client requests cancellation of an in-progress batch identified by a client-supplied `userId` path variable | Stop signal only, no payload |
| Async batch loop <-> `WikiReloadProgressService` in-memory registry | Cross-thread state shared between the `wikiReloadExecutor` worker thread and HTTP request threads | Progress state (per-user, in-memory `ConcurrentHashMap`) |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-14-01 | Elevation of Privilege | `GET .../progress`, `POST .../stop` (`WikiReloadController`) | high | mitigate | `assertOwnership(auth, userId)` called at the top of both endpoints (and `triggerReload`), returning 403 on a JWT-subject/path-userId mismatch — verified present in `WikiReloadController.java` lines 59/73/88; regression tests `shouldReturn403_whenUserMismatch_onProgressEndpoint` and `shouldReturn403_whenUserMismatch_onStopEndpoint` confirmed present in `WikiReloadControllerTest.java` | closed |
| T-14-02 | Information Disclosure | Frontend SSE consumption (`subscribeToWikiReloadProgress`) | high | mitigate | `@microsoft/fetch-event-source` used with header-based `Authorization`, never native `EventSource` (which cannot carry a header and would force a query-param token leaking into server/proxy access logs) — verified `import { fetchEventSource } from '@microsoft/fetch-event-source'` present in `useSettings.ts`; the later-session `onopen`/`onerror` hardening (403/404-only fatal abort) preserved this same header-based auth, no regression | closed |
| T-14-03 | Denial of Service | `SseEmitter` lifecycle in `WikiReloadProgressService.register()` | medium | mitigate | `SseEmitter(Long.MAX_VALUE)` combined with `onCompletion`/`onTimeout` -> `removeEmitter` cleanup, identical to the already-shipped `BulkImportProgressService.register()` pattern — verified present at `WikiReloadProgressService.java` lines 68-69; prevents an abandoned browser tab from leaking emitter references | closed |
| T-14-04 | Denial of Service (self-inflicted) | `WikiReloadService.batchReload()`'s stop-flag lifecycle | high | mitigate | `progressService.resetRun(userId)` called unconditionally at the top of every `batchReload()` invocation, before the per-movie loop — a stale `true` stop flag from a prior Stop click can never silently no-op a fresh Start — verified present at `WikiReloadService.java` line 170 (preserved through the post-plan chunk-interleaving refactor); regression test `resetRun_afterPriorRequestStop_clearsFlagBackToFalse` confirmed present in `WikiReloadProgressServiceTest.java` | closed |
| T-14-05 | Information Disclosure | `etaSeconds` field added to `ProgressState` | low | accept | A rolling-average call-duration estimate reveals no more than the already-streamed processed/total counts already disclose to the (ownership-checked) subscriber; no new sensitive data is included | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on (high) count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

**Post-plan changes reviewed for new threats:** This session's live-UAT fix commits (chunk-interleaved Wikidata prefetch, ETA pacing-delay inclusion, Stop-button UX state, `WIKI_RETRY_PACING_DELAY_MS` default 30s->20s) touch only `WikiReloadService`'s internal batch loop and frontend display state — no new endpoints, no changes to `assertOwnership` call sites, no new data crossing the browser/server boundary beyond what was already in the register. No new threats identified.

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| R-14-01 | T-14-05 | ETA seconds is a derived timing estimate with strictly less information content than the processed/total counts already streamed to the same ownership-checked subscriber; accepted at plan time in 14-02-PLAN.md's threat model | Planner (14-02-PLAN.md) | 2026-08-27 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-08-28 | 5 | 5 | 0 | Claude (gsd-secure-phase, State B — built from PLAN.md threat models, no auditor spawn needed per ASVS L1 short-circuit: threats_open=0, register_authored_at_plan_time=true) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-08-28
