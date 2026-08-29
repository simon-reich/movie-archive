# Phase 13: Wikidata SPARQL Batch Lookup - Context

**Gathered:** 2026-08-26
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the per-movie two-call Wikidata REST lookup (`WikipediaClient.tryFetchViaWikidata`: CirrusSearch `action=query&list=search` for P345 + REST sitelinks) with a batched SPARQL query against `query.wikidata.org/sparql` that resolves multiple IMDb IDs to their enwiki article titles in a single request. Live testing showed the current REST search endpoint hits Wikidata's anonymous rate limiter after only 2-3 movies even at 3000ms per-request pacing — an absolute per-minute quota on the CirrusSearch-backed search endpoint, not a spacing problem. SPARQL avoids that endpoint entirely and can resolve dozens of IMDb IDs per request. Scope extends to restructuring the two callers that process multiple movies in one run (batch-reload, bulk-import) so they actually exploit the batching, not just swap the underlying endpoint.

</domain>

<decisions>
## Implementation Decisions

### Batching scope — one method, everywhere
- **D-01:** The new SPARQL-based Wikidata lookup becomes the *only* Wikidata path, used by all three callers of `WikipediaClient.fetch()` — save-flow (`EnrichmentService`), manual retry (`WikiReloadService.retryWikipedia`), and batch-reload (`WikiReloadService.batchReload`). Single-movie callers (save-flow, manual retry) simply invoke it with a 1-element IMDb ID list. — **Reversibility:** reversible — matches Phase 12's D-03 "one client, one method, no path special-cased" pattern; purely an internal method replacement inside `WikipediaClient`.

### Batch-reload restructuring
- **D-02:** `WikiReloadService.batchReload()` is restructured to prefetch: gather all cooldown-eligible movies' IMDb IDs first, resolve them via one (or a few chunked) SPARQL call(s) *before* the per-movie loop starts, then loop through movies using the pre-resolved title (falling through to the existing candidate-URL cascade per movie when a given IMDb ID had no SPARQL match). This is what actually delivers "dozens of IDs per request" — calling the SPARQL method once per movie from inside the existing loop would not reduce request count versus today, only swap the endpoint. — **Reversibility:** costly — rationale: reshapes `batchReload()`'s control flow (currently a straight per-movie loop calling `self.retryWikipedia()`); reverting means re-inlining the per-movie Wikidata call.

### Bulk-import restructuring (two-pass)
- **D-03:** `BulkImportService`'s enrichment loop is also restructured for batching — it has the same one-movie-at-a-time exposure that caused the original ~630-movie incident (`processLine()` fires `enrichmentService.enrich()` per matched line, `enrich()` calls `WikipediaClient.fetch()` one movie at a time). Approach: two-pass. First resolve TMDB details (and therefore `imdbId`) for all matched lines in the run, since — unlike batch-reload, where `movie.imdbId` is already persisted from a prior save — bulk-import's `imdbId` today is only discovered inside `EnrichmentService.enrich()`'s own TMDB detail call. Then issue one batched SPARQL call for all collected IMDb IDs. Then proceed per line through OMDB/Wikipedia using the cached SPARQL results. — **Reversibility:** costly — rationale: changes `BulkImportService.processLine()`/`runImport()`'s sequencing (TMDB-detail-then-enrich per line today) into an explicit two-pass shape; reverting means collapsing back to per-line TMDB+enrich.

### Dev-visibility log (Phase 12, D-05)
- **D-04:** Remove the temporary `wiki-resolution.log` dev-visibility logging (the `logResolution()` method and its call sites in `WikipediaClient`) entirely as part of this phase. It served its debugging purpose during Phase 12 and is explicitly framed there as removable dev-only scaffolding — Phase 13 is the point to strip it out rather than update its wording for SPARQL. — **Reversibility:** reversible — pure deletion of a self-contained temporary method + `@Value` property + call sites; no schema, no dependents.

### Claude's Discretion
- Exact SPARQL query shape (e.g. `VALUES` clause binding multiple IMDb IDs via `wdt:P345`, combined with `wikibase:sitelinks`/`schema:about` to get the enwiki title in the same query) — technical/research call.
- Batch chunk size — how many IMDb IDs per SPARQL request, given `query.wikidata.org`'s practical URL-length/query-complexity limits. Not raised as a locked preference during discussion; pick a conservative default and document the reasoning, consistent with this project's pattern of erring toward safety after two live rate-limit incidents (Wikipedia REST, Wikidata REST).
- Whether the new SPARQL Wikidata step reuses `WikipediaClient`'s existing `paceRequest()`/`backoffUntil` 429-handling machinery (same question Phase 12 left open for the REST calls, still open here for SPARQL) — technical integration detail.
- Exact restructuring shape for `batchReload()`'s and `BulkImportService`'s prefetch step (e.g. a private helper returning `Map<String imdbId, String enwikiTitle>`, where SPARQL misses are simply absent from the map) — planner's call.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Prior phase context (what this phase builds on / changes)
- `.planning/phases/12-wikidata-based-wikipedia-lookup/12-CONTEXT.md` — Phase 12's decisions (D-01 Wikidata-first resolution order, D-03 "one client, one method" rollout pattern, D-05 the dev-visibility log being removed by this phase's D-04)

### Existing enrichment pipeline
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — current `tryFetchViaWikidata()` (two REST calls, lines 240-280) to be replaced by the SPARQL batch method; `paceRequest()`/`backoffUntil`/`recordRateLimited()` (429 handling) as candidate reuse; `logResolution()` (lines 216-225) and its `resolutionLogPath` `@Value` property to be deleted per D-04
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — `batchReload()` (lines 113-138, the per-movie loop to be restructured per D-02) and `retryWikipedia()` (single-movie caller, unaffected in shape, just calls the new method with a 1-element list per D-01)
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — save-flow caller; `imdbId` extraction from `tmdbDetail.path("external_ids").path("imdb_id")` (line 75) then `wikipediaClient.fetch()` (line 103) — this is the pattern bulk-import's two-pass restructuring (D-03) needs to front-load
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` — `processLine()` (calls `movieService.initiate()` then, after commit, `enrichmentService.enrich()` per matched line, line 94) — the loop to be restructured per D-03
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — `imdbId` field (persisted after first save/enrichment; already populated for batch-reload-eligible movies, NOT yet populated at bulk-import match time)
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` + `backend/src/test/resources/fixtures/wikipedia/` — existing WireMock test pattern; a new `fixtures/wikidata-sparql/` (or similar) directory should follow the same convention for the SPARQL endpoint

No external specs/ADRs beyond the above — requirements fully captured in decisions above (this phase has no formal REQUIREMENTS.md IDs, per ROADMAP.md's note that it carries forward Phase 12's decision-as-requirement pattern).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `WikipediaClient.paceRequest(long)` / `backoffUntil` / `recordRateLimited()` — existing 429-handling machinery, candidate for reuse by the new SPARQL call (open per Claude's Discretion above)
- `movie.getImdbId()` — already populated for all movies with a prior TMDB fetch; directly usable as the batch SPARQL query key for batch-reload

### Established Patterns
- External API clients: `@Component` + injected `WebClient.Builder`, `@Value` base URL — same shape should apply to a SPARQL endpoint client/method
- No `@Retryable` on top-level orchestrating methods (`fetch()`, `batchReload()`, `runImport()`) — same rule applies to any new batch-prefetch method: it should exhaust its own fallback internally, not be wrapped in Spring-managed retry that would re-run the whole batch
- WireMock test fixtures live under `backend/src/test/resources/fixtures/{source}/` — a new fixture directory for the SPARQL endpoint follows the same convention

### Integration Points
- New batch-resolution method likely lives in `WikipediaClient` (e.g. `resolveViaWikidataSparql(List<String> imdbIds) -> Map<String, String>`), replacing `tryFetchViaWikidata(String)` as the entry point Wikidata calls funnel through
- `WikiReloadService.batchReload()` needs a new prefetch step before its existing per-movie loop
- `BulkImportService.processLine()`/`runImport()` needs restructuring from "TMDB-detail-then-enrich per line" into an explicit two-pass shape (collect matched lines → resolve TMDB details/imdbIds → one SPARQL batch call → per-line OMDB/Wikipedia using cached results)

</code_context>

<specifics>
## Specific Ideas

No specific UI/wording examples given for this phase (backend-only, no user-facing surface) — open to standard approaches on SPARQL query shape and chunking.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. Two pending todos were reviewed against this phase and explicitly not folded (see below).

### Reviewed Todos (not folded)
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — UI progress indicator for batch-reload; keyword-matched ("batch", "problem") but is a UI-layer concern, unrelated to this phase's backend SPARQL lookup mechanism.
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — bulk-import results-page UI enhancements; keyword-matched ("batch") but unrelated to the Wikidata lookup mechanism this phase changes.

</deferred>

---

*Phase: 13-wikidata-sparql-batch-lookup*
*Context gathered: 2026-08-26*
