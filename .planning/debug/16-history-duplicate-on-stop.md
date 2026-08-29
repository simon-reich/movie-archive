---
status: diagnosed
trigger: "UAT gap G-16-2 (16-UAT.md Test 2): 'beim Stoppen, wenn dann wirklich gestoppt wurde, wird der letzte Eintragfilmtitel nochmal wiederholt. Und quasi in zwei darauf folgenden Zeilen doppelt angezeigt.'"
created: 2026-08-29T16:00:00Z
updated: 2026-08-29T16:40:00Z
---

## Current Focus

hypothesis: CONFIRMED — see Resolution
test: n/a — diagnosis complete, goal is find_root_cause_only
expecting: n/a
next_action: none — hand off to plan-phase --gaps for fix

## Symptoms

expected: When a wiki-reload run is stopped, the per-movie history list shows each processed
  movie exactly once — the last movie processed before Stop took effect must not appear twice
  in two consecutive rows.
actual: The last movie processed before Stop appears twice, in two consecutive rows, in the
  per-movie history list.
errors: None reported.
reproduction: Test 2 in .planning/phases/16-bulk-import-correctness-wiki-reload-progress-clarity/16-UAT.md
  — click "Reload missing Wikipedia data" with more than one eligible movie, let it run, click
  "Stop", wait for the terminal "Stopped at X / Y" panel, inspect the per-movie history list.
started: Discovered during UAT of Phase 16 plan 16-02 (wiki-reload stopped-vs-completed
  distinction), which introduced the `stopped` field on `WikiReloadProgressService.ProgressState`
  and `WikiReloadProgressService.complete()`'s new stop-aware terminal-state construction.

## Eliminated

(none — root cause found on first hypothesis, matching hint (b) in the investigation brief)

## Evidence

- timestamp: 2026-08-29T16:10:00Z
  checked: backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java,
    complete(UUID userId) (lines 182-197)
  found: |
    complete() builds its terminal ProgressState by copying `prior.lastMovieTitle()` and
    `prior.lastMovieStatus()` straight from the LAST-PUBLISHED `progress` state:
      ProgressState prior = lastKnown.get(userId);
      ...
      ProgressState state = new ProgressState(
              prior != null ? prior.processed() : total, total, true,
              prior != null ? prior.lastMovieTitle() : null,
              prior != null ? prior.lastMovieStatus() : null,
              0L, stopped);
    So the terminal "complete" SSE event carries the EXACT SAME non-null lastMovieTitle/
    lastMovieStatus as the immediately-preceding "progress" event, whenever at least one movie
    was processed in the run (stopped or not — this is not stop-specific).
  implication: Two consecutive SSE events (the last movie's "progress" event, then the run's
    "complete" event) both describe the same movie with identical title/status fields.

- timestamp: 2026-08-29T16:15:00Z
  checked: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java,
    batchReload() per-movie loop (lines 212-260) and its finally block (line 264-265)
  found: publish() is called exactly once per processed movie (line 241), with that movie's own
    title/status — no duplicate publish() call exists here. complete(userId) is called exactly
    once, in the finally block, regardless of whether the loop broke early via a Stop request or
    ran to completion.
  implication: The backend is NOT double-publishing per movie. The duplication is not caused by
    an extra progress event for the same movie — it is caused by the terminal event echoing the
    prior event's per-movie fields (confirmed above), which is a distinct mechanism from what the
    hint's option (a) suggested.

- timestamp: 2026-08-29T16:20:00Z
  checked: frontend/composables/useSettings.ts, subscribeToWikiReloadProgress() onmessage handler
    (lines 81-85)
  found: |
    onmessage(ev) {
      if (ev.event === 'progress' || ev.event === 'complete') {
        onProgress(JSON.parse(ev.data) as WikiReloadProgress)
      }
    }
  implication: Both SSE event types are routed to the exact same page-level callback with no
    tag distinguishing which kind of event triggered it — the page callback must self-guard if
    it wants different behavior per event type.

- timestamp: 2026-08-29T16:25:00Z
  checked: frontend/pages/settings.vue, subscribeToWikiReloadProgress callback (lines 136-154)
  found: |
    unsubscribeWikiProgress = subscribeToWikiReloadProgress(userId, (p) => {
      wikiProgress.value = p
      ...
      if (p.lastMovieTitle) {
        wikiMovieHistory.value.push({ title: p.lastMovieTitle, status: p.lastMovieStatus ?? 'FAILED' })
      }
      ...
    })
  implication: The push to `wikiMovieHistory` is unconditional on `p.lastMovieTitle` being
    truthy — it does NOT check `p.complete`. So it fires once for the last movie's "progress"
    event (correct, this is a newly-processed movie) AND fires again for the terminal "complete"
    event that immediately follows it (incorrect — this is not a new movie, it's the same event
    data echoed forward by the backend). This is confirmed hint option (b): "the frontend
    appending the last progress event's entry to history AND then also appending it again from
    the terminal event handler."

- timestamp: 2026-08-29T16:28:00Z
  checked: frontend/pages/settings.vue, grep for all other usages of `lastMovieTitle`/
    `lastMovieStatus` in the file (lines 144-145 are the only occurrences besides the type import)
  found: No other template binding or computed property reads `p.lastMovieTitle`/
    `p.lastMovieStatus` anywhere else in settings.vue. The history-array push at lines 144-146
    is the SOLE consumer of these two fields on the frontend.
  implication: The backend's decision to echo `prior.lastMovieTitle()/lastMovieStatus()` into
    the terminal state has no other frontend beneficiary — it exists purely as state-continuity
    bookkeeping (per the class javadoc's "last-known-state registry" pattern) but this
    unconditional-push consumer treats it as "a new movie was just processed," which is false
    for the terminal event.

- timestamp: 2026-08-29T16:32:00Z
  checked: frontend/test/unit/pages/settings.spec.ts, all `capturedOnProgress?.(...)` call sites
    (lines 146-288)
  found: Every test in the "wiki-reload progress UI (mounted)" describe block calls
    `capturedOnProgress` exactly ONCE per test, with a single isolated event object (either a
    lone "progress"-shaped event or a lone terminal "complete"-shaped event) — never a realistic
    two-event sequence (progress-for-movie-N immediately followed by complete-echoing-movie-N).
    No test asserts on `wikiMovieHistory.length` or on duplicate rows at all.
  implication: This is exactly why the bug was not caught by the existing test suite — the
    real-world failure mode requires observing the effect of TWO sequential events on the SAME
    persistent `wikiMovieHistory` array, which no test exercises. Each isolated-event test can
    only ever produce at most one push, so a duplicate can never surface.

- timestamp: 2026-08-29T16:35:00Z
  checked: whether this reproduces for a genuinely-completed run too (not just Stop), by tracing
    WikiReloadService.batchReload()'s finally-block complete(userId) call for the "ran to the
    natural end" code path vs the "Stop mid-loop" code path
  found: Both code paths call the exact same `progressService.complete(userId)` in the shared
    `finally` block (WikiReloadService.java:264-265) with no branching on how the loop ended;
    `complete()` itself (WikiReloadProgressService.java:182-197) echoes `prior.lastMovieTitle()/
    lastMovieStatus()` unconditionally regardless of the `stopped` value it computes.
  implication: The duplicate-last-row defect is NOT stop-specific — it will reproduce on ANY run
    that processes at least one movie, whether the run is stopped early or reaches its natural
    end. UAT Test 2 happened to catch it via the Stop scenario because that's the scenario it
    tested; UAT Test 1 (which also involves a stopped run) did not specifically check for
    duplicate rows within a single run's history, only for history-clearing behavior across runs,
    so it passing is not a contradiction.

## Resolution

root_cause: |
  Two cooperating defects produce one visible symptom (both required simultaneously — an
  AND-gate case per the RCA branching guidance):

  1. (Backend, code category) `WikiReloadProgressService.complete(UUID userId)` builds its
     terminal "complete" SSE event's `lastMovieTitle`/`lastMovieStatus` fields by copying them
     straight from the immediately-preceding `progress` event's `lastKnown` state (`prior`),
     rather than nulling them out. This means the terminal event always re-describes the same
     movie the last `progress` event already described, whenever at least one movie was
     processed in the run.

  2. (Frontend, code category) `settings.vue`'s SSE handler pushes a new row onto
     `wikiMovieHistory` whenever `p.lastMovieTitle` is truthy, with no guard on `p.complete`.
     Because `useSettings.ts` routes both `progress` and `complete` SSE events through the same
     `onProgress` callback, and both event payloads for the run's last movie carry an identical
     non-null `lastMovieTitle`/`lastMovieStatus` (per defect 1), the callback pushes the same
     movie into history twice: once for the genuine per-movie `progress` event, and once more for
     the terminal `complete` event that merely echoes it forward.

  Either defect alone would be harmless (echoing the field with no consumer, or pushing on every
  event with no duplicate-shaped data to push) — the AND of both produces the visible duplicate
  row. This reproduces on any run that processes >=1 movie, not only a Stop-mid-run; UAT
  happened to surface it via the Stop scenario (Test 2) because that's the tested path.

fix: (not applied — goal is find_root_cause_only; plan-phase --gaps will design and apply the fix)
verification: (not applicable — no fix applied)
files_changed: []
