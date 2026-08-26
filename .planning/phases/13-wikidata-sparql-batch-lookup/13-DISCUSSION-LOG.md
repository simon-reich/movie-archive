# Phase 13: Wikidata SPARQL Batch Lookup - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-26
**Phase:** 13-wikidata-sparql-batch-lookup
**Areas discussed:** Batching scope, Bulk-import's own rate-limit exposure, Dev-visibility log

---

## Pending Todo Fold Check

| Todo | Reason matched | Folded |
|------|-----------------|--------|
| 2026-08-23-show-progress-indicator-for-wikipedia-batch-reload | keywords: batch, problem; area: ui | No |
| 2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link | keywords: batch, movie, resolve, problem, phase | No |

**User's choice:** Fold neither (recommended) — both are UI-layer todos unrelated to this phase's backend SPARQL lookup mechanism.

---

## Batching scope

| Option | Description | Selected |
|--------|-------------|----------|
| One method everywhere, batch-of-1 for single callers | Matches Phase 12's D-03 pattern — save-flow/manual-retry call the SPARQL method with a 1-element IMDb ID list, no separate code path | ✓ |
| SPARQL batching only for batch-reload | Save-flow/manual retry keep something else | |
| You decide | Let Claude/research settle it | |

**User's choice:** One method everywhere, batch-of-1 for single callers.

**Follow-up question:** Should batch-reload be restructured to resolve all eligible movies' IMDb IDs via one/chunked SPARQL call(s) before the per-movie loop, or keep the per-movie loop calling the SPARQL method each time (1 ID per call)?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — prefetch all eligible IDs before the loop | Actually delivers "dozens of IDs per request" instead of one request per movie | ✓ |
| No — keep the per-movie loop calling the SPARQL method each time | Simpler but doesn't reduce request count | |

**User's choice:** Yes — prefetch all eligible IDs before the loop.

---

## Bulk-import's own rate-limit exposure

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — restructure bulk-import's loop too | Bulk-import is the original trigger of the rate-limit incident and has the same shape as batch-reload | ✓ |
| No — out of scope, batch-reload only | Bulk-import already got a scoped pacing fix in a prior quick-task | |
| You decide | Let research/planning assess | |

**User's choice:** Yes — restructure bulk-import's loop too.

**Follow-up question (structural wrinkle):** Unlike batch-reload, bulk-import's `imdbId` isn't known until `EnrichmentService.enrich()` fetches TMDB details per line — no upfront list of IMDb IDs exists before the per-line loop runs. How should Phase 13 approach this?

| Option | Description | Selected |
|--------|-------------|----------|
| Two-pass: TMDB detail fetch first for all matched lines, then batch Wikidata | Bigger restructuring but delivers the real batching benefit | ✓ |
| Leave bulk-import as-is for this phase | Treat as separate future concern | |
| You decide after research | Let researcher assess feasibility/cost | |

**User's choice:** Two-pass: TMDB detail fetch first for all matched lines, then batch Wikidata.

---

## Dev-visibility log (D-05, Phase 12)

| Option | Description | Selected |
|--------|-------------|----------|
| Keep as-is, update wording for SPARQL | Change "found via Wikidata" to reflect the SPARQL batch resolution path | |
| Remove it now | Phase 12 shipped and is done; strip the temporary log + call sites entirely | ✓ |
| Keep unchanged, no wording update | Leave the log exactly as Phase 12 built it | |

**User's choice:** Remove it now.

---

## Claude's Discretion

- Exact SPARQL query shape (`VALUES` clause + `wdt:P345` + sitelink resolution in one query)
- Batch chunk size for SPARQL requests (URL-length/complexity limits) — pick a conservative default
- Whether the new SPARQL call reuses `WikipediaClient`'s existing `paceRequest()`/`backoffUntil` 429-handling machinery
- Exact restructuring shape for the prefetch step in `batchReload()` and `BulkImportService` (e.g. helper returning `Map<String, String>` of imdbId → enwiki title)

## Deferred Ideas

None — discussion stayed within phase scope.

### Reviewed Todos (not folded)
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — UI-layer, unrelated to backend SPARQL lookup mechanism
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — bulk-import results-page UI, unrelated to Wikidata lookup mechanism
