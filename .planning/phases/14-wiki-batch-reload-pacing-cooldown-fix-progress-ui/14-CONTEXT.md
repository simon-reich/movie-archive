# Phase 14: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI - Context

**Gathered:** 2026-08-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Live verification of Phase 13 (real `batchReload` run against 409 cooldown-eligible movies, 2026-08-27) confirmed the Wikidata SPARQL batching fix works (21/21 movies got a Wikipedia hit vs. the historical ~11% baseline), but surfaced two follow-on problems: (1) the separate Wikipedia article-content fetch step still hits real 429s under sustained load, and (2) `WikiReloadService.doRetryWikipedia()` sets `wikiLastAttemptedAt` unconditionally before every attempt, so a movie that fails only due to rate-limiting gets cooldown-blocked as if genuinely checked and found empty.

This phase: (a) paces `batchReload()`'s between-movie delay at a deliberate, env-configurable cadence to mostly avoid ever hitting the rate limit; (b) fixes the cooldown-marking bug so `wikiLastAttemptedAt` is only set after a genuine, successfully-executed article-content fetch (found or confirmed-not-found), never on a technical/rate-limit failure; (c) adds a batch-reload progress UI: total movies targeted, live per-movie progress (title + success/fail status), an ETA computed from remaining-count × rolling-average call duration, and a Start/Stop control.

**Explicit out-of-scope:** `wikipedia.request-pacing-ms` (the pacing between the ~4 individual API calls a single movie's article fetch makes) is NOT touched by this phase — see D-01 below for why. Bulk-import's own enrichment pacing is untouched (separate caller, not in scope).

</domain>

<decisions>
## Implementation Decisions

### Pacing — the between-movie knob, not the within-movie knob
- **D-01:** Raise `wiki.retry.pacing-delay-ms` (the `Thread.sleep` in `WikiReloadService.batchReload()`'s per-movie loop) from 1000ms toward **~30s**. `wikipedia.request-pacing-ms` (paced before each of `WikipediaClient`'s ~4 outbound calls per movie in the common Wikidata-hit case — `sections` + up to 3 `fetchSection` calls for summary/plot/critics) stays unchanged at 1000ms. Rationale confirmed during discussion: raising the *per-call* knob to 30s would make each movie take 4×30s instead of the intended "30s between movies, fast within a movie" — the ROADMAP's "default raised toward ~30s" language refers to `pacing-delay-ms`, not `request-pacing-ms`. — **Reversibility:** reversible — a config default change, no code-shape impact.
- **D-02:** The existing reactive 429-backoff (`WikipediaClient.recordRateLimited`/`backoffUntil`, shared across all requests) stays exactly as-is as the fallback safety net for whenever a movie falls through Wikidata into the candidate-URL cascade (more calls, higher 429 risk) or the proactive 30s pacing isn't enough for some other reason. Not a replacement of one mechanism by the other — complementary layers.

### Cooldown-marking fix — genuine vs. technical failure
- **D-03:** `wikiLastAttemptedAt` is set on: (a) success (page found), and (b) `WikipediaNotFoundException` (a genuine "no Wikipedia page exists" result — the fallback cascade was fully exhausted with no hit). It is **NOT** set on any other exception path (429/rate-limit via `recordRateLimited`, network errors, timeouts, or any other technical failure) — those movies remain immediately eligible for the next batch-reload run rather than being cooldown-blocked for `wiki.retry.cooldown-days`. — **Reversibility:** reversible — a conditional-set change inside `doRetryWikipedia()`, no schema/contract impact.

### Progress UI — SSE, reused pattern
- **D-04:** Reuse the `BulkImportProgressService` pattern (in-memory `Map<UUID, List<SseEmitter>>` + last-known-state registry) for wiki-reload: a new `WikiReloadProgressService` (or equivalent) tracks per-run state and streams SSE events, consistent with this app's single-instance, no-queue-infrastructure architecture (CLAUDE.md's "Async: `@Async` + `@Retryable` (no queue infrastructure)" constraint). — **Reversibility:** reversible — new component, no changes to existing bulk-import progress code.
- **D-05:** The progress UI lives **inline in `settings.vue`**, directly under the existing "Reload missing Wikipedia data" trigger button — no navigation to a separate page. — **Reversibility:** reversible.
- **D-06:** Live progress shows a **per-movie list** (title + success/fail status) as movies are processed, not just an aggregate count — same level of detail as the bulk-import results view, adapted to this settings-page context.
- **D-07:** ETA is computed from remaining-count × a **rolling live average** of actual per-movie call duration (including any time spent in an active 429 backoff), not a fixed estimate based on the configured pacing delay alone — more accurate when backoff kicks in.

### Stop/Start control
- **D-08:** "Stop" performs a **clean interrupt after the currently-processing movie completes** — the stop flag is checked between movies (same point where the pacing sleep happens today), never a hard `Thread.interrupt()` mid-HTTP-call, to avoid an aborted fetch mid-flight or an inconsistent partial save.
- **D-09:** There is no dedicated "Resume" concept or server-side resume state — a stopped run is simply started again via the same trigger ("Start", not "Resume"). The existing cooldown-eligibility query (`findEligibleForWikiReload`) naturally picks up exactly where a stopped run left off: movies already processed (success or genuine-not-found, per D-03) have a fresh `wikiLastAttemptedAt` and drop out of eligibility; movies not yet reached remain eligible. — **Reversibility:** reversible — no new state to design around; relies entirely on existing cooldown-filter behavior confirmed correct by D-03.

### Claude's Discretion
- Exact SSE event/payload shape for wiki-reload progress (mirror `BulkImportProgressService.ProgressState` record shape, adapted with a per-movie title+status list field).
- Exact window size for the rolling-average ETA calculation (e.g. last N movies) — pick a reasonable default and document it.
- Where the Stop flag lives (e.g. an `AtomicBoolean` keyed by userId/runId in the new progress service, checked by `batchReload()`'s loop) — planner's call.
- Exact naming of the new `WikiReloadProgressService` class/methods and any new SSE endpoint path (e.g. `GET /admin/wiki-reload/{userId}/progress`).

### Folded Todos
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — "Show progress indicator for Wikipedia batch-reload." Confirmed by user during discussion as fully covered by this phase's D-14-03/D-04–D-07 progress-UI work. Should be removed from `.planning/STATE.md`'s Pending Todos once this phase ships.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Prior phase context (what this phase builds on / fixes)
- `.planning/phases/13-wikidata-sparql-batch-lookup/13-CONTEXT.md` — Phase 13's SPARQL batching decisions (the prefetch-map shape `retryWikipedia(movie, preResolvedTitles)` this phase's pacing/cooldown fixes build on top of, unchanged)
- `.planning/phases/08-wiki-enrichment-tracking-batch-reload/08-CONTEXT.md` — original `wikiLastAttemptedAt`/cooldown-window/pacing-delay design (D-01–D-08) that this phase's D-01 and D-03 amend

### Existing code (pacing, cooldown, progress pattern)
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — `doRetryWikipedia()` (unconditional `setWikiLastAttemptedAt` at top, to be made conditional per D-03) and `batchReload()` (per-movie `Thread.sleep(pacingDelayMs)` loop, the D-01 pacing target and the D-08 stop-check insertion point)
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — `requestPacingMs`/`wikidataRequestPacingMs` fields (lines ~55, ~68, explicitly NOT changed by this phase per D-01), `paceRequest()`, `backoffUntil`/`recordRateLimited()` (429 handling, stays as fallback per D-02), `fetch()`/`resolveWikidataResult()`/`tryFetch()`/`fetchSection()` (the ~4-calls-per-movie shape referenced in D-01's rationale)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` — the SSE progress pattern to replicate per D-04 (in-memory emitter registry, last-known-state replay on reconnect, `complete()` eviction)
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — existing `POST /admin/wiki-reload/{userId}` trigger endpoint; needs a companion progress/SSE endpoint and a stop endpoint per D-04/D-08
- `frontend/pages/settings.vue` (lines ~42-43, ~178-188, ~409-414) — existing wiki-reload trigger button (`wikiReloadTriggering`, `onTriggerWikiReload`) and its `#wikipedia-data` section, where the D-05 inline progress UI attaches
- `frontend/composables/useSettings.ts` — existing `$fetch` call to the trigger endpoint; needs an SSE-consuming counterpart for progress
- `frontend/pages/imports/[batchId].vue` + `frontend/composables/useBulkImport.ts` — the closest existing frontend SSE-consumption pattern to model the new settings.vue progress display on

No external specs/ADRs beyond the above — this phase has no formal REQUIREMENTS.md IDs, per ROADMAP.md's note that it carries forward Phase 12/13's decision-as-requirement pattern.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BulkImportProgressService`'s full SSE registry pattern (`register`/`publish`/`complete`/`sendEvent`/`removeEmitter`) — directly adaptable for wiki-reload progress (D-04)
- `WikipediaClient.backoffUntil`/`recordRateLimited()` — untouched, reused as-is as the fallback safety net (D-02)
- `MovieRepository.findEligibleForWikiReload(userId, cutoff)` — the existing cooldown-filter query that D-09 relies on for "Start again" to naturally resume from where a stop left off

### Established Patterns
- `@Async("wikiReloadExecutor")` bounded single-slot executor — unaffected by this phase's changes
- In-memory `Map<UUID, ...>` registries (no external queue/pub-sub) — this app's established pattern per CLAUDE.md, followed by `BulkImportProgressService` and to be followed by the new wiki-reload progress component
- Admin endpoints under `/admin/**`, `de.moviearchive.admin` package — the new progress/stop endpoints belong alongside `WikiReloadController`

### Integration Points
- `WikiReloadService.batchReload()` needs: (1) a stop-flag check inserted at the existing per-movie loop boundary (D-08), (2) a progress-publish call per movie (D-04/D-06), (3) call-duration tracking feeding the D-07 rolling-average ETA
- `settings.vue`'s `#wikipedia-data` section needs a new SSE-consuming progress display block plus a Stop button, both gated on an active run

</code_context>

<specifics>
## Specific Ideas

- User's own worked-out math during discussion, confirmed correct against the code: in the common (Wikidata-hit) case a movie makes ~4 Wikipedia calls; at the new 30s between-movie pacing that's well within Wikipedia's tolerated ~1 req/s sustained rate, without making each movie itself take minutes.
- "Start" is the only verb for both first-run and continuing-after-stop — explicitly not "Resume," per user preference, since there's no dedicated resume state (D-09).

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

### Reviewed Todos (not folded)
- `2026-08-24-support-real-csv-parsing-for-bulk-import` — Phase 15's concern (real CSV parsing for bulk import), unrelated to wiki-reload pacing/cooldown/progress.
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — Phase 15's concern (bulk-import batch detail page UI), unrelated to this phase's wiki-enrichment work.

</deferred>

---

*Phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui*
*Context gathered: 2026-08-27*
