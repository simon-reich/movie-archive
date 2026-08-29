---
phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
reviewed: 2026-08-27T16:00:54Z
depth: standard
files_reviewed: 11
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
  - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
  - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
  - backend/src/main/resources/application.properties
  - backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java
  - backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
  - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
  - frontend/composables/useSettings.ts
  - frontend/pages/settings.vue
  - frontend/test/unit/composables/useSettings.spec.ts
  - frontend/test/unit/pages/settings.spec.ts
findings:
  critical: 1
  warning: 4
  info: 1
  total: 6
status: issues_found
---

# Phase 14: Code Review Report

**Reviewed:** 2026-08-27T16:00:54Z
**Depth:** standard
**Files Reviewed:** 11
**Status:** issues_found

## Summary

Reviewed the wiki batch-reload pacing/cooldown/stop/progress-UI feature end to end: controller
(IDOR-checked ownership, SSE registration, stop endpoint), the SSE progress registry, the
Wikipedia-only batch-retry service (pacing, cooldown, Wikidata SPARQL prefetch, stop-flag
handling), the frontend composable and settings page, and the accompanying tests on both sides.

The IDOR protection (`assertOwnership`), the queue-capacity 503 handling, and the mid-run stop
mechanism are all correctly implemented and covered by integration tests that actually exercise
the concurrency/timing edge cases (`shouldHaltBatch_whenStopRequestedMidRun`,
`shouldReject_whenThirdTriggerExceedsQueueCapacity`).

The main defect is that `WikiReloadService.batchReload()` has no top-level exception handling:
any failure occurring before the per-movie loop (e.g. a transient DB error in
`findEligibleForWikiReload`) leaves the SSE progress stream permanently unresolved for that user
and leaks per-user state in `WikiReloadProgressService`. Several further issues degrade the
accuracy and robustness of the progress UI: the completion log line reports the wrong count on an
early stop, the terminal SSE event cannot distinguish "fully completed" from "stopped early", the
reload trigger button stays clickable while a run is genuinely in progress (letting a user wipe
their own visible history), and the SSE error handler kills the live stream on any transient
network error rather than only fatal ones — a materially bigger risk now that a single run can
legitimately last "many minutes" per this phase's own pacing change.

## Critical Issues

### CR-01: `batchReload()` has no top-level exception handling — a pre-loop failure hangs the SSE stream and leaks per-user state forever

**File:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:155-216`
**Issue:**
`batchReload()` calls `progressService.resetRun(userId)`, then `movieRepository.findEligibleForWikiReload(userId, cutoff)`, before entering the per-movie loop. None of this — nor the loop itself — is wrapped in a try/catch/finally. `progressService.complete(userId)` (the only code path that resolves the SSE stream to a terminal state and clears `stopFlags`/`durationWindowsMs`/`lastKnown` for the user) is only reached via the normal fall-through at the end of the method.

If `findEligibleForWikiReload` throws (DB connectivity blip, query timeout, deadlock — all realistic failure modes this class's own javadoc explicitly worries about for a different reason, "connection-pool exhaustion"), the exception propagates out of the `@Async` method and is swallowed by Spring's default `AsyncUncaughtExceptionHandler` (logged only). Consequences:
- The frontend's SSE connection (opened with `SseEmitter(Long.MAX_VALUE)`, i.e. no timeout) never receives a `complete` event. The settings page is left showing "Reload started…" / an in-progress spinner indefinitely, with no way for the user to tell the run failed.
- `progressService.resetRun(userId)` already put an entry into `stopFlags` for this user; since `complete()` is never called, that entry (and any `durationWindowsMs` entry from a prior run) is never removed — a permanent per-user leak in these `ConcurrentHashMap`s.
- The single global `wikiReloadExecutor` (core=1/max=1) is freed (the thread returns), so subsequent triggers for other/this user aren't blocked — but this user's UI is stuck regardless.

Per-movie failures inside the loop are correctly isolated (`doRetryWikipedia`'s internal try/catch, plus the loop's own try/catch around `self.retryWikipedia(...)`), but the setup code before the loop has no equivalent safety net.

**Fix:**
```java
@Async("wikiReloadExecutor")
public void batchReload(UUID userId) {
    progressService.resetRun(userId);
    try {
        Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
        List<Movie> eligible = movieRepository.findEligibleForWikiReload(userId, cutoff);
        log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligible.size());

        List<String> imdbIds = eligible.stream()
                .map(Movie::getImdbId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> resolvedTitles = wikipediaClient.resolveViaWikidataSparql(imdbIds);

        int processedCount = 0;
        for (int i = 0; i < eligible.size(); i++) {
            // ... unchanged loop body ...
        }
    } catch (Exception e) {
        log.error("Wiki batch-reload: fatal error before/outside per-movie loop for userId={}: {}",
                userId, e.getMessage(), e);
    } finally {
        progressService.complete(userId);
    }
    log.info("Wiki batch-reload complete userId={}", userId);
}
```
(Moving the existing `return` paths' `progressService.complete(userId)` calls into a single `finally` also fixes CR-01 and simplifies the interrupted-sleep early-return path.)

## Warnings

### WR-01: Completion log line reports the wrong processed count on an early stop

**File:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:214-215`
**Issue:** After the loop (which may `break` early on a stop request), the code logs:
```java
progressService.complete(userId);
log.info("Wiki batch-reload complete userId={} processed={}", userId, eligible.size());
```
`eligible.size()` is the *total* eligible count computed before the loop, not the number of movies
actually processed (`processedCount`). When a run is stopped mid-way (see
`shouldHaltBatch_whenStopRequestedMidRun`), this log line still claims the full `eligible.size()`
was processed, which is factually wrong and misleading for anyone debugging from logs.
**Fix:**
```java
log.info("Wiki batch-reload complete userId={} processed={} eligible={}", userId, processedCount, eligible.size());
```

### WR-02: Terminal SSE event cannot distinguish "fully completed" from "stopped early"

**File:** `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java:107-136`
**Issue:** `complete()` always constructs `new ProgressState(total, total, true, ...)` using the
last-known `total`, regardless of whether the run actually processed all `total` movies or was
halted early via the stop endpoint. `ProgressState` has no field indicating "stopped" vs
"finished". Combined with `settings.vue`'s `v-if="wikiProgress && !wikiProgress.complete"` guard
(which hides the processed/total counter and per-movie history list entirely once `complete` is
true), a user who stops a run mid-way sees the progress panel simply vanish with no confirmation
of how far it actually got, and no way to tell "it finished all 40 movies" from "it stopped after
3 of 40".
**Fix:** Add a `stopped` (or `reason`) field to `ProgressState`, e.g.:
```java
public record ProgressState(int processed, int total, boolean complete, boolean stopped,
                             String lastMovieTitle, String lastMovieStatus, long etaSeconds) { }
```
and have `complete()` report the real `prior.processed()` instead of always reporting `total`,
setting `stopped = progressService.isStopRequested(userId)` (checked before `stopFlags` is
cleared) or by having `WikiReloadService` pass an explicit "was stopped" flag into `complete()`.

### WR-03: "Reload" trigger button stays clickable while a run is genuinely in progress, and clicking it wipes the in-progress history

**File:** `frontend/pages/settings.vue:216-232, 465-467`
**Issue:** The trigger button's disabled state is `:disabled="wikiReloadTriggering"`, which is
only `true` for the duration of the `POST /wiki-reload/{userId}` HTTP round-trip (milliseconds),
not for the duration of the actual (potentially many-minutes-long) batch run. Once that POST
resolves, the button re-enables even though `wikiProgress.value` shows an active, non-complete
run (the Stop button, gated on `wikiProgress && !wikiProgress.complete`, correctly stays visible
during this time — the Reload button does not mirror that gating).

Worse, `onTriggerWikiReload()` unconditionally clears history on any `'started'` result:
```js
const result = await triggerWikiReload()
if (result === 'started') {
  wikiMovieHistory.value = []
}
```
Since the backend's `wikiReloadExecutor` queue accepts a second trigger for the same user while
the first is still running (queueCapacity=1, confirmed by
`shouldQueueSecondTrigger_whileFirstRunInProgress`), a user who double-clicks "Reload missing
Wikipedia data" while a run is already active gets a second `202 started` response, which
immediately wipes the currently-displayed (still-updating) history of the *first, still-running*
batch from the UI — the visible progress panel silently loses its history mid-run, even though
the backend continues fine.
**Fix:** Gate the trigger button on live progress state too, and only clear history when no run
is currently active:
```html
<ButtonPrimary
  type="button"
  :loading="wikiReloadTriggering"
  :disabled="wikiReloadTriggering || (wikiProgress && !wikiProgress.complete)"
  @click="onTriggerWikiReload"
>
```
```js
if (result === 'started' && (!wikiProgress.value || wikiProgress.value.complete)) {
  wikiMovieHistory.value = []
}
```

### WR-04: SSE `onerror` throws unconditionally, killing the live progress stream on any transient error, not just fatal ones

**File:** `frontend/composables/useSettings.ts:78-81`
**Issue:**
```js
onerror(err) {
  // Stop the library's default retry-forever behavior on a fatal error (e.g. 403/404)
  throw err
},
```
The comment says this is meant to stop retries on a *fatal* error (403/404), but the
implementation throws for every error `fetch-event-source` reports, including transient network
drops. Since throwing from `onerror` aborts the library's automatic retry entirely, a single
transient blip permanently ends the live progress subscription for the rest of that browser
session — the batch keeps running server-side, but the user's progress bar/history simply stops
updating with no visible indication that the connection was lost. This risk is materially larger
in this phase than before: `wiki.retry.pacing-delay-ms` now defaults to 30000ms and the
controller's own javadoc states a real run "can now run for many minutes", so the exposure window
to a transient drop is much longer than the pre-phase behavior.
**Fix:** Only rethrow (abort) for genuinely fatal statuses; let other errors fall through to the
library's default retry/backoff:
```js
onerror(err) {
  const status = (err as { status?: number })?.status
  if (status === 403 || status === 404) {
    throw err // fatal — do not retry
  }
  // transient — return undefined to use the library's default retry/backoff
},
```

## Info

### IN-01: `try/catch` around `subscribeToWikiReloadProgress` in `onMounted` is dead-weight — it cannot catch the errors it appears to guard against

**File:** `frontend/pages/settings.vue:117-127`
**Issue:**
```js
try {
  const userId = await getCurrentUserId()
  unsubscribeWikiProgress = subscribeToWikiReloadProgress(userId, (p) => { ... })
} catch {
  // Non-fatal — no live progress stream, page still usable
}
```
`subscribeToWikiReloadProgress` is synchronous (it kicks off `fetchEventSource` without awaiting
it and returns an unsubscribe function immediately); it does not throw synchronously for
connection-level failures — those surface later via the internal `onopen`/`onerror` callbacks
(which, per WR-04, `throw` inside the library's own async machinery, not into this call site).
In practice, this `catch` block can only ever catch an error from the preceding
`await getCurrentUserId()` call, not anything from the SSE subscription itself. The comment
implies broader protection than the code actually provides.
**Fix:** Split the two concerns, or acknowledge in the comment that the catch only covers
`getCurrentUserId()` failures:
```js
try {
  const userId = await getCurrentUserId()
  unsubscribeWikiProgress = subscribeToWikiReloadProgress(userId, (p) => { ... })
} catch {
  // Non-fatal — could not resolve the user id (e.g. /users/me failed); no live
  // progress stream, page still usable. Does NOT cover SSE connection errors,
  // which surface asynchronously via onerror (see WR-04).
}
```

---

_Reviewed: 2026-08-27T16:00:54Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
