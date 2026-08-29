---
phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
plan: 02
subsystem: wiki-reload-progress-eta
tags: [sse, spring-boot, vue, wikipedia, batch-reload, eta, rolling-average]
status: complete

dependency-graph:
  requires:
    - WikiReloadProgressService (SSE registry + stop-flag component, from 14-01)
    - WikiReloadService.WikiRetryOutcome (SUCCESS/NOT_FOUND/FAILED classification, from 14-01)
  provides:
    - WikiReloadProgressService.ProgressState.etaSeconds field
    - WikiReloadProgressService's 5-entry rolling duration window (durationWindowsMs)
    - settings.vue's wikiEtaLabel computed
  affects:
    - frontend/pages/settings.vue (#wikipedia-data progress block)
    - frontend/composables/useSettings.ts (WikiReloadProgress interface)

tech-stack:
  added: []
  patterns:
    - "Fixed-size Deque<Long>-per-userId rolling window (cap 5), evicted from the front on overflow, averaged on each publish() call"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
    - backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
    - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
    - frontend/composables/useSettings.ts
    - frontend/pages/settings.vue
    - frontend/test/unit/pages/settings.spec.ts

decisions:
  - "etaSeconds computed as round(rollingAverageMs * (total - processed) / 1000.0), using a 5-entry Deque<Long>-per-userId window (RESEARCH.md Open Question 2's recommended default)"
  - "publish() now returns the constructed ProgressState (previously void) — a purely additive signature change letting tests assert etaSeconds directly instead of mocking an SseEmitter for every case"
  - "Duration is measured as wall-clock time around self.retryWikipedia() in batchReload()'s loop, capturing any active 429-backoff wait automatically since that wait happens synchronously inside the same call (D-07)"
  - "register()'s synthetic-complete fallback and complete()'s terminal state both zero-fill etaSeconds (0L) — no ETA makes sense once a run is done or before it starts"
  - "durationWindowsMs entry evicted inside complete() alongside emitters/lastKnown/stopFlags, preventing unbounded growth across repeated runs"
  - "wikiEtaLabel renders nothing (empty string, v-if gated) for etaSeconds 0/null/undefined; formats as \"~X min remaining\" at/above 60s, \"~Xs remaining\" below 60s"

metrics:
  duration: ~35m
  completed: 2026-08-27

actuals:
  tokens: 4756
  tasks: 2
  commits: 2
---

# Phase 14 Plan 02: Wiki Batch-Reload Rolling-Average ETA Summary

Added a live, per-movie-duration-driven ETA to the wiki batch-reload progress UI — a 5-entry rolling average of real per-movie call durations (including any active 429-backoff wait) times the remaining-movie count, replacing the fixed pacing-delay-based guess 14-01-PLAN.md deferred (D-07).

## What Was Built

**Task 1 (backend — rolling-average ETA calculation):**
- `WikiReloadProgressService.ProgressState` grew a 6th field, `long etaSeconds`. `register()`'s synthetic-complete fallback and `complete()`'s terminal-state construction both zero-fill it (`0L`) — there's no meaningful ETA when no run is active.
- New `Map<UUID, Deque<Long>> durationWindowsMs` field + `ETA_WINDOW_SIZE = 5` named constant: a fixed-size rolling window of the last 5 per-movie call durations per user. `complete()` evicts a user's window entry alongside the existing `emitters`/`lastKnown`/`stopFlags` eviction, preventing unbounded growth across repeated runs.
- `publish(...)` gained a `long durationMs` parameter (the just-completed movie's wall-clock call duration). Before constructing the new `ProgressState`, it pushes `durationMs` onto the user's deque, evicts from the front once the window exceeds 5 entries, computes the window's average, and sets `etaSeconds = round(average * (total - processed) / 1000.0)`. `publish()` now also **returns** the constructed `ProgressState` (previously `void`) — a purely additive signature change that let the new tests assert `etaSeconds` directly without mocking an `SseEmitter`.
- `WikiReloadService.batchReload()`'s per-movie loop now measures wall-clock duration around the existing `self.retryWikipedia(movie, resolvedTitles)` call (`System.currentTimeMillis()` before/after) and passes the measured `durationMs` into the now-6-argument `publish(...)` call. Since an active 429 backoff wait happens synchronously inside that same call, the measured duration automatically captures it (D-07's explicit requirement).
- New tests: `publish_computesEtaSeconds_asRollingAverageTimesRemaining` (asserts the window-of-3 math for a 5-movie run) and `publish_windowCapsAtFiveEntries` (asserts a 6th publish evicts the oldest of 5 windowed durations, proving the cap — not a naive all-6-average). `WikiReloadServiceTest` now injects a Mockito-mocked `WikiReloadProgressService` (previously a real instance) and adds `shouldPublishProgressWithNonNegativeDuration_forSuccessfullyProcessedMovie`, asserting `publish(...)` is called with a `durationMs >= 0` via `longThat(...)`.

**Task 2 (frontend — ETA label rendering):**
- `useSettings.ts`'s `WikiReloadProgress` interface gained `etaSeconds: number`, matching the backend's 6th `ProgressState` field.
- `settings.vue`'s new `wikiEtaLabel` computed formats `wikiProgress.value?.etaSeconds`: `0`/`null`/absent → empty string (renders nothing, `v-if` gated); `>= 60` → `` `~${Math.ceil(etaSeconds / 60)} min remaining` ``; `< 60` (and `> 0`) → `` `~${etaSeconds}s remaining` ``. Rendered as a `<p class="text-sm text-muted-foreground">` immediately below the existing processed/total `<p>` inside the `data-testid="wiki-reload-progress"` block.
- 3 new mount-based tests in `settings.spec.ts` extending the 14-01-PLAN.md progress tests: `etaSeconds: 240` → asserts `~4 min remaining`; `etaSeconds: 45` → asserts `~45s remaining`; `etaSeconds: 0` → asserts neither label variant renders. Existing progress-event test payloads were updated to include `etaSeconds` (TypeScript interface now requires it).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `argThat` with a primitive `long` parameter caused a NullPointerException that corrupted Mockito's matcher stack for later tests**
- **Found during:** Task 1, writing `shouldPublishProgressWithNonNegativeDuration_forSuccessfullyProcessedMovie`.
- **Issue:** `argThat(d -> d >= 0)` against `publish(...)`'s primitive `long durationMs` parameter returns a boxed `Long` matcher placeholder that Java auto-unboxes to `long` at the call site — since `argThat`'s placeholder return value is `null`, unboxing threw an NPE. The NPE left an incomplete matcher on Mockito's internal matcher stack, which then caused `UnfinishedVerificationException`/`InvalidUseOfMatchersException` failures in two unrelated, subsequently-run tests in the same class (`shouldSetTimestampOnly_whenWikipediaNotFound`, `shouldNotSetTimestamp_onGenericTechnicalFailure`).
- **Fix:** Used Mockito's `longThat(...)` (the primitive-`long`-safe matcher variant, which returns `0L` instead of a boxed `null`) instead of `argThat(...)`.
- **Files modified:** `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java`.
- **Commit:** e628d06.

None otherwise — plan executed exactly as written.

## Verification

- `./gradlew test --tests "de.moviearchive.admin.WikiReloadProgressServiceTest" --tests "de.moviearchive.movie.WikiReloadServiceTest"` — 15/15 pass (Task 1's exact `<verify>` command, re-confirmed after Task 2 changes too).
- `pnpm test -- settings` (full frontend suite, since Vitest's `-- settings` argument only filters the *displayed* summary, not the run) — 183/183 pass, including `settings.spec.ts`'s 15 tests (12 pre-existing + 3 new ETA tests).
- `pnpm lint` — 1 pre-existing, unrelated error in `frontend/pages/movies/[id].vue` (an unused import), confirmed untouched by this plan's `git status --short`; out of scope per the plan's own scope boundary (already documented as pre-existing in 14-01-SUMMARY.md).
- Full backend suite (`./gradlew test --tests "de.moviearchive.admin.*" --tests "de.moviearchive.enrichment.*" --tests "de.moviearchive.movie.WikiReload*"`) surfaced 6 unrelated Testcontainers/Docker-strategy failures (`ReindexControllerTest`, `WikiReloadControllerTest`, `WikiReloadServiceIntegrationTest`) — consistent with the sandboxed environment's documented Testcontainers resource-contention limitation from 14-01-SUMMARY.md, not a regression from this plan (none of the failing classes were touched by either task).
- Manual dev-environment confirmation (trigger a reload, observe the ETA label live-update, confirm it increases during a real 429 backoff) was **not** performed in this sandboxed session — no way to drive a live browser session against a running dev stack here. The unit + mount-based test coverage above (rolling-average math, window-cap eviction, minutes/seconds/zero label formatting) is the verification actually exercised.

## Self-Check: PASSED

Verified both modified backend files and all three modified frontend files exist on disk, and both task commits (`e628d06`, `c448a7a`) exist in `git log`.
