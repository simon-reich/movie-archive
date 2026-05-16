---
plan: 03-04
phase: 03-save-movie-flow
status: complete
completed: 2026-05-16
commits:
  - 86b57c5: feat(03-04): TmdbClient, OmdbClient, WikipediaClient with retryable + 6-step fallback
  - 88a8b39: feat(03-04): EnrichmentService async orchestrator + unit and integration tests
self_check: PASSED
---

## What Was Built

Full async enrichment pipeline: TmdbClient (search + fetchDetail), OmdbClient (optional, OMDB key-gated), WikipediaClient (6-step fallback), and EnrichmentService `@Async` orchestrator that runs TMDB → OMDB → Wikipedia → persist → status update.

## Key Files Created

### Enrichment Clients
- `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java` — WebClient search + fetchDetail with `@Retryable(maxAttempts=3, backoff=exponential)`
- `backend/src/main/java/de/moviearchive/enrichment/OmdbClient.java` — fetch by imdb_id with `@Retryable`, skips gracefully if no key or no imdb_id
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — 6-step fallback (`{Title}_{Year}_film` → `{Title}_(film)` → `{Title}` → same with originalTitle), throws `WikipediaNotFoundException` when all candidates miss
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaNotFoundException.java` — used as `noRetryFor` target
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaResult.java` — record holding plot, summary, critics, url

### Enrichment Orchestrator
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — `@Async("enrichmentExecutor")` enrich method: calls TmdbClient → stores rawTmdbJson → extracts imdbId → calls OmdbClient (guarded) → calls WikipediaClient (try/catch, non-fatal) → persists Movie with SUCCESS status; sets ERROR on TMDB failure

### Configuration
- `backend/src/main/resources/application.properties` — added `tmdb.base-url`, `omdb.base-url`, `wikipedia.base-url` properties

### Repository Update
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — added `findByIdWithUser` JOIN FETCH query for enrichment

### Tests (replacing @Disabled stubs from 03-01)
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` — 2 real tests: first-candidate hit, all-candidates-miss
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` — 3 Mockito unit tests: OMDB skip (no key), OMDB skip (no imdbId), Wikipedia failure non-fatal
- `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java` — 3 WireMock integration tests: full pipeline persist, Wikipedia failure still saves, status transitions to SUCCESS

## Verification

- `./gradlew compileJava` — zero errors
- Enrichment pipeline follows CLAUDE.md constraints: `@Retryable` on clients only (not on `@Async` method), OMDB graceful degradation, Wikipedia non-fatal

## Deviations

- Added `WikipediaResult.java` record (not in plan file list) to cleanly pass Wikipedia data from client to service — minor addition, no scope creep.
