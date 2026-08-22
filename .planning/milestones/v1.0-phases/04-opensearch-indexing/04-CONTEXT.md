# Phase 4: OpenSearch Indexing - Context

**Gathered:** 2026-05-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Every film persisted to Postgres by the Phase 3 enrichment pipeline is also indexed into OpenSearch with a production-ready custom analyzer and the full 40+ field mapping. The index is auto-created on first write; no manual setup required. Two admin endpoints provide index lifecycle management.

**Scope anchor:** IDX-01, IDX-02, IDX-03, IDX-04

**Explicit out-of-scope for Phase 4:**
- Search queries and results (Phase 5)
- Personal fields on the Movie entity (watched, personal_rating, personal_notes — Phase 6). Phase 4 includes these in the index mapping but always writes them as null.
- Frontend UI for the reindex operations

</domain>

<decisions>
## Implementation Decisions

### OpenSearch Write Failure Handling

- **D-01:** If OpenSearch is unreachable during the enrichment pipeline, the failure is **silent** — movie status stays `SUCCESS`, `indexed_at` remains `null`, and a warning is logged. Consistent with OMDB/Wikipedia degradation (Phase 3 D-15). The admin reindex endpoints provide the recovery path.
- **D-02:** `GET /movies/{id}/status` continues to return `status=SUCCESS` without an `indexed_at` field. Indexing state is an infrastructure detail — transparent to the user. (Same response contract established in Phase 3 D-13.)

### Admin Reindex Endpoints

- **D-03:** Both reindex endpoints are protected with **JWT auth + userId == authenticated user's ID** check. No ADMIN role, no Flyway migration for roles. The controller validates that `{userId}` in the path matches the caller's JWT subject.
- **D-04:** Two separate endpoints with distinct behaviors:
  - `POST /admin/reindex/{userId}` — **full rebuild**: delete the existing `movies-{userId}` index, recreate with fresh analyzer + mapping, reindex all Postgres movies for that user. For fixing mapping/analyzer drift.
  - `POST /admin/reindex/{userId}/pending` — **partial load**: index only films where `indexed_at IS NULL`. For recovering films that failed to index during save without disrupting the existing index.

### Personal Fields in Phase 4 Mapping

- **D-05:** Phase 4 includes `watched`, `personal_rating`, and `personal_notes` in the index mapping (exactly as specified in `.claude/data-model.md`) from day one. All three fields are written as `null` in every document until Phase 6. This avoids a mapping migration in Phase 6.
- **D-06:** Phase 6 (when it saves personal fields) **must also upsert the OpenSearch document** for that film. This is a Phase 6 integration requirement — not Phase 4 scope — but must be noted so the Phase 6 planner picks it up. Notes must be searchable per DETAIL-03.

### Claude's Discretion

- Whether `IndexingService` is its own `@Service` class or the indexing logic is added to `EnrichmentService` — whichever is cleaner given the existing code structure.
- How the document builder assembles 40+ fields from `raw_tmdb_json` / `raw_omdb_json` at index time (parsed at write, not stored as structured Postgres columns).
- Specific response body format for reindex endpoints (e.g., `{"indexed": 42}` or `{"status": "ok"}`).
- OpenSearch Testcontainers image version (must be 2.x, consistent with docker-compose.yml).
- Exact field extraction logic for computed fields (`year` from `release_date`, `imdb_link` from `imdb_id`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model & Field Mapping
- `.claude/data-model.md` — OpenSearch index strategy (`movies-{userId}`), custom analyzer spec, complete 40+ field mapping with types, analyzers, and source (TMDB/OMDB/Wikipedia/User). **Authoritative — do not deviate.**

### Requirements
- `.planning/REQUIREMENTS.md` §OpenSearch Indexing — IDX-01 through IDX-04

### Tech Stack Constraints
- `CLAUDE.md` §OpenSearch Java Client 2.19.0 — `ApacheHttpClient5Transport` (not deprecated `RestClientTransport`), client bean config, index creation pattern (`ensureIndexExists()` with existence check), document indexing builder pattern. What NOT to do.
- `CLAUDE.md` §Spring @Async+@Retryable — Enrichment pipeline pattern. Phase 4 adds OpenSearch write as Step 5 after Postgres persist. `@Retryable` may be applied to the OS client method (not the `@Async` orchestrator).

### Prior Phase Context
- `.planning/phases/03-save-movie-flow/03-CONTEXT.md` — D-11 (pipeline boundary: Postgres is last in Phase 3), D-12 (movies table with `status` + `indexed_at` columns), D-13 (status endpoint contract). Phase 4 extends the pipeline; the existing contract must not change.

### Existing Code
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — current `@Async enrich()` pipeline ends at `movieRepository.save()`. Phase 4 adds OpenSearch write after line 111 (the Postgres save + SUCCESS set block).
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — entity with `indexed_at` field (already present). Phase 4 sets this after successful OS write.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `EnrichmentService.enrich()` — Phase 4 adds Step 5 here. The injection point is after `movie.setStatus(MovieStatus.SUCCESS); movieRepository.save(movie)` (line ~111). A new `IndexingService` bean (or inline logic) is injected and called at that point.
- `backend/de/moviearchive/settings/SettingsService.java` — reference for how a service bean is structured and injected.
- Existing `AsyncConfig.java` (`enrichmentExecutor`) — the OS indexing call runs on the same thread (or a sub-call), not a separate `@Async` method.

### Established Patterns
- Testcontainers (real OpenSearch 2.x container) for integration tests — no mocking of the OS client in integration tests.
- `@Retryable` on client methods (not on `@Async` orchestrator) — per CLAUDE.md.
- Spring Data JPA `movieRepository.findAll()` / `findByUserId()` for the reindex data fetch.
- UUID-based user ID — `movies-{userId}` index names use UUID strings.

### Integration Points
- `SecurityConfig`: `/admin/**` endpoints must be authenticated (JWT required). The controller-level userId validation is the authorization layer.
- `Movie.indexedAt`: set to `Instant.now()` after successful OpenSearch write. Remains `null` on failure. Used by the `/pending` reindex endpoint to find unindexed films.
- Phase 6 integration point: when personal fields (watched, rating, notes) are saved in Phase 6, that service must call the indexing logic to upsert the OS document.

</code_context>

<specifics>
## Specific Ideas

- **Two-speed reindex design**: The user explicitly wanted two separate reindex operations — full rebuild for mapping changes, partial (unindexed only) for data recovery. Keep these as distinct endpoints with distinct behaviors, not a mode parameter.
- **Document builder parses raw JSON at index time**: The 40+ field OpenSearch document is assembled by parsing `raw_tmdb_json` and `raw_omdb_json` at indexing time. The Movie entity only stores the raw blobs + a few scalars. No new Postgres columns needed for Phase 4.

</specifics>

<deferred>
## Deferred Ideas

- **Phase 6 OS doc update on personal field save** — Not Phase 4 scope. Phase 6 planner must ensure that saving watched/rating/notes also triggers an OS document upsert for that film (D-06). Notes are searchable per DETAIL-03, so this is non-optional for Phase 6.
- **Reindex frontend UI** — Flagged as v2 (FEAT-V2-03 in REQUIREMENTS.md). Phase 4 is API-only.
- **Index rebuild with zero-downtime (blue/green alias swap)** — Overkill for a single-user personal app. Full delete+recreate is acceptable downtime.

</deferred>

---

*Phase: 04-opensearch-indexing*
*Context gathered: 2026-05-17*
