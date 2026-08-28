---
status: resolved
trigger: "After stopping a wiki-reload batch and immediately starting a new one, the Settings page shows NO progress bar and NO Stop button for a period after the new run starts — the user cannot tell whether a batch is running or stop it. Observed live during manual verification of the sse-auth-denied-on-complete fix (2026-08-28)."
created: 2026-08-28
updated: 2026-08-29
---

## Symptoms

expected: After clicking "Reload missing Wikipedia data" for a second time (shortly after a prior run finished/was stopped), the progress panel and Stop button appear promptly and reflect the new run's live progress.

actual: After the second click, neither the progress panel nor the Stop button render at all. The user reported being "completely in the dark" — no visibility into whether anything is running, and no way to stop it. Backend logs confirm the batch IS actually running (regular "Wiki retry succeeded" lines at the expected pacing cadence) despite the frontend showing nothing. The user clicked "Reload" 5 times over about 90 seconds (23:16:43, 23:16:56, 23:16:58, 23:17:00, 23:18:04) presumably because each click appeared to do nothing.

errors: None thrown/logged — this is a silent state-sync gap, not an exception.

timeline: First observed 2026-08-28 during live manual verification of the sse-auth-denied-on-complete debug session's fix (unrelated bug — that one IS confirmed fixed, no AuthorizationDeniedException on stop). This is a separate, newly discovered bug in the same feature area (wiki-reload SSE progress).

reproduction: |
  1. Start a wiki-reload batch from Settings page, let it run a bit.
  2. Click "Stop" — batch stops cleanly, progress panel and Stop button correctly disappear.
  3. Immediately click "Reload missing Wikipedia data" again to start a new batch.
  4. Observe: no progress panel, no Stop button appear, even though backend logs show the new batch is actively processing movies.

## Current Focus

hypothesis: |
  WikiReloadProgressService.complete(userId) (backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java)
  calls emitter.complete() on every registered SSE emitter for userId when a run finishes/stops,
  THEN removes the emitter list from the `emitters` map entirely (see the tail of complete(),
  after the shown broadcast loop — emitters.remove(userId); lastKnown.remove(userId); etc.).
  This tears down the live SSE connection the frontend's fetchEventSource() call is using.

  frontend/composables/useSettings.ts#subscribeToWikiReloadProgress() has no onclose handler —
  it only handles onopen (fatal 403/404 check), onmessage, and onerror (which only intervenes for
  403/404). When the SERVER cleanly closes the stream (via emitter.complete()), fetch-event-source's
  default behavior is to auto-retry/reconnect (since nothing in onerror/onopen aborts it for a
  clean server-side close) — but that reconnect is not instantaneous and its timing is not
  explicitly controlled here.

  If the user clicks "Reload" again during that reconnect window (before the new EventSource has
  re-registered via WikiReloadController#progress -> progressService.register()), the new run's
  progressService.start(userId, eligibleCount) and subsequent publish() calls broadcast to
  `emitters.get(userId)` — which is empty/null during the gap, so broadcast() silently no-ops
  (see broadcast(): "if (userEmitters == null) return;"). The new state IS stored in `lastKnown`,
  but the frontend's wikiProgress ref is never updated until the reconnect actually completes AND
  either (a) a subsequent publish() event arrives while re-subscribed, or (b) register()'s
  replay-last-known-state logic fires on the (now-reconnected) emitter. Until then, the client's
  wikiProgress stays stuck at the PRIOR run's terminal {complete: true} state, so the
  v-if="wikiProgress && !wikiProgress.complete" guards on both the progress panel AND the Stop
  button (frontend/pages/settings.vue) render nothing — and the trigger button's :disabled guard
  (same condition, inverted) stays enabled, letting the user click Start again repeatedly during
  the blind window, compounding into multiple overlapping trigger requests.
test: "Reproduce via MockMvc/integration-style test: register an emitter, call progressService.complete(userId) (which removes the emitter), THEN call progressService.start(userId, N) BEFORE re-registering a new emitter for userId — assert the broadcast is silently dropped (no exception, but no emitter receives it) and that lastKnown still reflects the new run's state once queried directly. Separately, a frontend test asserting subscribeToWikiReloadProgress reconnects and picks up the current lastKnown state after a server-initiated close (may need to inspect fetch-event-source's exposed reconnect/onclose hooks — check its API surface for whether an explicit onclose handler exists to trigger controlled behavior, e.g. immediate re-subscribe or at least a synthetic loading state, rather than relying on default backoff)."
expecting: "Confirms a real gap between run N's SSE teardown and run N+1's SSE re-registration during which broadcasts are dropped and the frontend has no visibility into an active run."
next_action: "Resolved. User confirmed fixed live in browser (2026-08-29): stopped and immediately restarted a wiki-reload batch multiple times; progress panel and Stop button appeared correctly every time with no page refresh. Session archived."
reasoning_checkpoint: |
  hypothesis: "WikiReloadProgressService.complete(userId) calls emitter.complete() and removes
    the userId entry from the `emitters` registry at the end of EVERY batchReload run (both
    natural completion and Stop). @microsoft/fetch-event-source (the actual installed version's
    source, confirmed by reading lib/esm/fetch.js) does NOT reconnect after a clean
    server-initiated stream close -- only after a thrown/network error. Since settings.vue
    subscribes exactly ONCE per page mount (onMounted), the page's single SSE connection is
    permanently destroyed the moment the FIRST run ends. Any subsequent run in the same page
    session broadcasts into an empty/missing emitter list (broadcast()'s `if (userEmitters ==
    null) return;`), so the frontend's wikiProgress ref never updates again -- explaining the
    exact reproduction (Stop, then Reload immediately -> permanently no progress panel/Stop
    button until a hard page reload)."
  confirming_evidence:
    - "Read installed fetch-event-source@2.0.1 source directly: clean stream end takes the
      onclose()/dispose()/resolve() path with zero retry scheduling; retry only happens in the
      catch block on a thrown error."
    - "Read WikiReloadProgressService.complete(): calls emitter.complete() then
      emitters.remove(userId) unconditionally."
    - "Read WikiReloadService.batchReload(): finally { progressService.complete(userId); } runs
      on every single run, success or Stop, no exception."
    - "Read settings.vue onMounted/onUnmounted: subscribeToWikiReloadProgress is called exactly
      once per mount; nothing re-subscribes on trigger or on stream close."
  falsification_test: "If fetch-event-source DID reconnect automatically after a clean close (it
    doesn't, per source), or if settings.vue re-subscribed per trigger click (it doesn't), this
    hypothesis would be wrong. Both were checked directly against source, not assumed."
  fix_rationale: "Root cause is the registry being torn down on a per-RUN basis when its actual
    scope is per-USER-PAGE-SESSION (one subscription covers all future runs). The fix keeps the
    emitter registered and the connection open across runs -- complete() still broadcasts the
    terminal state (so the UI correctly hides the progress panel/Stop button when a run ends)
    but no longer severs the transport. This addresses the mechanism directly, not just the
    symptom (e.g. a client-side optimistic-state patch would paper over this specific
    reproduction but leave the connection dead for every OTHER future event too)."
  blind_spots: "Have not yet tested actual browser fetch() ReadableStream behavior end-to-end
    (relying on reading the library source, which is deterministic and unambiguous, not
    behavioral guesswork) or confirmed emitter.complete() in Spring's SseEmitter definitely ends
    the underlying response stream cleanly rather than erroring -- will confirm via updated unit
    tests plus the human-verify checkpoint in the real browser."
  candidate_causes:
    - "code: WikiReloadProgressService.complete() closes+evicts the emitter registry entry
      every run (backend service logic)"
    - "config/design: the registry is keyed per-userId (page-lifetime scope) while its
      lifecycle management assumes per-run scope, copied from BulkImportProgressService's
      genuinely-per-batch-id model (architectural mismatch, not merely a typo-level code bug)"
  and_gate: "no -- a single code change (stop closing/evicting on complete()) fully resolves the
    mechanism; the second candidate cause is the SAME root design mismatch described from a
    different angle, not an independent contributing condition that must co-occur."
tdd_checkpoint: ""

## Evidence

- timestamp: 2026-08-28
  checked: frontend/pages/settings.vue onMounted/onUnmounted + useSettings.ts#subscribeToWikiReloadProgress
  found: |
    The SSE subscription is established EXACTLY ONCE per page mount (inside onMounted), not
    re-established per trigger click. unsubscribeWikiProgress is only ever called from
    onUnmounted. There is no re-subscribe logic anywhere tied to onTriggerWikiReload or to a
    stream close.
  implication: |
    Whatever kills that single long-lived connection kills live progress for the REST of the
    page session, not just for a transient window — unless the library reconnects on its own.

- timestamp: 2026-08-28
  checked: node_modules/.pnpm/@microsoft+fetch-event-source@2.0.1/.../lib/esm/fetch.js (installed
    version's actual source, not docs/memory)
  found: |
    fetchEventSource()'s create() function: after getBytes(response.body, ...) returns
    NORMALLY (i.e. the server ends the stream cleanly, no thrown error), the code path is
    `onclose?.(); dispose(); resolve();` — dispose() clears the retry timer and aborts the
    controller, and resolve() finishes the OUTER promise. Retry/reconnect (`setTimeout(create,
    interval)`) only happens inside the `catch` block, i.e. only on a THROWN error (network
    failure, or onopen/onerror throwing). A clean server-initiated stream end is NOT an error
    path in this library and triggers NO reconnect at all.
  implication: |
    Contradicts the original hypothesis's "reconnect window (self-healing, just delayed)"
    framing. There is no reconnect after a clean close — the subscription dies PERMANENTLY.
    useSettings.ts's subscribeToWikiReloadProgress() passes no onclose handler, so this is the
    exact code path hit whenever the backend cleanly closes the stream.

- timestamp: 2026-08-28
  checked: backend WikiReloadProgressService.complete(UUID) (full method body)
  found: |
    complete(userId) sends the terminal "complete" event to every currently-registered emitter,
    then calls emitter.complete() on each one (ending the underlying SSE HTTP response/stream),
    THEN does `emitters.remove(userId); lastKnown.remove(userId); stopFlags.remove(userId);
    durationWindowsMs.remove(userId);` — i.e. the emitter registry entry for userId is deleted
    entirely, not just marked idle.
  implication: |
    Combined with the fetch-event-source finding above: calling emitter.complete() here
    triggers a CLEAN close of the client's fetch response stream -> getBytes() returns
    normally -> fetchEventSource's create() takes the no-reconnect resolve() path. The single
    page-lifetime subscription from onMounted is gone for good after this call.

- timestamp: 2026-08-28
  checked: backend WikiReloadService.batchReload(UUID) — the try/finally wrapping the whole method
  found: |
    `finally { progressService.complete(userId); }` runs unconditionally at the end of EVERY
    batchReload invocation — whether the run finished naturally (all eligible movies processed)
    OR was halted early via a Stop click (the `break chunks` paths). There is no code path that
    completes a run without hitting this.
  implication: |
    Every single run — the very first one included — ends by permanently killing the page's
    one SSE connection. The 2nd+ run in a page session (exactly the reproduction steps: Stop
    then immediately Reload again) has NO live subscription at all: WikiReloadProgressService's
    broadcast(userId, ...) finds `emitters.get(userId) == null` (removed by the prior
    complete()) and silently no-ops (see broadcast()'s early return). The frontend's
    wikiProgress ref is frozen at the prior run's terminal {complete:true} state for the rest
    of the page session — not a transient blind window, a PERMANENT one until a hard page
    reload remounts settings.vue and re-subscribes.

- timestamp: 2026-08-28
  checked: backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
  found: |
    Two existing unit tests encode exactly this buggy behavior as intended/expected:
    `publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andCompletesEmitter` asserts
    `verify(emitter, times(1)).complete();`, and
    `register_afterComplete_getsSyntheticCompleteFallback_notReplayOfRealCompletion` explicitly
    asserts that lastKnown is evicted by complete() (a fresh register() after complete() must
    see the synthetic (0,0,true) fallback, NOT a replay of the real terminal state) —
    docstring literally says "proving eviction rather than a stale replay."
  implication: |
    The buggy "close + evict on every run completion" behavior was a deliberate, tested design
    choice (likely copied from BulkImportProgressService's per-batch-id-scoped emitter model,
    per this class's own javadoc: "structurally cloned from BulkImportProgressService"). That
    model is correct for bulk-import (each batch has ITS OWN id and the frontend subscribes
    per-batch), but wrong here: wiki-reload's registry is keyed on userId (page-lifetime scope,
    one subscription per page mount covering ALL future runs), not per-run. These two tests
    will need to be rewritten to match the corrected per-user-persistent-connection design.

## Eliminated

## Resolution

root_cause: |
  WikiReloadProgressService.complete(userId) calls emitter.complete() on every registered SSE
  emitter and then unconditionally removes the userId entry from the `emitters` registry map,
  at the end of EVERY batchReload run (success or Stop, via a finally block). The frontend
  (settings.vue) opens exactly one fetchEventSource() SSE subscription per page mount and never
  re-subscribes. @microsoft/fetch-event-source (confirmed by reading the installed v2.0.1
  source) does not auto-reconnect after a clean server-initiated stream close -- reconnection
  only happens on a thrown/network error. So the first run's completion permanently kills the
  page's only SSE connection; every subsequent run's start()/publish() broadcasts silently
  no-op (broadcast() returns early when `emitters.get(userId)` is null), leaving the frontend's
  wikiProgress stuck at the prior terminal state -- no progress panel, no Stop button -- for the
  rest of the page session. Architectural root: the registry is keyed per-userId (page-lifetime
  scope, meant to carry ALL future runs) but its cleanup logic (copied from the
  per-batch-id-scoped BulkImportProgressService) assumes per-run scope.
fix: |
  backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java — complete(userId)
  no longer calls emitter.complete() on registered emitters, and no longer removes the userId
  entry from `emitters` or `lastKnown`. It still computes and stores the terminal
  {complete:true} ProgressState and broadcasts it to every currently-registered emitter (so the
  UI still correctly hides the progress panel/Stop button when a run ends) — it just no longer
  severs the transport. stopFlags/durationWindowsMs (genuinely per-RUN state) are still cleared.
  The `emitters` registry entry is now only ever cleaned up by an actual client disconnect
  (register()'s onCompletion/onTimeout wiring, or a failed send in sendEvent()/removeEmitter()),
  matching the registry's real per-userId/page-lifetime scope rather than the per-run scope it
  was incorrectly modeled on (copied from BulkImportProgressService, which genuinely IS
  per-batch-id-scoped). Updated class + method javadoc to document the corrected lifecycle and
  the incident. Two pre-existing unit tests that pinned the old (buggy) behavior were rewritten
  to assert the corrected behavior, and one new test directly reproduces the bug scenario (two
  runs over a single register() call, asserting all 7 events reach the one persistent emitter
  and it is never closed).
verification: |
  target_test: { result: pass } — new driving test
  secondRun_afterComplete_broadcastsToStillRegisteredEmitter_noReReg (WikiReloadProgressServiceTest)
  reproduces the exact bug scenario (one register() call, two runs' worth of start/publish/complete)
  and passes against the fixed code.
  mutation_check: { result: skipped, reason: "no PITest/Stryker mutation-testing tool configured
  in build.gradle.kts for this Java backend" }
  no_op_deletion: { result: flagged, deletion_justified_by_rca: true } — the diff removes
  emitter.complete()/emitters.remove()/lastKnown.remove() calls from complete(), which is itself
  the root cause identified via the reasoning_checkpoint RCA (those exact lines are what
  permanently killed the page's SSE subscription); broadcast/lastKnown-state-update/stopFlags
  and durationWindowsMs cleanup logic is preserved unchanged, and new test coverage plus
  extensive javadoc were added alongside the deletion — this is a targeted root-cause-level
  removal, not a symptom-hiding no-op.
  adjacent_tests: { result: pass, suites_run: [WikiReloadProgressServiceTest (11 tests, all
  pass), WikiReloadServiceTest (uses a mocked progressService — unaffected, all pass)] }.
  WikiReloadControllerTest (Testcontainers/Docker-backed, exercises the real HTTP SSE endpoint)
  could not run in this sandbox — initializationError from DockerClientProviderStrategy (no
  /var/run/docker.sock reachable even with sandbox disabled); confirmed this is a pre-existing
  environment limitation by observing the SAME initializationError across 60 other
  Testcontainers-based tests unrelated to this change (SearchControllerTest, UserControllerTest,
  SettingsIntegrationTest, etc.) in a full `./gradlew test` run — not something this fix broke.
  Logged as skipped for this signal; recommend running WikiReloadControllerTest in a
  Docker-enabled environment before merge as a follow-up check.
  revert_and_reconfirm: { result: pass, bug_returned_on_revert: true, fixed_on_reapply: true } —
  `git diff` of the fix isolated to WikiReloadProgressService.java, reverted via `git apply -R`;
  the driving test then failed with TooFewActualInvocations (proving the old code drops run-2
  broadcasts, i.e. the bug reproduces); reapplied via `git apply`, driving test passed again.
  guardrail_verdict: accepted
files_changed:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
  - backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java
