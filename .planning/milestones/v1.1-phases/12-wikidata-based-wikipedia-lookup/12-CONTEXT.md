# Phase 12: Wikidata-based Wikipedia lookup - Context

**Gathered:** 2026-08-26
**Status:** Ready for planning

<domain>
## Phase Boundary

Wikipedia lookup uses the Wikidata IMDb-ID cross-reference (property P345) first for a direct, unambiguous article resolution, instead of guessing up to 10 URL candidates; falls back to the existing candidate search when no Wikidata link exists. Independent WikipediaClient improvement — unrelated to the Bulk Import phases (8–11).

</domain>

<decisions>
## Implementation Decisions

### Resolution order
- **D-01:** New Wikidata P345 lookup is tried first inside `WikipediaClient.fetch()`, using the already-populated `movie.imdbId`. Falls through to the existing 6/10-step candidate-URL cascade only when the IMDb ID is missing, Wikidata has no P345 match, or the matched Wikidata item has no `enwiki` sitelink. — **Reversibility:** reversible — purely additive to `fetch()`, no schema change.
- **D-02:** OMDB is out of scope for this phase. Investigated whether OMDB could substitute part of the Wikipedia enrichment: it cannot — OMDB's `Plot` field is IMDb-sourced and the API contract (`.claude/api-contracts.md`) shows OMDB never maps to `wiki_plot`/`wiki_summary`/`wiki_critics`. OMDB and Wikipedia enrichment stay fully independent, no pipeline reordering.

### Rollout scope
- **D-03:** The new Wikidata-first lookup applies automatically everywhere `WikipediaClient.fetch()` is called — save flow (`EnrichmentService`), manual per-film retry (`WikiReloadService.retryWikipedia`, Phase 9), and batch-reload (`WikiReloadService.batchReload`, Phase 8). One client, one method — no path is special-cased or excluded.

### Backfill
- **D-04:** No active/forced backfill run for the ~630 previously-failed bulk-import films is part of this phase. Explicitly decided against ("das machen wir auf keinen Fall") — those films are intentionally left as real-world test material: once Phase 12 ships, the user will manually trigger retries/batch-reload on them to observe the new lookup working in practice, rather than have Phase 12 push a bulk re-enrichment itself.

### Dev visibility (Wikidata vs. fallback)
- **D-05:** Add a temporary, human-readable log of how each Wikipedia lookup was resolved — driven by the user's frustration with having zero insight into what the Wikipedia enrichment step is doing (no progress indicator, no visibility). Requirements, precisely as narrowed down during discussion:
  - **Not** the normal application log / terminal / Docker log output — a **separate** artifact the user can open and read on demand.
  - **Not** JSON or any structured/machine format — plain, easily human-readable lines.
  - One line per Wikipedia enrichment attempt, e.g. `Inception (2010): found via Wikidata` / `Inception (2010): fallback candidate #3 (Inception_(2010_film))` / `Inception (2010): not found`.
  - Explicitly framed as **temporary/dev-only** — intended to be removed again later without leaving residue (e.g. no permanent DB column, no permanent UI element). A plain appended text/log file is the right shape; keep it easy to strip out.
  - Purpose is analytical/debugging only, not a user-facing feature.

### Claude's Discretion
- Exact Wikidata query mechanism (SPARQL endpoint vs. `wbgetentities`/`wbsearchentities` REST API vs. Special:EntityData) — technical choice for research/planning.
- Exact file path/name and rotation behavior of the temporary resolution log (D-05) — as long as it's a separate, human-readable, easily-removable artifact.
- Whether the new Wikidata call reuses `WikipediaClient`'s existing `paceRequest()`/429-backoff machinery or needs its own — technical integration detail.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### API contracts
- `.claude/api-contracts.md` — OMDB and Wikipedia field mappings; confirms OMDB does not provide Wikipedia-equivalent plot/critics data (informs D-02)

### Existing enrichment pipeline
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — current 6/10-step candidate-URL cascade + search fallback; this is where the Wikidata lookup slots in first
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — `imdbId`, `wikiPlot`, `wikiSummary`, `wikiCritics`, `wikiUrl`, `wikiLastAttemptedAt` fields
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — save-flow caller of `WikipediaClient.fetch()`
- `backend/src/main/java/de/moviearchive/movie/WikiReloadService.java` (or equivalent) — manual retry (Phase 9) and batch-reload (Phase 8) callers of `WikipediaClient.fetch()`
- `backend/src/main/java/de/moviearchive/enrichment/OmdbClient.java` — reference pattern for external API client structure (`@Retryable`, `WebClient`)
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` + `backend/src/test/resources/fixtures/wikipedia/` — existing WireMock test pattern to extend with Wikidata fixtures

No external specs/ADRs beyond the above — requirements fully captured in decisions above.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `WikipediaClient.paceRequest()` / `backoffUntil` (429 handling) — reusable for the new Wikidata HTTP call to stay consistent with existing rate-limit behavior
- `movie.getImdbId()` — already populated from TMDB `external_ids.imdb_id` before the Wikipedia step runs; directly usable as the Wikidata query key

### Established Patterns
- External API clients: `@Component` + injected `WebClient.Builder`, `@Value` base URL, `@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))` on individual HTTP-calling methods — but NOT on `WikipediaClient.fetch()` itself (it already internally exhausts its own candidate cascade; wrapping the whole thing in Spring retry would re-run it needlessly). Same non-retry-at-top-level rule should apply to the new Wikidata step if it's added inside `fetch()`.
- WireMock test fixtures live under `backend/src/test/resources/fixtures/{source}/` — a new `fixtures/wikidata/` directory follows the same convention.

### Integration Points
- New Wikidata lookup is a new private method inside `WikipediaClient` (e.g. `tryFetchViaWikidata(imdbId)`), called first in `fetch()`, before `buildCandidates()`.
- No shared/reusable pacing utility class exists today — pacing logic is duplicated per-caller (`WikipediaClient.paceRequest()`, `WikiReloadService.batchReload()`'s `Thread.sleep`). Not required to be refactored for this phase.

</code_context>

<specifics>
## Specific Ideas

- Dev-visibility log line examples given by the user during discussion: `"Inception (2010): gefunden über Wikidata"` / `"Inception (2010): Fallback-Kandidat #3 (Inception_(2010_film))"` — plain German or English text is fine, the key requirement is human-readability, not a specific language.

</specifics>

<deferred>
## Deferred Ideas

- Active backfill/re-enrichment trigger for the ~630 films missing Wikipedia data — explicitly rejected for this phase (see D-04); the user wants to observe the new lookup organically via manual retry/existing batch-reload instead.
- Todos matched against this phase by keyword (`enhance bulk import batch detail page`, `support real CSV parsing for bulk import`, `show progress indicator for Wikipedia batch-reload`) were reviewed and explicitly NOT folded — all three are Bulk Import (Phase 8–11) topics, out of scope for this independent WikipediaClient phase.

### Reviewed Todos (not folded)
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — Bulk Import UI enhancement, unrelated to Wikipedia lookup mechanism
- `2026-08-24-support-real-csv-parsing-for-bulk-import` — Bulk Import parsing, unrelated
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — UI progress indicator for batch-reload; conceptually adjacent (also "visibility into Wikipedia enrichment") but scoped as a Batch-Reload UI feature, not this phase's temporary dev-log (D-05). Revisit after Phase 12 ships, once it's clear whether the rate-limiting problem the progress indicator was compensating for is still an issue.

</deferred>

---

*Phase: 12-wikidata-based-wikipedia-lookup*
*Context gathered: 2026-08-26*
