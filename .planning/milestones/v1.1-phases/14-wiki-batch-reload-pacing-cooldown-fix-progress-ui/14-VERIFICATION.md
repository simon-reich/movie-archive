---
phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
verified: 2026-08-27T16:09:35Z
status: passed
score: 6/7 must-haves verified
behavior_unverified: 1
overrides_applied: 0
behavior_unverified_items:

  - truth: "User can click Stop mid-run; the run halts cleanly after the currently-processing movie finishes, never mid-fetch"
    test: "Run backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java#shouldHaltBatch_whenStopRequestedMidRun (POST trigger, immediately POST stop, poll, assert fewer than 3 of 3 persisted movies end up with indexedAt != null) against a working Docker/Testcontainers environment. Alternatively, manually trigger a reload with 3+ eligible movies on a dev instance, click Stop, and confirm the per-movie history list stops growing after the in-flight movie finishes."
    expected: "The batch loop breaks at its next loop-boundary check (before the next movie, or before the pacing sleep) rather than continuing to process all eligible movies; strictly fewer movies end up indexed than the total eligible count."
    why_human: "This is a cancellation/cleanup invariant (state transition: running -> halted) that only a real multi-movie async batch run against a live DB can exercise. The one test written specifically to prove it (`shouldHaltBatch_whenStopRequestedMidRun`) requires Testcontainers-managed Postgres + OpenSearch containers. In this verification session, Testcontainers could not reach Docker (`Could not find a valid Docker environment`) — confirmed as a sandbox-wide limitation, not a phase-14 regression, by independently reproducing the identical `initializationError`/`DockerClientProviderStrategy` failure on the untouched `MovieControllerTest` class. The stop-flag *mechanics* (`resetRun`/`requestStop`/`isStopRequested`) are unit-tested and passing; only the full-loop, DB-backed halting behavior is unverified here."
human_verification:

  - test: "Run `cd backend && ./gradlew test --tests \"de.moviearchive.admin.WikiReloadControllerTest\"` in an environment where Testcontainers can reach Docker, or manually trigger + Stop a batch-reload with 3+ eligible movies on a running dev stack."
    expected: "shouldHaltBatch_whenStopRequestedMidRun passes (fewer than all eligible movies get indexed); on a manual test, the progress panel's history list stops growing shortly after clicking Stop, and clicking Reload again resumes processing the remaining movies without hanging."
    why_human: "Docker/Testcontainers unreachable from this verification session's Gradle test JVM; confirmed as an environment-wide limitation, not a code gap — see behavior_unverified_items above."
---

# Phase 14: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI Verification Report

**Phase Goal:** Fix Phase 13's live-verification follow-on problems (real Wikipedia 429s under sustained load, and a cooldown-marking bug that blocks technically-failed movies for 30 days) by (a) raising `batchReload()`'s between-movie pacing to a deliberate, env-configurable ~30s default while leaving the existing reactive 429-backoff untouched as a fallback; (b) only advancing `wikiLastAttemptedAt` on a genuine, successfully-executed attempt (success or confirmed not-found), never on a technical/rate-limit failure; (c) shipping the long-deferred batch-reload progress UI — live per-movie progress, a rolling-average ETA, and a Stop control — on the Settings page.

**Verified:** 2026-08-27T16:09:35Z
**Status:** human_needed
**Re-verification:** No — initial verification.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | User can trigger a Wikipedia batch-reload and see live per-movie progress (title + status) update on the Settings page via SSE, without manual refresh | ✓ VERIFIED | `WikiReloadProgressService` register/publish/complete lifecycle unit-tested (9/9 pass, independently re-run); `settings.vue`'s mount-based test `renders processed/total and the movie title once a progress event arrives` (independently re-run, 183/183 frontend suite green) proves the component actually re-renders from a live SSE callback, not a static value |
| 2 | User can click Stop mid-run; the run halts cleanly after the currently-processing movie finishes, never mid-fetch | ⚠️ PRESENT_BEHAVIOR_UNVERIFIED | Code present and correctly wired (`isStopRequested()` checked at both loop-boundary points in `WikiReloadService.batchReload()`); the one test that exercises this end-to-end (`WikiReloadControllerTest#shouldHaltBatch_whenStopRequestedMidRun`) could not be run in this session — see Human Verification |
| 3 | Clicking Reload again after Stop immediately continues processing the remaining eligible movies — no stale stop-flag silently blocks the new run | ✓ VERIFIED | `resetRun_afterPriorRequestStop_clearsFlagBackToFalse` unit test (independently re-run, passes) directly exercises the regression case: `requestStop()` then `resetRun()` clears the flag back to `false`; `batchReload()` calls `progressService.resetRun(userId)` unconditionally at the very top, before the loop (confirmed by code read) |
| 4 | A movie that fails only due to a technical/rate-limit error is NOT cooldown-blocked — it remains immediately eligible for the very next reload run | ✓ VERIFIED | `shouldNotSetTimestamp_onGenericTechnicalFailure` unit test (independently re-run, passes): stubs a generic `RuntimeException`, asserts `wikiLastAttemptedAt` stays `null`; `grep -c 'setWikiLastAttemptedAt' WikiReloadService.java` = 2 (success + `WikipediaNotFoundException` only, confirmed by code read) |
| 5 | A movie that is successfully checked (found, or genuinely confirmed not-found) gets `wikiLastAttemptedAt` updated and is excluded from reload until the cooldown elapses | ✓ VERIFIED | `shouldSetTimestampAndWikiFields_onRetrySuccess` and `shouldSetTimestampOnly_whenWikipediaNotFound` unit tests (independently re-run, both pass, confirmed unmodified by the D-14-02 restructuring) |
| 6 | Batch-reload paces its between-movie Wikipedia calls at the new, deliberately slower default (30s) while the existing reactive 429-backoff remains untouched as a fallback safety net | ✓ VERIFIED | `application.properties` line 65: `wiki.retry.pacing-delay-ms=${WIKI_RETRY_PACING_DELAY_MS:30000}` (confirmed by direct read); `wikipedia.request-pacing-ms` (line 61, `1000`) and `wikidata.request-pacing-ms` (line 56, `3000`) both unchanged |
| 7 | User sees an ETA estimate that adapts to real observed per-movie call durations (including active 429 backoff time), not a fixed pacing-delay-based guess | ✓ VERIFIED | `publish_computesEtaSeconds_asRollingAverageTimesRemaining` and `publish_windowCapsAtFiveEntries` unit tests (independently re-run, both pass — part of the 9/9 `WikiReloadProgressServiceTest` run); 3 mount-based frontend tests for the `~4 min remaining` / `~45s remaining` / no-label-at-zero formatting (independently re-run, all pass) |

**Score:** 6/7 truths verified (1 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/.../admin/WikiReloadProgressService.java` | SSE registry + stop-flag + ETA rolling window | ✓ VERIFIED | Exists, substantive (194 lines, full lifecycle), wired into both `WikiReloadService` and `WikiReloadController` |
| `backend/test/.../admin/WikiReloadProgressServiceTest.java` | Full lifecycle + stop-flag + ETA test coverage | ✓ VERIFIED | 9/9 tests pass (independently re-run) |
| `backend/.../enrichment/WikiReloadService.java` | `WikiRetryOutcome` enum, restructured `doRetryWikipedia()`, stop-flag-aware `batchReload()` | ✓ VERIFIED | All three elements confirmed present by direct code read; `batchReload()` also wraps its body in try/finally (CR-01 fix — see below) |
| `backend/.../admin/WikiReloadController.java` | `GET .../progress`, `POST .../stop` | ✓ VERIFIED | Both endpoints present, both call `assertOwnership()` |
| `frontend/composables/useSettings.ts` | `subscribeToWikiReloadProgress`, `stopWikiReload`, `WikiReloadProgress` | ✓ VERIFIED | All three exported; uses `@microsoft/fetch-event-source` with header-based auth (never native `EventSource`) |
| `frontend/pages/settings.vue` | `#wikipedia-data` progress block + Stop button | ✓ VERIFIED | `data-testid="wiki-reload-progress"` block present, Stop button gated on `wikiProgress && !wikiProgress.complete` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `WikiReloadService.batchReload()`'s per-movie loop | `WikiReloadProgressService.resetRun()/isStopRequested()/publish()` | Direct method calls | ✓ WIRED | `resetRun()` at top of method, `isStopRequested()` checked at both loop-boundary points, `publish()` called once per processed movie with real status + duration |
| `WikiReloadController`'s `GET .../progress`/`POST .../stop` | `assertOwnership()` | Direct call | ✓ WIRED | Both new endpoints call the existing private `assertOwnership(auth, userId)` method before touching `progressService` |
| `settings.vue`'s `subscribeToWikiReloadProgress` | `@microsoft/fetch-event-source` | Header-based `Authorization` | ✓ WIRED | `useSettings.ts` imports `fetchEventSource`, passes `authHeaders()` as the `headers` option |
| `batchReload()`'s wall-clock duration measurement | `WikiReloadProgressService`'s 5-entry rolling window → `ProgressState.etaSeconds` → `settings.vue`'s `wikiEtaLabel` | `publish(..., durationMs)` → computed | ✓ WIRED | `startMs`/`durationMs` measured around `self.retryWikipedia(...)`, passed into `publish()`; `publish()` pushes onto `durationWindowsMs` deque (capped at 5), computes `etaSeconds`; `WikiReloadProgress.etaSeconds` consumed by `wikiEtaLabel` computed in `settings.vue` |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Backend unit suite for progress service + cooldown-fix logic | `cd backend && ./gradlew test --tests "de.moviearchive.admin.WikiReloadProgressServiceTest" --tests "de.moviearchive.movie.WikiReloadServiceTest" --rerun` | 15/15 pass (9 + 6, XML-confirmed via test-results) | ✓ PASS |
| Named tests for D-14-02 (cooldown fix) and D-08 (stale-flag fix) exist and pass individually | Enumerated via test-results XML: `shouldNotSetTimestamp_onGenericTechnicalFailure`, `resetRun_afterPriorRequestStop_clearsFlagBackToFalse` | Both present, both pass | ✓ PASS |
| Frontend full suite | `cd frontend && pnpm test -- run` | 183/183 pass across 25 files, including 16 `useSettings.spec.ts` + 15 `settings.spec.ts` tests (6 new mount-based progress/Stop/ETA tests) | ✓ PASS |
| Backend integration test for Stop-mid-run (`WikiReloadControllerTest`) | `cd backend && ./gradlew test --tests "de.moviearchive.admin.WikiReloadControllerTest" --rerun` | `initializationError` — `Could not find a valid Docker environment` inside `AbstractIntegrationTest`'s static init (Testcontainers) | ? SKIP — see below |
| Independent regression check: does the Docker failure affect untouched classes identically? | `cd backend && ./gradlew test --tests "de.moviearchive.movie.MovieControllerTest" --rerun` | Identical `initializationError`/`DockerClientProviderStrategy` failure on a class this phase never touched | ✓ CONFIRMS environment-wide limitation, not a phase-14 regression |
| Config default check | `grep wiki.retry.pacing-delay-ms backend/src/main/resources/application.properties` | `${WIKI_RETRY_PACING_DELAY_MS:30000}` | ✓ PASS |

**Root-cause confirmation for the Docker failure:** `~/.testcontainers.properties` pins `docker.client.strategy=UnixSocketClientProviderStrategy` (expects `/var/run/docker.sock`), while the active Docker context in this session is OrbStack, exposing its socket at `~/.orbstack/run/docker.sock` — a host/session Docker-context mismatch reachable by the interactive shell (`docker ps` succeeds) but not by the Gradle test JVM's Testcontainers client. This independently corroborates the phase's own diagnosis in the task brief: unrelated integration test classes (confirmed here with `MovieControllerTest`) fail identically, and it is a sandbox/session configuration issue, not something Phase 14's code changes caused.

### Code Review Findings — Fix Verification

| ID | Finding | Status | Evidence |
|----|---------|--------|----------|
| CR-01 (Critical) | `batchReload()` had no top-level exception handling — a pre-loop failure hangs the SSE stream and leaks per-user state forever | ✓ FIXED | `batchReload()` now wraps its body in `try { ... } catch (Exception e) { log.error(...) } finally { progressService.complete(userId); }` (confirmed by direct code read, lines 168-226) |
| WR-01 | Completion log line reported `eligible.size()` instead of the real `processedCount` on an early stop | ✓ FIXED | Log line now reads `log.info("...processed={} eligible={}", userId, processedCount, eligibleCount)` |
| WR-02 | Terminal SSE event cannot distinguish "fully completed" from "stopped early" | **Deliberately deferred** | Confirmed still present in `WikiReloadProgressService.complete()` (`new ProgressState(total, total, true, ...)` regardless of actual stop point) — but this is documented and tracked as intentional: `.planning/todos/pending/2026-08-27-distinguish-stopped-vs-completed-in-progress-ui.md` exists, accurately describes the current (unchanged) behavior, and cites the same schema-change rationale as `14-REVIEW.md`. Treated as a known, accurately-tracked limitation, not a verification failure. |
| WR-03 | Reload trigger button stayed clickable during an in-progress run and wiped visible history on a double-trigger | ✓ FIXED | `settings.vue` line 470: `:disabled="wikiReloadTriggering || (wikiProgress && !wikiProgress.complete)"`; history-clear now gated on `result === 'started' && (!wikiProgress.value || wikiProgress.value.complete)` |
| WR-04 | SSE `onerror` threw unconditionally, killing the live stream on any transient error | ✓ FIXED | `useSettings.ts`'s `onerror` now only rethrows for `status === 403 \|\| status === 404` (tagged via `onopen`); other errors fall through to the library's default retry |
| IN-01 | `onMounted`'s try/catch comment implied broader protection than it provides | ✓ FIXED | Comment corrected to state it only covers `getCurrentUserId()` failures, not SSE connection errors |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `.planning/STATE.md` | 85 | "Pending Todos" still lists `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload`, even though the underlying todo file was moved to `.planning/todos/completed/` in commit `c6810f3`, and the same commit's newly-filed `2026-08-27-distinguish-stopped-vs-completed-in-progress-ui` todo is not listed in this section at all | ℹ️ Info | Documentation-only drift in the planning tracker — does not affect any codebase artifact, truth, or key link verified above. Worth a follow-up STATE.md sync, not a phase-goal blocker. |

No `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` markers found in any of the 5 core files this phase modified/created (`WikiReloadProgressService.java`, `WikiReloadService.java`, `WikiReloadController.java`, `useSettings.ts`, `settings.vue`). The "Coming soon" text on `settings.vue` line 460 is a pre-existing, unrelated section (not part of this phase's scope).

### Requirements Coverage

No formal `REQUIREMENTS.md` IDs exist for Phase 14 (confirmed by `grep -n "D-14" .planning/REQUIREMENTS.md` returning zero matches) — this matches the phase's own explicit declaration ("no formal REQUIREMENTS.md IDs yet, carries forward Phase 12/13's decision-as-requirement pattern; refined in 14-CONTEXT.md"). `14-CONTEXT.md` defines D-01 through D-08, all of which map cleanly onto D-14-01 (D-01/D-02), D-14-02 (D-03), D-14-03 (D-04/D-06/D-07), and D-14-04 (D-05/D-08) — all confirmed implemented in code as described above. No orphaned requirements found in `REQUIREMENTS.md`'s traceability table for Phase 14 (it only tracks Phases 8-11).

### Deferred Items

None beyond WR-02 (already addressed above as a deliberately-deferred, accurately-tracked code-review finding, not a roadmap-level deferral).

## Human Verification Required

### 1. Stop-mid-run halts the batch loop before every eligible movie is processed

**Test:** Run `cd backend && ./gradlew test --tests "de.moviearchive.admin.WikiReloadControllerTest"` in an environment where Testcontainers can reach Docker (this verification session's sandbox has a Docker-context mismatch — OrbStack socket vs. the `UnixSocketClientProviderStrategy` pinned in `~/.testcontainers.properties` — that prevents the Gradle test JVM from starting Postgres/OpenSearch containers). Alternatively, manually trigger a reload with 3+ movies missing Wikipedia data on a running dev stack, click Stop shortly after, and watch the Settings page.
**Expected:** `shouldHaltBatch_whenStopRequestedMidRun` passes — strictly fewer than all eligible movies end up with a non-null `indexedAt`. On manual test: the per-movie history list stops growing shortly after clicking Stop (not after processing all remaining movies), and clicking "Reload missing Wikipedia data" again resumes processing the remaining movies without hanging.
**Why human:** This is a cancellation invariant (async batch loop breaks at a loop-boundary check under real DB-backed conditions) that only a live multi-movie run can exercise end-to-end. The stop-flag mechanics themselves (`resetRun`/`requestStop`/`isStopRequested`) are unit-tested and pass; only the full-loop integration behavior is unverified in this session due to a sandbox-local Docker configuration issue independently confirmed to affect unrelated, untouched test classes identically.

## Gaps Summary

No gaps. All 7 must-have truths are either fully verified with a passing, independently re-run test, or (in the case of the Stop-mid-run behavior) present and correctly wired per direct code review, with the sole gap being an inability to execute the one integration test that proves the runtime behavior in this specific sandboxed session — a Docker/Testcontainers configuration limitation confirmed to be environment-wide, not a phase-14 code defect. The Critical code-review finding (CR-01) and three of four Warnings (WR-01, WR-03, WR-04/IN-01) are confirmed fixed in the codebase. WR-02 is confirmed deliberately deferred and accurately tracked as a pending todo, matching its description in `14-REVIEW.md` exactly — not a silent regression. One minor documentation-tracking inconsistency was found in `STATE.md`'s Pending Todos list (unrelated to code correctness).

---

_Verified: 2026-08-27T16:09:35Z_
_Verifier: Claude (gsd-verifier)_
