# Phase 14: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI - Research

**Researched:** 2026-08-27
**Domain:** Backend pacing/cooldown-flag correctness + SSE progress streaming (Spring Boot + Nuxt/Vue), extending three existing, already-shipped patterns — no new libraries.
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Pacing — the between-movie knob, not the within-movie knob**
- **D-01:** Raise `wiki.retry.pacing-delay-ms` (the `Thread.sleep` in `WikiReloadService.batchReload()`'s per-movie loop) from 1000ms toward **~30s**. `wikipedia.request-pacing-ms` (paced before each of `WikipediaClient`'s ~4 outbound calls per movie in the common Wikidata-hit case — `sections` + up to 3 `fetchSection` calls for summary/plot/critics) stays unchanged at 1000ms. Rationale confirmed during discussion: raising the *per-call* knob to 30s would make each movie take 4×30s instead of the intended "30s between movies, fast within a movie" — the ROADMAP's "default raised toward ~30s" language refers to `pacing-delay-ms`, not `request-pacing-ms`. — **Reversibility:** reversible — a config default change, no code-shape impact.
- **D-02:** The existing reactive 429-backoff (`WikipediaClient.recordRateLimited`/`backoffUntil`, shared across all requests) stays exactly as-is as the fallback safety net for whenever a movie falls through Wikidata into the candidate-URL cascade (more calls, higher 429 risk) or the proactive 30s pacing isn't enough for some other reason. Not a replacement of one mechanism by the other — complementary layers.

**Cooldown-marking fix — genuine vs. technical failure**
- **D-03:** `wikiLastAttemptedAt` is set on: (a) success (page found), and (b) `WikipediaNotFoundException` (a genuine "no Wikipedia page exists" result — the fallback cascade was fully exhausted with no hit). It is **NOT** set on any other exception path (429/rate-limit via `recordRateLimited`, network errors, timeouts, or any other technical failure) — those movies remain immediately eligible for the next batch-reload run rather than being cooldown-blocked for `wiki.retry.cooldown-days`. — **Reversibility:** reversible — a conditional-set change inside `doRetryWikipedia()`, no schema/contract impact.

**Progress UI — SSE, reused pattern**
- **D-04:** Reuse the `BulkImportProgressService` pattern (in-memory `Map<UUID, List<SseEmitter>>` + last-known-state registry) for wiki-reload: a new `WikiReloadProgressService` (or equivalent) tracks per-run state and streams SSE events, consistent with this app's single-instance, no-queue-infrastructure architecture (CLAUDE.md's "Async: `@Async` + `@Retryable` (no queue infrastructure)" constraint). — **Reversibility:** reversible — new component, no changes to existing bulk-import progress code.
- **D-05:** The progress UI lives **inline in `settings.vue`**, directly under the existing "Reload missing Wikipedia data" trigger button — no navigation to a separate page. — **Reversibility:** reversible.
- **D-06:** Live progress shows a **per-movie list** (title + success/fail status) as movies are processed, not just an aggregate count — same level of detail as the bulk-import results view, adapted to this settings-page context.
- **D-07:** ETA is computed from remaining-count × a **rolling live average** of actual per-movie call duration (including any time spent in an active 429 backoff), not a fixed estimate based on the configured pacing delay alone — more accurate when backoff kicks in.

**Stop/Start control**
- **D-08:** "Stop" performs a **clean interrupt after the currently-processing movie completes** — the stop flag is checked between movies (same point where the pacing sleep happens today), never a hard `Thread.interrupt()` mid-HTTP-call, to avoid an aborted fetch mid-flight or an inconsistent partial save.
- **D-09:** There is no dedicated "Resume" concept or server-side resume state — a stopped run is simply started again via the same trigger ("Start", not "Resume"). The existing cooldown-eligibility query (`findEligibleForWikiReload`) naturally picks up exactly where a stopped run left off: movies already processed (success or genuine-not-found, per D-03) have a fresh `wikiLastAttemptedAt` and drop out of eligibility; movies not yet reached remain eligible. — **Reversibility:** reversible — no new state to design around; relies entirely on existing cooldown-filter behavior confirmed correct by D-03.

**Explicit out-of-scope:** `wikipedia.request-pacing-ms` (the pacing between the ~4 individual API calls a single movie's article fetch makes) is NOT touched by this phase. Bulk-import's own enrichment pacing is untouched (separate caller, not in scope).

### Claude's Discretion
- Exact SSE event/payload shape for wiki-reload progress (mirror `BulkImportProgressService.ProgressState` record shape, adapted with a per-movie title+status list field).
- Exact window size for the rolling-average ETA calculation (e.g. last N movies) — pick a reasonable default and document it.
- Where the Stop flag lives (e.g. an `AtomicBoolean` keyed by userId/runId in the new progress service, checked by `batchReload()`'s loop) — planner's call.
- Exact naming of the new `WikiReloadProgressService` class/methods and any new SSE endpoint path (e.g. `GET /admin/wiki-reload/{userId}/progress`).

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.

Reviewed but not folded (belong to Phase 15 instead):
- `2026-08-24-support-real-csv-parsing-for-bulk-import` — CSV parsing for bulk import
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — bulk-import batch detail page UI

### Folded Todo
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — confirmed by user as fully covered by this phase's D-04–D-07 progress-UI work; remove from `.planning/STATE.md` Pending Todos once this phase ships.
</user_constraints>

<phase_requirements>
## Phase Requirements

No formal `REQUIREMENTS.md` IDs cover Phase 14 — this phase carries forward Phase 12/13's decision-as-requirement pattern (confirmed by reading `.planning/REQUIREMENTS.md` this session: it covers only `ENRICH-01..05` and `IMPORT-01..07`, all mapped to Phases 8–11, none to Phase 14). The phase-scoping prompt's decision-IDs are used below in place of formal requirement IDs.

| ID | Description | Research Support |
|----|-------------|------------------|
| D-14-01 | Deliberate, env-configurable pacing between movies (raise `wiki.retry.pacing-delay-ms` toward ~30s; leave `wikipedia.request-pacing-ms` untouched) | `application.properties:63-65` already exposes `WIKI_RETRY_PACING_DELAY_MS`; only the numeric default changes — see Code Examples, Pitfall 1 |
| D-14-02 | Cooldown timestamp set only on a genuine, successfully-executed attempt (success or confirmed-empty `WikipediaNotFoundException`), never on a technical/rate-limit failure | Exact bug location identified at `WikiReloadService.java:91` and the 3-location fix scope documented in Code Examples, Pitfall 2 |
| D-14-03 | Progress UI: total targeted, live per-movie progress (title + success/fail), ETA from rolling average | `BulkImportProgressService`/`BulkImportController`/`useBulkImport.ts`/`imports/[batchId].vue` cloned end-to-end — see Architecture Patterns 1-3, Open Question 1 (payload shape for the live list) |
| D-14-04 | Stop/Start control: clean interrupt after current movie, no dedicated resume state | D-08/D-09 mapped to the existing loop boundary (`batchReload()` lines 149-166) and the existing `findEligibleForWikiReload` query — see Anti-Patterns, Pitfall 4 (Stop-flag reset lifecycle) |

</phase_requirements>

## Summary

This phase touches no new technology. It is a targeted extension of three subsystems that already exist and are already covered by tests in this codebase: `WikiReloadService.batchReload()`'s pacing loop, `doRetryWikipedia()`'s unconditional `wikiLastAttemptedAt` write, and the `BulkImportProgressService` SSE pattern (already proven in Phase 11 for bulk-import). Everything the planner needs — the exact line where the bug lives, the exact config property to raise, the exact SSE registry shape to clone, and the exact frontend SSE-consumption idiom to mirror — was confirmed by reading the source files directly this session, not inferred.

The three sub-problems map to three self-contained changes: (1) `wiki.retry.pacing-delay-ms` default in `application.properties` goes from `1000` to `30000` — one line, `WIKIPEDIA_REQUEST_PACING_MS`/`wikipedia.request-pacing-ms` (the within-movie knob) is explicitly untouched per CONTEXT.md D-01; (2) `doRetryWikipedia()`'s `movie.setWikiLastAttemptedAt(Instant.now())` currently fires unconditionally at the top of the method (line 91) before the `try` block — it must move to only fire in the success path and the `WikipediaNotFoundException` catch, never in the generic `catch (Exception e)` block; (3) a new `WikiReloadProgressService` is a near-direct structural clone of `BulkImportProgressService` (same `Map<UUID, List<SseEmitter>>` + `Map<UUID, State>` shape), with a new SSE endpoint on `WikiReloadController`, consumed by a new composable function mirroring `useBulkImport.ts`'s `subscribeToProgress`, rendered inline in `settings.vue` mirroring `imports/[batchId].vue`'s progress-bar/list pattern.

One net-new architectural element has no established in-repo precedent: the Stop flag. No `AtomicBoolean`-based cancellation, `Future.cancel()`, or interrupt-driven stop mechanism exists anywhere else in this codebase — the planner is inventing this pattern for the first time, not reusing one. CONTEXT.md D-08 already specifies its exact shape (checked between movies, at the same point pacing sleep happens today) closely enough that this is low-risk, but the plan should call it out explicitly as new ground rather than "just like X".

**Primary recommendation:** Change 1 line in `application.properties` (D-01), move 1 statement in `WikiReloadService.doRetryWikipedia()` (D-03), and build one new service + one new controller + one new composable + one new UI block, each a structural clone of the already-shipped bulk-import SSE trio (`BulkImportProgressService` / `BulkImportController#progress` / `useBulkImport.ts#subscribeToProgress` / `imports/[batchId].vue`) — plus one genuinely new piece, the Stop flag, which has no precedent to clone and should be planned as net-new.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Between-movie pacing (D-01) | API / Backend | — | Pure config value in `WikiReloadService`; no cross-tier surface |
| Cooldown-marking fix (D-03) | API / Backend | Database / Storage | Conditional write to `movie.wiki_last_attempted_at`, a Postgres column; logic lives entirely in `doRetryWikipedia()` |
| Progress state tracking + SSE stream (D-04, D-06, D-07) | API / Backend | — | New `WikiReloadProgressService`, in-memory (no queue infra per CLAUDE.md), streamed via `SseEmitter` from a new controller endpoint |
| Stop flag (D-08) | API / Backend | — | Checked inside `batchReload()`'s per-movie loop; no browser-side state beyond a button click that calls a stop endpoint |
| Progress UI rendering (D-05, D-06) | Frontend Server (SSR-capable, client-rendered here) | Browser / Client | `settings.vue` is Nuxt SSR-capable, but SSE consumption and live re-render happen client-side via `@microsoft/fetch-event-source`, same as `imports/[batchId].vue` |
| ETA calculation (D-07) | API / Backend | — | CONTEXT.md D-07 specifies rolling-average computed from real call durations, which only the backend observes; the frontend just renders the number the backend sends, it does not compute it itself |

## Standard Stack

No new libraries. Every dependency this phase touches is already in the stack and already exercised by an existing, analogous feature.

### Core (already present, reused as-is)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring `SseEmitter` (`org.springframework.web.servlet.mvc.method.annotation.SseEmitter`) | Spring Boot 3.5.0 BOM-managed [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java:6] | Server-Sent Events stream for progress | Already the exact mechanism `BulkImportProgressService`/`BulkImportController` use for IMPORT-05; this phase's progress UI must be the same shape per CONTEXT.md D-04 |
| `@microsoft/fetch-event-source` | `^2.0.1` [VERIFIED: frontend/package.json:19] | Client-side SSE consumption with custom `Authorization` header | Native `EventSource` cannot attach a header; this app's JWT is header-only (`useBulkImport.ts:1,35-38,57-73`) — same requirement applies to the new wiki-reload progress stream |
| `java.util.concurrent.atomic.AtomicReference`/`AtomicBoolean` | JDK 25 stdlib | Shared mutable state across threads without a lock | `WikipediaClient.backoffUntil` is already an `AtomicReference<Instant>` [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:94]; the new Stop flag should follow the same idiom (`AtomicBoolean`), even though no exact prior instance of `AtomicBoolean` exists in this codebase (`grep -rn "AtomicBoolean" backend/src/main/java` returned no hits this session — confirmed absent, not just unfound) |

**Installation:** None — no `npm install` / `build.gradle.kts` dependency additions required for this phase.

## Package Legitimacy Audit

**Not applicable.** This phase installs zero new external packages in either `backend/build.gradle.kts` or `frontend/package.json`. All libraries used (`SseEmitter`, `@microsoft/fetch-event-source`, JDK `Atomic*`) are already present and already verified in the codebase (see Standard Stack table above, each with a `[VERIFIED: <path>]` citation).

## Architecture Patterns

### System Architecture Diagram

```
[Browser: settings.vue #wikipedia-data]
        │  1. POST /admin/wiki-reload/{userId}  (existing, unchanged)
        ▼
[WikiReloadController.triggerReload()] ──► [WikiReloadService.batchReload()]  (@Async wikiReloadExecutor)
        │                                          │
        │  2. GET .../progress (SSE, NEW)          │  per-movie loop:
        ▼                                          │   a) check Stop flag (D-08, NEW)
[WikiReloadController.progress()]                  │   b) self.retryWikipedia(movie, resolvedTitles)
        │  registers SseEmitter with                │        └─► doRetryWikipedia(): sets
        ▼                                            │             wikiLastAttemptedAt ONLY on
[WikiReloadProgressService (NEW,                     │             success or WikipediaNotFoundException
 clone of BulkImportProgressService)]  ◄─────────────┘             (D-03 fix) — NOT on generic Exception
        │  publish(runId, movie title/status,                    │
        │          processed, total, etaSeconds)                  c) publish progress event (D-04/D-06/D-07)
        ▼                                                          d) Thread.sleep(pacingDelayMs=30000)  (D-01)
[SSE stream: "progress"/"complete" events]                              — with Stop-flag check before sleep too
        │
        ▼
[Browser: live per-movie list + ETA + Stop button, D-05/D-06/D-07/D-08]
        │  3. POST .../stop (NEW)
        ▼
[WikiReloadController.stop()] ──► sets Stop flag consulted by the loop above
```

Reader trace: a Start click issues (1) as it does today; a new (2) SSE connection opens simultaneously and stays open, receiving one `progress` event per processed movie until a terminal `complete` event; a Stop click issues (3), which the currently-sleeping or currently-between-movies loop observes at its next check point and exits cleanly without touching the movie mid-fetch (D-08).

### Recommended Project Structure
```
backend/src/main/java/de/moviearchive/
├── enrichment/
│   ├── WikiReloadService.java       # MODIFIED: D-01 pacing value comes from config (no code change
│   │                                 #   needed beyond the property default), D-03 conditional
│   │                                 #   wikiLastAttemptedAt write, D-08 stop-flag check, D-04/D-06/D-07
│   │                                 #   progress-publish calls inserted into batchReload()'s loop
│   └── WikipediaClient.java          # UNCHANGED (D-01/D-02 explicitly leave this file's pacing/backoff as-is)
├── admin/
│   ├── WikiReloadController.java     # MODIFIED: add GET .../progress (SSE) and POST .../stop endpoints,
│   │                                 #   mirroring BulkImportController's progress() + assertOwnership() pattern
│   └── WikiReloadProgressService.java # NEW — structural clone of BulkImportProgressService
frontend/
├── composables/
│   ├── useSettings.ts                # MODIFIED: add subscribeToWikiReloadProgress() + stopWikiReload(),
│   │                                 #   mirroring useBulkImport.ts's subscribeToProgress() shape
├── pages/
│   └── settings.vue                  # MODIFIED: #wikipedia-data section gets a progress block + Stop
│                                     #   button, mirroring imports/[batchId].vue's progress-bar/list JSX
```

### Pattern 1: In-memory SSE progress registry (clone of `BulkImportProgressService`)
**What:** `Map<UUID, List<SseEmitter>>` + `Map<UUID, State>` "last-known-state" registry, `register`/`publish`/`complete`/`sendEvent`/`removeEmitter` methods.
**When to use:** Any live-progress stream in this single-instance, no-queue-infrastructure app (CLAUDE.md's explicit constraint).
**Example (existing, to be cloned — not rewritten from scratch):**
```java
// Source: backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java:32-97
@Service
@Slf4j
public class BulkImportProgressService {
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressState> lastKnown = new ConcurrentHashMap<>();

    public record ProgressState(int processed, int total, boolean complete) {}

    public void register(UUID batchId, SseEmitter emitter, int totalLinesFallback) {
        emitters.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(batchId, emitter));
        emitter.onTimeout(() -> removeEmitter(batchId, emitter));
        ProgressState state = lastKnown.get(batchId);
        if (state != null) {
            sendEvent(emitter, batchId, state.complete() ? "complete" : "progress", state);
        } else {
            ProgressState synthesized = new ProgressState(totalLinesFallback, totalLinesFallback, true);
            sendEvent(emitter, batchId, "complete", synthesized);
        }
    }
    // publish()/complete()/sendEvent()/removeEmitter() — see full source
}
```
**Adaptation needed for this phase:** `ProgressState` must grow beyond `(processed, total, complete)` to also carry a per-movie title+status list (D-06) and an `etaSeconds` value (D-07) — CONTEXT.md leaves the exact record shape to planner discretion, but it must remain Jackson-serializable-as-is the way `ProgressState` already is (a plain `record`, no extra annotation — confirmed by the comment at `BulkImportProgressService.java:39`). The registry keys on `UUID batchId` for bulk-import; for wiki-reload there is no batch entity, so the key should be `userId` (the only identifier `WikiReloadController.triggerReload()` already has — CONTEXT.md's discretion note suggests `runId`, but `userId` avoids inventing new state to correlate a run with its progress stream, and this app is single-user-first with a single global executor slot, so one concurrent run per app instance is already the enforced invariant).

### Pattern 2: SSE endpoint + ownership check (clone of `BulkImportController.progress()`)
**What:** `GET .../progress` returns a raw `SseEmitter`, registers it with the progress service, `SseEmitter(Long.MAX_VALUE)` to avoid container timeout.
**Example:**
```java
// Source: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:131-137
@GetMapping(value = "/bulk-import/{batchId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter progress(@PathVariable UUID batchId, Authentication auth) {
    BulkImportBatch batch = loadOwnedBatch(auth, batchId);
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    progressService.register(batchId, emitter, batch.getTotalLines());
    return emitter;
}
```
`WikiReloadController` already has its own ownership check, `assertOwnership(auth, userId)` [VERIFIED: backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:64-71], throwing `AccessDeniedException` on a JWT-subject/path-`userId` mismatch — the new `GET /admin/wiki-reload/{userId}/progress` and the new stop endpoint should call this exact existing private method, not re-derive ownership logic.

### Pattern 3: Frontend SSE consumption via `@microsoft/fetch-event-source` (clone of `useBulkImport.ts`)
**What:** wraps `fetchEventSource` with the app's `Authorization` header, returns an unsubscribe function.
**Example:**
```typescript
// Source: frontend/composables/useBulkImport.ts:55-74
function subscribeToProgress(batchId: string, onProgress: (p: BulkImportProgress) => void): () => void {
  const ctrl = new AbortController()
  fetchEventSource(`/api/movies/bulk-import/${batchId}/progress`, {
    headers: authHeaders(),
    signal: ctrl.signal,
    async onopen() {},
    onmessage(ev) {
      if (ev.event === 'progress' || ev.event === 'complete') {
        onProgress(JSON.parse(ev.data) as BulkImportProgress)
      }
    },
    onerror(err) { throw err },
  })
  return () => ctrl.abort()
}
```
Componentized consumption (mount/unmount lifecycle) is proven in `imports/[batchId].vue:37-49` (`onMounted` subscribes, `onUnmounted` calls the returned unsubscribe function) — `settings.vue` should follow the identical `onMounted`/`onUnmounted` pairing, scoped to whether a run is believed active (e.g. after a successful "Start" trigger, or discovered active on page load via a synthesized-complete-or-replay register() call, exactly as `BulkImportProgressService.register()` already handles the "no lastKnown state" case).

### Anti-Patterns to Avoid
- **Applying `@Retryable` to `doRetryWikipedia()` or `batchReload()`:** Already explicitly forbidden by this file's own javadoc [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:29-32] — `WikipediaClient.fetch()` already exhausts its own fallback cascade; wrapping the caller in Spring retry would re-run the entire cascade on any residual exception, multiplying request volume during exactly the rate-limiting scenario this phase exists to prevent.
- **Hard `Thread.interrupt()` for Stop:** CONTEXT.md D-08 explicitly rejects this — a stop flag checked at the loop boundary (same point the pacing sleep already happens) is required instead, to avoid aborting a fetch mid-flight or leaving a partial/inconsistent save.
- **Building a "Resume" concept:** CONTEXT.md D-09 explicitly rejects dedicated resume state — the existing `findEligibleForWikiReload` cooldown query already provides idempotent continuation once D-03's fix lands (a genuinely-processed movie's fresh `wikiLastAttemptedAt` drops it from eligibility; an unprocessed movie remains eligible). Do not add a "remaining movie IDs" tracking table or similar.
- **Raising `wikipedia.request-pacing-ms` instead of (or in addition to) `wiki.retry.pacing-delay-ms`:** CONTEXT.md D-01 is explicit and was reasoned through by the user during discussion — raising the per-call knob to 30s would make each movie take ~4×30s=120s instead of the intended "30s between movies, fast within a movie."
- **Keying the new progress registry off a newly-invented "run ID" without checking whether one is really needed:** `userId` is already the only identifier available at the trigger point and matches the single-global-run-slot invariant already enforced by `wikiReloadExecutor`'s `core=1/max=1/queue=1` sizing [VERIFIED: backend/src/main/java/de/moviearchive/config/AsyncConfig.java:30-38] — inventing a `runId` UUID adds a correlation problem (client must learn the runId to open the SSE connection) that `userId` avoids.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Live progress streaming | A polling endpoint, WebSocket server, or external pub/sub broker | The existing in-memory SSE `Map<UUID, List<SseEmitter>>` registry pattern, cloned from `BulkImportProgressService` | CLAUDE.md's explicit "no queue infrastructure" constraint; this app is single-instance; the pattern is already proven correct (5 passing unit tests in `BulkImportProgressServiceTest.java`) |
| Authenticated SSE from the browser | Passing the JWT as a query param, or switching to cookie-only auth for this one endpoint | `@microsoft/fetch-event-source`, already a project dependency, already used for the header-only JWT scheme | Native `EventSource` cannot set custom headers; passing the token in the URL leaks it into server logs/proxy logs — already avoided by the existing bulk-import implementation, must be avoided identically here |
| Rolling-average ETA math | A generic statistics library | A fixed-size circular buffer (e.g. `Deque<Long>` capped at N) of the last N per-movie durations, averaged on each publish | This is a ~10-line calculation; CONTEXT.md D-07 leaves window size to planner discretion — no external dependency is warranted for something this small |
| Cross-thread stop signal | A custom lock/wait-notify implementation | `AtomicBoolean`, the same idiom this codebase already uses for `WikipediaClient.backoffUntil` (`AtomicReference<Instant>`) | JDK's `java.util.concurrent.atomic` package is the established idiom in this codebase for cross-thread mutable flags without locking; no precedent exists for anything more elaborate |

**Key insight:** Every piece of infrastructure this phase needs (SSE registry, authenticated SSE consumption, ownership-checked endpoints, bounded single-run executors) already exists in this codebase for the structurally-identical bulk-import feature. The only genuinely new construct is the Stop flag — everything else is disciplined cloning, not invention.

## Common Pitfalls

### Pitfall 1: Forgetting the `wikipedia.request-pacing-ms` vs. `wiki.retry.pacing-delay-ms` distinction
**What goes wrong:** A planner or implementer conflates "raise the Wikipedia pacing to 30s" with the wrong property, either raising `wikipedia.request-pacing-ms` instead of/in addition to `wiki.retry.pacing-delay-ms`, or renaming/merging the two properties.
**Why it happens:** Both properties live in the same class family (`WikiReloadService` vs. `WikipediaClient`) and both are named "pacing" — easy to confuse under time pressure.
**How to avoid:** `wiki.retry.pacing-delay-ms` (`WikiReloadService.java:46-47`, between-movie) is the ONLY property this phase changes the default of, per CONTEXT.md D-01. `wikipedia.request-pacing-ms` (`WikipediaClient.java:55-56`, within-movie, ~4 calls/movie) and `wikidata.request-pacing-ms` (`WikipediaClient.java:68-69`, SPARQL calls) are both explicitly out of scope.
**Warning signs:** A plan task that touches `WikipediaClient.java`'s `@Value` fields, or that changes `application.properties` lines other than `wiki.retry.pacing-delay-ms` (line 65).

### Pitfall 2: The generic `catch (Exception e)` block currently sets the same state as the `WikipediaNotFoundException` block
**What goes wrong:** `doRetryWikipedia()` currently has the unconditional `movie.setWikiLastAttemptedAt(Instant.now())` at line 91, BEFORE the try block, so every exception path (429/rate-limit, network error, timeout, or genuine not-found) sets the same cooldown timestamp. A naive fix might only touch the `catch (Exception e)` block's logic and forget the timestamp is already set upstream at line 91 regardless of what happens inside `try`.
**Why it happens:** The bug is a statement-placement issue, not a missing-conditional issue — the fix requires DELETING the line-91 unconditional set and adding it back in exactly two places: the success path (after the `movieRepository.save(movie)` at line 103, or before it — order matters for what gets persisted in one write vs. two) and the `catch (WikipediaNotFoundException e)` block (currently line 115-117).
**How to avoid:** Read the full method body (`WikiReloadService.java:90-122`) before editing — the fix touches 3 locations (delete line 91, add in the success block, add in the `WikipediaNotFoundException` catch), not 1.
**Warning signs:** A diff that only adds code to the generic `catch (Exception e)` block without also removing line 91's unconditional set — this would still set the timestamp on every path, making the fix a no-op.

### Pitfall 3: `WikiReloadServiceIntegrationTest`'s pacing-delay override becomes stale
**What goes wrong:** `WikiReloadServiceIntegrationTest.java:64-73` deliberately overrides `wiki.retry.pacing-delay-ms` to `2500` (not the production default) via `@DynamicPropertySource`, with an explicit comment explaining the 2500ms value was chosen against a measured 988-1614ms infrastructure floor. If the production default changes to 30000 but this test's override is left unexamined, the test still passes (it's independently overridden) — but a reviewer might assume the test exercises the new 30s value when it does not.
**Why it happens:** Test-suite pacing overrides are already deliberately decoupled from the production default (`application-test.properties` sets `wiki.retry.pacing-delay-ms=1` globally for suite speed) — this is by design, not a bug, but it means "the test proves the pacing constant is X" claims need to specifically check which override is in effect.
**How to avoid:** When verifying D-01 is correctly implemented, check `application.properties` (production default) directly rather than inferring it from test behavior — the test suite intentionally never exercises the real 30000ms value end-to-end (that would make the suite 30s+ slower per test).
**Warning signs:** A verification step that runs the test suite and calls it done — the test suite proves the pacing MECHANISM works, not that the DEFAULT VALUE is 30000.

### Pitfall 4: The 503 "already in progress" response and the Stop button can race
**What goes wrong:** `WikiReloadController.triggerReload()` already returns 503 when `wikiReloadExecutor`'s single run-slot + single queue-slot are both full [VERIFIED: backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:86-90]. If the Stop flag is implemented as a field on `WikiReloadProgressService` keyed by `userId` and NOT reset when a run completes or is stopped, a second "Start" after "Stop" could silently inherit a stale `true` stop-flag value and exit its per-movie loop on iteration 0 without processing anything — appearing to "hang" with no progress and no error.
**Why it happens:** No precedent for this reset lifecycle exists in the codebase (Stop flag is net-new, see Standard Stack); it's easy to set the flag on stop and forget to clear it on the next `batchReload()` invocation.
**How to avoid:** `batchReload()` must clear/reset the stop flag for `userId` at its own start (before the per-movie loop), not rely on the Stop-button handler or any other caller to reset it.
**Warning signs:** A "Start" after "Stop" that returns 202 but the SSE stream immediately shows `complete` with 0 processed movies, even though eligible movies exist.

### Pitfall 5: SSE 429-elapsed-time tests are pacing-sensitive — see already-documented Pitfall 5 reference
`application-test.properties` comments explicitly reference "RESEARCH.md Pitfall 5" for `wikipedia.request-pacing-ms=0` — a prior phase's research already documented that WireMock-based 429/backoff timing tests must set per-request pacing to 0 or near-0 so elapsed-time assertions measure the RIGHT thing (the shared `backoffUntil` wait) instead of an unrelated fixed sleep. If this phase's new tests for the D-01 pacing change or D-07 ETA rolling-average need real elapsed-time assertions, follow the same isolation principle: override only the property under test, keep every other pacing property at its test-suite-fast default.

## Code Examples

### Current buggy unconditional-set (to be fixed)
```java
// Source: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:90-122
private void doRetryWikipedia(Movie movie, Map<String, String> preResolvedTitles) {
    movie.setWikiLastAttemptedAt(Instant.now());  // BUG (D-03): unconditional, fires before any attempt
    try {
        int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
        String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
        String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
        WikipediaResult wiki = preResolvedTitles != null
                ? wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId(), preResolvedTitles)
                : wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId());
        movie.setWikiUrl(wiki.url());
        movie.setWikiSummary(wiki.summary());
        movie.setWikiPlot(wiki.plot());
        movie.setWikiCritics(wiki.critics());
        movieRepository.save(movie);
        log.info("Wiki retry succeeded movieId={}", movie.getId());
        try {
            indexingService.index(movie);
            movie.setIndexedAt(Instant.now());
            movieRepository.save(movie);
        } catch (Exception e) {
            log.warn("Wiki retry: OpenSearch re-index failed movieId={}: {}", movie.getId(), e.getMessage());
        }
    } catch (WikipediaNotFoundException e) {
        movieRepository.save(movie);            // must ALSO set wikiLastAttemptedAt here (D-03: genuine result)
        log.warn("Wiki retry: still not found movieId={}", movie.getId());
    } catch (Exception e) {
        movieRepository.save(movie);            // must NOT set wikiLastAttemptedAt here (D-03: technical failure)
        log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
    }
}
```

### Current per-movie pacing loop (D-01 config-only change, D-08 stop-flag insertion point)
```java
// Source: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:149-166
for (int i = 0; i < eligible.size(); i++) {
    Movie movie = eligible.get(i);
    try {
        self.retryWikipedia(movie, resolvedTitles);
    } catch (Exception e) {
        log.warn("Wiki batch-reload: unexpected error for movieId={}: {}", movie.getId(), e.getMessage());
    }
    if (i < eligible.size() - 1) {
        try {
            Thread.sleep(pacingDelayMs);   // D-01: pacingDelayMs sourced from wiki.retry.pacing-delay-ms,
        } catch (InterruptedException e) { // default raised 1000 -> 30000 in application.properties
            Thread.currentThread().interrupt();
            log.warn("Wiki batch-reload interrupted for userId={} at index={}", userId, i);
            return;
        }
    }
    // D-08 stop-flag check belongs HERE, at the same loop boundary as the pacing sleep above —
    // checked both before starting the next movie AND (per CONTEXT.md) not mid-fetch.
}
```

### Existing config property (D-01's target, already env-configurable)
```properties
# Source: backend/src/main/resources/application.properties:63-65
# Wiki batch-reload (Phase 8: cooldown window + inter-request pacing, D-04/D-08)
wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}
wiki.retry.pacing-delay-ms=${WIKI_RETRY_PACING_DELAY_MS:1000}
```
D-01's "env-configurable" requirement is already satisfied — `WIKI_RETRY_PACING_DELAY_MS` already exists as an env override. The only change needed is the numeric literal default (`1000` → `30000`).

### Existing cooldown-eligibility query (D-09's "naturally resumes" mechanism)
```java
// Source: backend/src/main/java/de/moviearchive/movie/MovieRepository.java:74-78
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId "
       + "AND m.wikiPlot IS NULL AND m.wikiCritics IS NULL "
       + "AND m.status = de.moviearchive.movie.MovieStatus.SUCCESS "
       + "AND (m.wikiLastAttemptedAt IS NULL OR m.wikiLastAttemptedAt < :cutoff)")
List<Movie> findEligibleForWikiReload(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
```
This query is unchanged by this phase — D-03's fix makes its existing semantics correct (a genuinely-processed movie drops out; a technically-failed one stays eligible for the very next run).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| REST-based Wikidata search (CirrusSearch + Sitelinks) | Batched SPARQL query (up to 50 IMDb IDs/request) | Phase 13, 2026-08-27 | Fixed the underlying "why is Wikipedia lookup slow/unreliable" problem this phase's follow-on issues surfaced against; this phase does not touch the SPARQL prefetch (`resolveViaWikidataSparql`, called once per `batchReload()` invocation, unchanged) |
| `wiki.retry.pacing-delay-ms=1000` (reactive-only rate-limit avoidance) | `wiki.retry.pacing-delay-ms≈30000` (proactive pacing) + existing reactive `backoffUntil` as fallback | This phase (14) | Live verification (2026-08-27, 409-movie real run) showed 1000ms between-movie pacing still triggers ~1 real 429/min under sustained load; this phase's raised default is meant to mostly avoid needing the reactive fallback at all, while explicitly keeping it as the safety net (D-02) |

**Deprecated/outdated:** None — no library or API this phase touches has a newer major version or a deprecated method surface; `SseEmitter` and `@microsoft/fetch-event-source` are both current.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | The new progress registry should key on `userId` rather than a newly-invented `runId` | Architecture Patterns, Pattern 1 | Low — this is a design recommendation, not a verified fact; if the planner disagrees and introduces a `runId`, the SSE endpoint path and client subscription call both need the ID threaded through an extra round-trip (the trigger response would need to return the `runId`), adding complexity CONTEXT.md's discretion note does not require. Both `userId`-keying and `runId`-keying are defensible; `userId`-keying is recommended because it requires zero new state and matches the existing single-global-slot invariant, but this is an inference not a locked decision. |
| A2 | `AtomicBoolean` (not a more elaborate cancellation-token type) is the right idiom for the Stop flag | Standard Stack, Don't Hand-Roll | Low — `AtomicReference<Instant>` (`WikipediaClient.backoffUntil`) is the closest existing precedent for cross-thread mutable state in this codebase; `AtomicBoolean` is the natural boolean analogue, but no exact `AtomicBoolean` precedent exists in-repo (confirmed absent via `grep`), so this is a recommendation based on established idiom, not a verified pattern being reused. |

## Open Questions

1. **Exact `ProgressState`-equivalent record shape for wiki-reload (per-movie list + ETA)**
   - What we know: `BulkImportProgressService.ProgressState` is `record ProgressState(int processed, int total, boolean complete)` — a plain Jackson-serializable record [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java:40]. CONTEXT.md explicitly leaves the wiki-reload equivalent's exact shape to planner discretion, but requires it to add a per-movie title+status list (D-06) and an ETA field (D-07).
   - What's unclear: Whether the per-movie list should be the full accumulated history (every movie processed so far, growing each event) or just the most-recently-processed movie (client accumulates its own list). The former is simpler on the client (no state merging) but sends a growing payload on every SSE event for a long-running batch (409 movies); the latter is a smaller payload per event but requires client-side list accumulation, closer to how `imports/[batchId].vue` currently only receives `(processed, total, complete)` and gets the FULL per-line detail from a separate `getBatchDetail()` REST call only once, at completion — bulk-import's actual pattern is NOT "stream every line's detail via SSE," it's "stream counts via SSE, fetch full detail once at the end." Wiki-reload's D-06 requirement (live per-movie list DURING the run, not just at the end) is stricter than what bulk-import currently does.
   - Recommendation: Send only the most-recently-completed movie's title+status in each `progress` SSE event (smallest payload), and have the frontend accumulate the list client-side (append on each event) — this avoids ever growing the SSE payload itself, and is a small, well-contained piece of client state (a `ref<Array<{title, status}>>`) that doesn't need a REST round-trip at all, unlike bulk-import's two-phase (SSE-for-counts, REST-for-detail) approach.

2. **Rolling-average window size for ETA (D-07)**
   - What we know: CONTEXT.md explicitly defers this to planner discretion ("pick a reasonable default and document it").
   - What's unclear: No prior art in this codebase for a rolling average of anything.
   - Recommendation: A window of the last 5 movies balances responsiveness (adapts quickly if backoff kicks in mid-run, per D-07's explicit "including any time spent in an active 429 backoff") against noise (a single very-fast or very-slow outlier movie shouldn't swing the ETA wildly). Document this as a named constant if implemented, not a magic number inline.

## Environment Availability

Skipped — this phase has no new external tool/service/runtime dependency beyond what the existing dev environment already provides (Spring Boot app, Postgres, WireMock in tests, Node/pnpm for the frontend) — all already verified present and working by the preceding phases (12, 13) whose live-verification run is what surfaced this phase's need in the first place.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 + Mockito + AssertJ + Testcontainers (`postgres:16-alpine`) + WireMock 3.13.0 [VERIFIED: backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java:9-33, backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java:1-52] |
| Frontend framework | Vitest ^3.1.3 + Vue Test Utils [VERIFIED: frontend/package.json:11-13,44; frontend/test/unit/pages/imports-batchId.spec.ts:1-2] |
| Config file | `backend/build.gradle.kts` (JUnit Platform, no separate config file); `frontend/vitest.config.ts` (not read this session, referenced by `package.json`'s `test` script) |
| Quick run command | `./gradlew test --tests "de.moviearchive.enrichment.*" --tests "de.moviearchive.admin.WikiReloadControllerTest"` (backend); `pnpm --filter frontend test -- settings imports-batchId` (frontend, approximate — Vitest supports filename-substring filtering) |
| Full suite command | `./gradlew test` (backend); `pnpm test` → `vitest run` (frontend) [VERIFIED: frontend/package.json:11] |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| D-14-01 | `wiki.retry.pacing-delay-ms` default is 30s-ish; `wikipedia.request-pacing-ms` unchanged | unit (config value) + existing integration (mechanism) | `./gradlew test --tests WikiReloadServiceIntegrationTest` (pacing MECHANISM only — value itself must be checked by reading `application.properties`, see Pitfall 3) | ✅ mechanism test exists (`WikiReloadServiceIntegrationTest.java`); ❌ no test currently asserts the production DEFAULT value — Wave 0 gap |
| D-14-02 | `wikiLastAttemptedAt` set only on success/`WikipediaNotFoundException`, not on generic exception | unit | `./gradlew test --tests WikiReloadServiceTest` | ✅ file exists, but current tests (`shouldSetTimestampAndWikiFields_onRetrySuccess`, `shouldSetTimestampOnly_whenWikipediaNotFound`) don't yet cover the "generic exception → timestamp NOT set" case — Wave 0 gap, new test needed |
| D-14-03 | Progress UI: total, live per-movie progress, ETA | unit (backend `WikiReloadProgressService`) + component (frontend `settings.vue`) | New — no existing test file for either | ❌ Wave 0 gap on both sides |
| D-14-04 | Stop control cleanly interrupts, and a subsequent Start naturally resumes | integration (backend) + component (frontend) | New — extends `WikiReloadServiceIntegrationTest` | ❌ Wave 0 gap |

### Sampling Rate
- **Per task commit:** targeted `./gradlew test --tests <ClassName>` for the file(s) touched, or `pnpm test -- <filename-substring>` for frontend
- **Per wave merge:** `./gradlew test` (backend full suite) + `pnpm test` (frontend full suite)
- **Phase gate:** Full suite green (both backend and frontend) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` — needs a new test asserting `wikiLastAttemptedAt` is NOT set when `wikipediaClient.fetch(...)` throws a generic `Exception` (D-14-02) — currently only success and `WikipediaNotFoundException` paths are covered
- [ ] `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java` — net-new, mirrors `BulkImportProgressServiceTest.java`'s structure (register/publish/complete lifecycle, using `mock(SseEmitter.class)` + `ArgumentCaptor<SseEmitter.SseEventBuilder>`)
- [ ] `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` — needs new test cases for the SSE progress endpoint and the stop endpoint (ownership-check 403 case should mirror the existing pattern already in this file for `triggerReload`)
- [ ] `frontend/test/unit/composables/useSettings.spec.ts` — needs new test cases for a `subscribeToWikiReloadProgress`-equivalent function, mirroring `imports-batchId.spec.ts`'s `vi.mock('@/composables/...')` + captured-callback pattern
- [ ] `frontend/test/unit/pages/settings.spec.ts` — needs new test cases for the progress block + Stop button rendering and interaction, extending the existing wiki-reload-trigger tests already in this file

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | yes | Existing JWT bearer scheme, unchanged — `authHeaders()` composable pattern already used by every settings.ts/useBulkImport.ts call |
| V3 Session Management | yes (indirectly, via SSE) | The new SSE endpoint MUST use the same `@microsoft/fetch-event-source` + header-based JWT pattern as the existing bulk-import SSE endpoint — native `EventSource` (which cannot carry an `Authorization` header, forcing a query-param token workaround) must NOT be introduced, per the explicit warning already documented in `BulkImportController.java:122-125` |
| V4 Access Control | yes | IDOR protection: the new `GET /admin/wiki-reload/{userId}/progress` and new stop endpoint MUST call the existing `assertOwnership(auth, userId)` private method [VERIFIED: backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:64-71], exactly as `triggerReload()` already does — this is the established, tested pattern (`WikiReloadControllerTest.java` already covers the 403 case for `triggerReload`) |
| V5 Input Validation | n/a (new scope) | No new user-supplied input beyond the existing `userId` path variable, already validated by ownership check |
| V6 Cryptography | n/a | Unchanged — no new secrets or encrypted data introduced |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| IDOR via `userId` path variable on the new SSE/stop endpoints | Elevation of Privilege | Reuse `assertOwnership()` — already proven correct for the sibling `triggerReload()` endpoint and already covered by an existing passing test |
| JWT leaking into server/proxy access logs via a query-param SSE token | Information Disclosure | Do NOT use native `EventSource`; use `@microsoft/fetch-event-source` with header-based auth exactly as `useBulkImport.ts` already does — this is already the established mitigation in this codebase, not a new decision |
| Resource exhaustion via an unbounded/never-completing SSE connection | Denial of Service | `SseEmitter(Long.MAX_VALUE)` combined with `onCompletion`/`onTimeout` emitter cleanup — already the established pattern in `BulkImportProgressService.register()`; must be replicated, not reinvented, in the new service |
| Stale Stop-flag causing a silent no-op "Start" (see Pitfall 4) | Denial of Service (self-inflicted, appears as a hang) | `batchReload()` must reset the Stop flag for its `userId` at the top of the method, before the per-movie loop begins |

## Sources

### Primary (HIGH confidence — all read directly this session)
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — full file read; confirmed exact bug location (line 91) and pacing loop (lines 149-166)
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — full file read; confirmed `requestPacingMs`/`wikidataRequestPacingMs`/`backoffUntil`/`recordRateLimited` are untouched by this phase's scope
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` — full file read; the exact SSE registry pattern to clone
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — full file read; existing trigger endpoint, ownership check, 503-on-full-queue handler
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` (partial, lines 1-150) — the SSE endpoint + `loadOwnedBatch()`/ownership-check counterpart pattern
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` — confirmed `wikiReloadExecutor` is `core=1/max=1/queue=1`, a single global slot
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` (lines 63-78) — confirmed `findEligibleForWikiReload` query, verbatim
- `backend/src/main/java/de/moviearchive/movie/Movie.java` (lines 60-84) — confirmed `wikiLastAttemptedAt` field/column mapping, verbatim
- `backend/src/main/java/de/moviearchive/movie/MovieStatus.java` — confirmed enum values `PENDING`, `SUCCESS`, `ERROR`, verbatim
- `backend/src/main/resources/application.properties` (lines 55-75) — confirmed exact current pacing property defaults and env var names, verbatim
- `backend/src/test/resources/application-test.properties` (lines 1-45) — confirmed test-suite pacing overrides and the documented rationale ("RESEARCH.md Pitfall 5" reference)
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` — full file read; existing unit test coverage and gaps for D-14-02
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java` (lines 1-80) — confirmed integration test structure and pacing-override rationale
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java` — full file read; the exact test pattern (`mock(SseEmitter.class)` + `ArgumentCaptor`) to mirror for the new service's tests
- `frontend/composables/useBulkImport.ts` — full file read; exact SSE-consumption composable shape to mirror
- `frontend/composables/useSettings.ts` — full file read; existing `triggerWikiReload()` and the composable's overall shape (`authHeaders()`, `getCurrentUserId()`)
- `frontend/pages/settings.vue` (lines 1-60, 160-200, 400-418) — confirmed the exact insertion point (`#wikipedia-data` section) and existing trigger-button state (`wikiReloadTriggering`, `wikiReloadMessage`)
- `frontend/pages/imports/[batchId].vue` — full file read; the exact progress-bar + per-item-list rendering pattern to mirror for the settings.vue inline block
- `frontend/test/unit/pages/imports-batchId.spec.ts` (lines 1-40) — confirmed the `vi.mock` + captured-callback frontend test pattern
- `frontend/package.json` — confirmed `@microsoft/fetch-event-source: ^2.0.1` and Vitest `^3.1.3` versions, verbatim
- `.planning/phases/14-wiki-batch-reload-pacing-cooldown-fix-progress-ui/14-CONTEXT.md` — full file read; all locked decisions (D-01 through D-09) and discretion areas
- `.planning/phases/14-wiki-batch-reload-pacing-cooldown-fix-progress-ui/14-DISCUSSION-LOG.md` — full file read; alternatives considered and rejected, confirming CONTEXT.md's decisions are final
- `.planning/STATE.md` — full file read; confirmed the folded todo and prior-phase decision history
- `.planning/REQUIREMENTS.md` — full file read; confirmed no formal REQUIREMENTS.md IDs cover Phase 14 (consistent with CONTEXT.md's note)
- `.planning/config.json` — confirmed `workflow.nyquist_validation: true` (Validation Architecture section required) and no web-search providers configured (`brave_search`/`exa_search`/`firecrawl` all `false`) — consistent with this research being entirely codebase-internal

### Secondary / Tertiary
None — no WebSearch or external documentation lookup was performed this session. This phase's entire scope is an extension of already-shipped, already-documented, already-tested in-repo patterns; no new external library or API needed investigation beyond what direct source reading already confirmed. `.planning/config.json` confirms no external search providers (Brave/Exa/Firecrawl) are even configured for this project, reinforcing that this phase's research posture should stay entirely codebase-internal.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — zero new libraries; every dependency cited was read directly from `package.json`/existing source this session
- Architecture: HIGH — every pattern to clone (SSE registry, SSE endpoint, SSE composable, progress UI rendering) was read in full from its existing implementation this session; only the Stop flag has no direct precedent (flagged explicitly, not overstated)
- Pitfalls: HIGH — all 5 pitfalls are drawn from exact line numbers and comments already present in the source and test files, not speculation

**Research date:** 2026-08-27
**Valid until:** No expiry driver — this research is tied to the current state of an internal, actively-developed codebase, not an external API/library version. Re-verify only if `WikiReloadService.java`, `WikipediaClient.java`, or `BulkImportProgressService.java` change materially before this phase is planned/executed.
