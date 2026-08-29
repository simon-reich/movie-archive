# Phase 8: Wiki Enrichment Tracking & Batch Reload - Context

**Gathered:** 2026-08-22
**Status:** Ready for planning

<domain>
## Phase Boundary

The system tracks every Wikipedia enrichment attempt per film (success or failure) with a `wiki_last_attempted_at` timestamp, and exposes a batch-reload mechanism that retries only films missing Wikipedia data whose last attempt is outside a cooldown window — paced so it does not re-trigger Wikipedia rate limiting.

**Scope anchor:** ENRICH-01 (attempt timestamp), ENRICH-02 (batch-reload endpoint with cooldown filter), ENRICH-03 (paced processing).

**Explicit out-of-scope for Phase 8:**
- Manual per-film retry button on the detail page — that's ENRICH-04/05, Phase 9
- Bulk import as an in-app feature (file upload, live progress UI) — Phase 10/11
- Live progress UI for the batch-reload run itself — not requested by ENRICH-01..03; only Phase 11 (IMPORT-05/06) gets a progress UI, and that's for the import flow, not this reload job
- Scheduled/automatic batch-reload — not requested; this phase only builds an admin-triggered endpoint

</domain>

<decisions>
## Implementation Decisions

### Retry Scope & Re-indexing

- **D-01:** Batch-reload retries **only the Wikipedia step**, not TMDB or OMDB. A new lean method (e.g. `retryWikipedia(movieId)` on `EnrichmentService` or a new service) reuses the existing `WikipediaClient` 6-step fallback. TMDB/OMDB data and the movie's `status` (SUCCESS/ERROR from the original save) are left untouched — this phase never re-fetches those.
- **D-02:** When a late Wikipedia fetch succeeds, the film is **re-indexed in OpenSearch** (reuse `IndexingService`, same as the existing enrichment pipeline's final step) and `indexed_at` is updated, so `wikipedia_summary`/`wikipedia_plot`/`wikipedia_critics` become searchable (SRCH-01 depends on the OpenSearch index, not Postgres directly). — **Reversibility:** reversible — re-indexing is idempotent and can be re-run via the existing `/admin/reindex/{userId}` endpoint if ever needed.

### Cooldown Window

- **D-03:** Cooldown duration is **30 days** by default — a film whose last Wikipedia attempt (success-with-no-page-found or failure) was less than 30 days ago is skipped by batch-reload; films never attempted, or attempted more than 30 days ago, are eligible.
- **D-04:** The cooldown value is **configurable via an application property** (e.g. `wiki.retry.cooldown-days=30` in `application.properties`, overridable via ENV), matching the project's existing ENV-driven config convention (`JWT_SECRET`, `ENCRYPTION_MASTER_KEY`).

### Trigger & Execution Model

- **D-05:** Batch-reload runs as **fire-and-forget async** (`@Async`, same pattern as the existing enrichment pipeline's bounded thread pool) — the endpoint returns immediately; the job iterates eligible films sequentially with the pacing delay in the background. No live progress tracking in this phase (progress UI is Phase 11's concern, for the import flow, not this endpoint) — progress is visible only via logs. — **Reversibility:** reversible — internal implementation detail, no published contract depends on it being sync.
- **D-06:** Batch-reload is triggered via an **admin endpoint only** in this phase — `POST /admin/wiki-reload/{userId}`, same authenticated-admin style as the existing `POST /admin/reindex/{userId}`. No dedicated UI button yet (the manual per-film retry button is Phase 9; bulk-import UI is Phase 10/11). No scheduled/automatic triggering — not requested by ENRICH-01..03.

### Pacing Delay

- **D-07:** Pacing delay between Wikipedia calls during a batch run is **1 second** by default. This is the root-cause knob from the original incident (~89% of ~630 bulk-imported films silent-failed from rate limiting) — 1s is conservative-but-not-glacial (a 630-film run takes ~10.5 minutes).
- **D-08:** The pacing delay is **configurable via an application property** (e.g. `wiki.retry.pacing-delay-ms=1000`), consistent with the cooldown-days property — tunable without a redeploy if Wikipedia's actual rate limits turn out to need adjustment.

### Claude's Discretion

- Exact naming of the new property keys (`wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` are suggestions, not locked).
- Exact naming of the new service method(s) and repository query for "films missing wiki data outside cooldown".
- Whether the sequential pacing loop uses `Thread.sleep` inside the `@Async` batch method or a scheduled-delay mechanism — implementation detail, not user-facing.
- Exact Flyway migration version number for the new `wiki_last_attempted_at` column.
- Whether "missing Wikipedia data" is determined by `wiki_url IS NULL` (the existing convention — no Wikipedia match sets no wiki fields) — this is the natural existing signal and doesn't need a new status field.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model
- `.claude/data-model.md` — `movies` table schema (existing `wiki_plot`, `wiki_summary`, `wiki_critics`, `wiki_url` columns; OpenSearch field mapping for `wikipedia_summary`/`wikipedia_plot`/`wikipedia_critics`, all `custom_english_analyzer`). Phase 8 adds `wiki_last_attempted_at` via a new Flyway migration.

### API Contracts
- `.claude/api-contracts.md` §Wikipedia API — 6-step title fallback strategy, extracted fields, retry policy (`@Retryable` on TMDB/OMDB/Wikipedia clients, not on the `@Async` orchestrating method).

### Tech Stack Constraints
- `CLAUDE.md` §Spring @Async+@Retryable — bounded thread pool, no self-invocation, `@Retryable` never on the `@Async` method itself.
- `CLAUDE.md` §Wikipedia 6-step fallback — exact fallback URL construction, already implemented in `WikipediaClient`.

### Requirements
- `.planning/REQUIREMENTS.md` §Enrichment Reliability — ENRICH-01, ENRICH-02, ENRICH-03 (this phase); ENRICH-04, ENRICH-05 deferred to Phase 9.
- `.planning/PROJECT.md` — v1.1 milestone goal and trigger incident (bulk import of ~630 films → ~89% missing wiki data from rate limiting).

### Prior Phase Context
- `.planning/milestones/v1.0-phases/03-save-movie-flow/03-CONTEXT.md` — original async enrichment pipeline decisions (D-11 through D-15): TMDB → OMDB → Wikipedia → Postgres, silent Wikipedia failure, `@Async`/`@Retryable` wiring.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — existing `enrich(UUID movieId)` `@Async` method; Wikipedia step (lines ~93-101) is the exact logic to extract/reuse for the new Wikipedia-only retry method. Must be called from a different bean than the batch loop itself if `@Async` self-invocation would otherwise apply.
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — 6-step fallback fetch, already `@Retryable`. Call directly for the retry path.
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaResult.java`, `WikipediaNotFoundException.java` — result/exception types to reuse as-is.
- `backend/src/main/java/de/moviearchive/admin/ReindexController.java` + underlying `IndexingService` (`backend/src/main/java/de/moviearchive/indexing/IndexingService.java`) — direct structural analog for the new `/admin/wiki-reload/{userId}` endpoint: `reindexPending(UUID userId)` shows the "find eligible movies for this user, loop, log per-movie warnings on failure" pattern to copy for the wiki-reload batch loop. Note `ReindexController`'s existing methods are synchronous (D-05 deliberately diverges to async for wiki-reload because of the pacing delay).
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — has an existing "not yet indexed" query pattern (`findByIndexedAtIsNull`-style, per the `IndexingService` comment "Used by partial reindex"); the new "missing wiki data outside cooldown" query should follow the same repository-query style.
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — entity to extend with the new `wikiLastAttemptedAt` (`Instant`) field, alongside existing `wikiUrl`/`wikiPlot`/`wikiSummary`/`wikiCritics`.

### Established Patterns
- `@Async("enrichmentExecutor")` bounded thread pool already configured — reuse the same executor bean for the batch-reload job, or confirm whether a separate bounded executor is warranted for a long-running batch vs per-request enrichment (planner's call).
- Flyway migrations are sequential `V{n}__description.sql` in `backend/src/main/resources/db/migration/` (V7 was the last one applied per CLAUDE.md's original phase list — planner should check the actual highest existing version at implementation time).
- `application.properties` + ENV var overrides is the existing config convention (`JWT_SECRET`, `ENCRYPTION_MASTER_KEY`) — extend with `wiki.retry.cooldown-days` and `wiki.retry.pacing-delay-ms`.
- Admin endpoints live under `/admin/**`, authenticated, in `de.moviearchive.admin` package (`ReindexController` is the existing occupant).

### Integration Points
- `SecurityConfig` — `/admin/**` is already authenticated (confirm `/admin/wiki-reload/**` inherits the same rule, no new config needed if the path prefix matches).
- `IndexingService` — the re-index call after a successful late wiki fetch should follow the same "index, then set `indexed_at`, log warning and continue silently on failure" pattern as `EnrichmentService.enrich()`'s Step 5.

</code_context>

<specifics>
## Specific Ideas

- **The 630-film incident is the design driver**: batch-reload must be safe to run against the entire backlog from that incident without re-triggering the same rate limiting. Pacing (1s default, configurable) and cooldown (30 days default, configurable) are the two knobs that directly address this.
- **Narrow retry, not full re-enrichment**: only Wikipedia is retried because only Wikipedia failed. TMDB/OMDB data that already succeeded should never be touched or re-fetched by this phase's code.
- **No new status enum needed**: "missing Wikipedia data" is inferred from `wiki_url IS NULL` (the existing signal that no Wikipedia match was found/saved) — no new column beyond `wiki_last_attempted_at`.

</specifics>

<deferred>
## Deferred Ideas

- **Manual per-film retry button** — Phase 9 (ENRICH-04, ENRICH-05).
- **Live progress UI for batch-reload** — not requested for this endpoint; Phase 11's progress UI (IMPORT-05/06) is specifically for the bulk-import flow, not this admin job.
- **Scheduled/automatic batch-reload** — considered during discussion, explicitly rejected: this phase only builds an admin-triggered endpoint, not a background scheduler.

### Reviewed Todos (not folded)
None — no matching todos were found (`todo.match-phase` returned 0 matches).

</deferred>

---

*Phase: 8-wiki-enrichment-tracking-batch-reload*
*Context gathered: 2026-08-22*
