# Phase 14: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-27
**Phase:** 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
**Areas discussed:** Pacing strategy, Genuine-vs-technical failure, Progress UI mechanics, Stop/Start semantics

---

## Todo fold check

Asked whether the pending todo "Show progress indicator for Wikipedia batch-reload" (2026-08-23) should be folded into this phase's scope.

**User's choice:** Yes, fold it in — user confirmed (in German) that this was already the plan and the todo should be removed from the pending list once this phase covers it.
**Notes:** Reflected as a "Folded Todos" entry in CONTEXT.md, with a note to remove it from STATE.md's Pending Todos once the phase ships.

---

## Pacing strategy

Initial framing asked which of two knobs (`wiki.retry.pacing-delay-ms` between movies, vs. `wikipedia.request-pacing-ms` between individual API calls) should be raised toward ~30s.

| Option | Description | Selected |
|--------|-------------|----------|
| Both raised to same value | Single config knob for both | |
| Only `wikipedia.request-pacing-ms` raised | Assumed `batchReload`'s own sleep was redundant | |
| Two separate properties | Keep both independently tunable | |

**User's choice:** None of the above directly — user pushed back with their own analysis: a movie makes multiple Wikipedia calls (they estimated ~8, code confirmed ~4 in the Wikidata-hit case), so pacing every individual call at 30s would make each movie take minutes. They reasoned pacing should be ~30s **between movies**, not between every call.

Follow-up question (after verifying call count in code): confirmed `wiki.retry.pacing-delay-ms` (between-movie, in `WikiReloadService.batchReload()`) is the correct knob to raise to ~30s; `wikipedia.request-pacing-ms` (within-movie, ~4 calls) stays at 1000ms.

| Option | Description | Selected |
|--------|-------------|----------|
| Only pacing-delay-ms to 30s | Between-movie sleep raised; within-movie pacing untouched | ✓ |
| Both raised slightly | pacing-delay-ms to 30s AND request-pacing-ms bumped a bit as extra buffer | |

**User's choice:** Only `pacing-delay-ms` → 30s.
**Notes:** User explicitly noted the existing reactive 429-backoff stays as the fallback safety net for cases where a movie falls through to the fallback candidate cascade and trips a rate limit anyway — "dann muss halt auch mal die 40 Sekunden back off gewartet werden, bis es weitergeht."

---

## Genuine-vs-technical failure

| Option | Description | Selected |
|--------|-------------|----------|
| Only rate-limit/429 counts as technical | Everything else (incl. generic exceptions) still sets cooldown | |
| Rate-limit + all technical failures | Only `WikipediaNotFoundException` sets cooldown; every other exception does not | ✓ |

**User's choice:** Rate-limit + all technical failures.
**Notes:** Simple rule — cooldown timestamp only set on success or a genuine "page not found" result.

---

## Progress UI mechanics

| Option | Description | Selected |
|--------|-------------|----------|
| SSE (BulkImportProgressService pattern) | Reuse existing in-memory emitter registry pattern | ✓ |
| Polling | Client polls a status endpoint | |

**User's choice:** SSE, reusing the existing pattern.

| Option | Description | Selected |
|--------|-------------|----------|
| Inline in settings.vue | Progress appears under the existing trigger button | ✓ |
| Separate page (like imports/[batchId].vue) | Navigate to a dedicated progress page | |

**User's choice:** Inline in settings.vue.

---

## ETA & detail level

| Option | Description | Selected |
|--------|-------------|----------|
| Rolling live average | Track actual per-movie duration for ETA | ✓ |
| Fixed estimate | remaining × configured pacing delay only | |

**User's choice:** Rolling live average.

| Option | Description | Selected |
|--------|-------------|----------|
| Only count + ETA | Simple "X of Y" display | |
| List with title + status per movie | Per-movie list like bulk-import results | ✓ |

**User's choice:** List with title + status per movie.

---

## Stop/Start semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Clean interrupt after current movie | Stop flag checked between movies, no mid-fetch abort | ✓ |
| Hard Thread.interrupt() | Immediate interrupt, even mid-call | |

**User's choice:** Clean interrupt after current movie.

| Option | Description | Selected |
|--------|-------------|----------|
| Just re-trigger, no dedicated resume state | Cooldown filter naturally continues from where a stop left off | ✓ |
| Explicit resume mechanism | Server-side state tracking remaining movie IDs | |

**User's choice:** Just re-trigger — but explicitly renamed: not "Resume," just "Start" again (start/stop/start, not start/stop/resume).

---

## Claude's Discretion

- Exact SSE event/payload shape for wiki-reload progress (mirror `BulkImportProgressService.ProgressState`, adapted with a per-movie title+status list).
- Rolling-average window size for ETA calculation.
- Where the Stop flag lives (implementation detail).
- Exact naming of the new progress service class/methods and SSE endpoint path.

## Deferred Ideas

None — discussion stayed within phase scope. Two todos (CSV parsing, bulk-import batch detail page) were reviewed against this phase and confirmed to belong to Phase 15 instead.
