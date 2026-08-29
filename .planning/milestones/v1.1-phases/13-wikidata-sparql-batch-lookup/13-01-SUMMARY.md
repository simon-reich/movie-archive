---
phase: 13-wikidata-sparql-batch-lookup
plan: 01
subsystem: api
tags: [wikidata, sparql, wikipedia, webclient, wiremock, enrichment]

# Dependency graph
requires:
  - phase: 12-wikidata-based-wikipedia-lookup
    provides: "Wikidata-first Wikipedia resolution order (D-01), the dev-visibility resolution log later removed here (D-05), and the paceRequest/backoffUntil/recordRateLimited 429-handling machinery reused unchanged by the new SPARQL call"
provides:
  - "WikipediaClient.resolveViaWikidataSparql(List<String>) -> Map<String,String> — the single Wikidata resolution entry point for all callers"
  - "WikipediaClient.fetch(originalTitle, title, year, imdbId, Map<String,String> preResolvedTitles) — the batch-prefetch-aware fetch() overload Plan 2 (WikiReloadService.batchReload) and Plan 3 (BulkImportService/EnrichmentService) depend on"
  - "wikidata.sparql-base-url application property bound to https://query.wikidata.org"
affects: [13-wikidata-sparql-batch-lookup (Plan 2: WikiReloadService.batchReload prefetch restructuring), 13-wikidata-sparql-batch-lookup (Plan 3: BulkImportService two-pass restructuring)]

actuals:
  tokens: 9974
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Batched SPARQL VALUES-clause query resolving multiple IMDb IDs to enwiki titles in one HTTP round-trip, chunked at 50 ids/request"
    - "Optional prefetched-map parameter overload (fetch(..., Map<String,String>)) letting batch callers skip per-movie external calls while single-movie callers keep the original signature unchanged"

key-files:
  created:
    - backend/src/test/resources/fixtures/wikidata-sparql/batch-found.json
    - backend/src/test/resources/fixtures/wikidata-sparql/batch-partial.json
    - backend/src/test/resources/fixtures/wikidata-sparql/batch-empty.json
  modified:
    - backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
    - backend/src/main/resources/application.properties
    - backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java

key-decisions:
  - "Chunking lives inside WikipediaClient.resolveViaWikidataSparql itself (not in each caller) so WikiReloadService/BulkImportService can pass an arbitrarily large imdbId list without knowing the chunk-size constant — keeps the 'one client, one method' pattern from Phase 12's D-03 intact, per RESEARCH.md Open Question #2's recommendation"
  - "SPARQL WebClient uses a more specific User-Agent (MovieArchive/0.1 (https://github.com/simon-reich/movie-archive)) than the other two clients, per Wikimedia's User-Agent policy for the Query Service (RESEARCH.md Pitfall 3)"

patterns-established:
  - "Batch-resolution entry point returns Map<String,String> with misses simply absent (never throws) — callers treat absence as 'fall through to the existing fallback', letting the resolution boundary stay side-effect-free"

requirements-completed: [D-01, D-04]

coverage:
  - id: D1
    description: "WikipediaClient exposes exactly one Wikidata resolution method (resolveViaWikidataSparql) used by both fetch() overloads; the REST-era two-call method (tryFetchViaWikidata) no longer exists in the source file"
    requirement: "D-01"
    verification:
      - kind: unit
        ref: "WikipediaClientTest#shouldReturnResult_viaWikidata_whenImdbIdMatchesP345 — status: pass"
        status: pass
      - kind: other
        ref: "grep -cE 'tryFetchViaWikidata|logResolution|resolutionLogPath|wikidataWebClient' src/main/java/de/moviearchive/enrichment/WikipediaClient.java — returns 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "A batch of N IMDb IDs resolves in ceil(N/50) SPARQL requests, never N requests; an empty IMDb-ID list makes zero HTTP calls"
    requirement: "D-01"
    verification:
      - kind: unit
        ref: "WikipediaClientTest#shouldChunkRequests_whenMoreThanFiftyImdbIds — status: pass"
        status: pass
    human_judgment: false
  - id: D3
    description: "When a caller supplies a pre-resolved title map (even empty), fetch() never issues an additional per-movie SPARQL call — hit or miss both skip the network call"
    requirement: "D-01"
    verification:
      - kind: unit
        ref: "WikipediaClientTest#shouldSkipSparqlCall_whenPreResolvedMapProvidedAndImdbIdAbsent — status: pass"
        status: pass
      - kind: unit
        ref: "WikipediaClientTest#shouldUsePreResolvedTitle_whenPresentInMap — status: pass"
        status: pass
    human_judgment: false
  - id: D4
    description: "The temporary wiki-resolution.log dev-visibility logging (logResolution, resolutionLogPath, and all call sites) is fully removed"
    requirement: "D-04"
    verification:
      - kind: other
        ref: "grep -cE 'tryFetchViaWikidata|logResolution|resolutionLogPath|wikidataWebClient' src/main/java/de/moviearchive/enrichment/WikipediaClient.java — returns 0"
        status: pass
    human_judgment: false
  - id: D5
    description: "A 429 from the SPARQL endpoint engages the same shared backoff window (recordRateLimited/backoffUntil) every other WikipediaClient method already writes to, not a separate/unpaced path"
    verification:
      - kind: unit
        ref: "WikipediaClientTest#shouldHonorRetryAfterBackoff_onSparqlCall — status: pass"
        status: pass
    human_judgment: false
  - id: D6
    description: "The SPARQL VALUES-clause query shape (wdt:P345 + schema:about/isPartOf/name) is syntactically correct and resolves IMDb IDs to enwiki titles against the real query.wikidata.org endpoint, not just against WireMock fixtures"
    verification:
      - kind: manual_procedural
        ref: "curl smoke test against https://query.wikidata.org/sparql with tt1375666 — returned articleName 'Inception', confirming the exact query template used in resolveChunkViaWikidataSparql"
        status: pass
    human_judgment: false

duration: 22min
completed: 2026-08-27
status: complete
---

# Phase 13 Plan 1: SPARQL Batch Wikidata Lookup Summary

**Replaced WikipediaClient's per-movie two-call REST Wikidata lookup (CirrusSearch search + `www.wikidata.org` REST sitelinks) with a single batched SPARQL query against `query.wikidata.org` that resolves up to 50 IMDb IDs to enwiki article titles per request, and deleted the temporary Phase-12 dev-visibility resolution log entirely.**

## Performance

- **Duration:** 22 min
- **Started:** 2026-08-27T10:19:00Z (approx, worktree HEAD assertion)
- **Completed:** 2026-08-27T10:41:00Z (approx)
- **Tasks:** 2
- **Files modified:** 9 (3 modified source/config, 1 modified test, 3 new fixtures, 3 deleted fixtures)

## Accomplishments
- `WikipediaClient.resolveViaWikidataSparql(List<String>)` resolves any number of IMDb IDs to enwiki titles via one or more chunked SPARQL requests (50 ids/chunk), returning zero HTTP calls for an empty/all-null-filtered input list
- New `fetch(originalTitle, title, year, imdbId, Map<String,String> preResolvedTitles)` overload lets batch callers (Plan 2's `WikiReloadService.batchReload`, Plan 3's `BulkImportService`) skip a per-movie SPARQL call entirely once they've prefetched a batch map — a miss in that map falls straight through to the existing candidate cascade, exactly as a SPARQL-miss would
- Deleted the REST-era `tryFetchViaWikidata()` (two paced calls against `www.wikidata.org`: CirrusSearch `haswbstatement:P345` search, then a REST sitelinks lookup) and the Phase-12 dev-visibility `logResolution()`/`resolutionLogPath` scaffolding, including all 4 call sites inside `fetch()`
- Live-verified the exact SPARQL query template (`VALUES` + `wdt:P345` + `schema:about`/`isPartOf`/`name`) against the real `query.wikidata.org/sparql` endpoint via `curl` — confirmed it returns `Inception` for `tt1375666`, matching this plan's WireMock fixture verbatim
- `WikipediaClientTest` grew from 7 tests (4 remaining after the REST-era deletions + 3 new in Task 1) to 10 tests total, covering the happy path, partial/empty batch responses, the 51-id chunk boundary, both directions of the prefetch-map skip, and 429 backoff on `/sparql`

## Task Commits

Each task was committed atomically:

1. **Task 1: SPARQL batch resolution replaces the two-call REST Wikidata lookup (D-01, D-04)** - `a294af5` (feat)
2. **Task 2: Batch, chunk, prefetch-map, and 429 test coverage + D-01/D-04 residue check** - `bb1ee32` (test)

**Plan metadata:** committed as part of this SUMMARY (worktree mode — orchestrator finalizes STATE.md/ROADMAP.md after merge)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` - New `sparqlWebClient` bean bound to `wikidata.sparql-base-url`; `resolveViaWikidataSparql`, `resolveChunkViaWikidataSparql`, `resolveWikidataResult`, `chunk` (new methods); `fetch(..., Map)` overload; `tryFetchViaWikidata`, `logResolution`, `resolutionLogPath`, `wikidataWebClient` all deleted
- `backend/src/main/resources/application.properties` - `wikidata.base-url` replaced by `wikidata.sparql-base-url=${WIKIDATA_SPARQL_BASE_URL:https://query.wikidata.org}`; `wiki.resolution-log.path` property deleted
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` - Rewrote the Wikidata happy-path test against `/sparql`; added 6 new SPARQL-era test methods; removed 3 REST-era-only tests
- `backend/src/test/resources/fixtures/wikidata-sparql/batch-found.json` - SPARQL JSON Results fixture, single matched id (tt1375666 -> Inception)
- `backend/src/test/resources/fixtures/wikidata-sparql/batch-partial.json` - Two matched ids (tt1375666, tt0133093), simulating a batch where some ids don't resolve
- `backend/src/test/resources/fixtures/wikidata-sparql/batch-empty.json` - Zero bindings, simulating a batch where no ids resolve
- `backend/src/test/resources/fixtures/wikidata/search-found.json`, `search-not-found.json`, `sitelinks-found.json` - Deleted (dead REST-era fixtures, nothing references them after the rewrite)

## Decisions Made
- Chunking logic lives inside `WikipediaClient.resolveViaWikidataSparql` itself rather than in each caller (WikiReloadService/BulkImportService), so Plan 2/3 can hand it an arbitrarily large IMDb-ID list without duplicating a chunk-size constant — matches RESEARCH.md Open Question #2's recommendation and Phase 12's "one client, one method" pattern (D-03)
- The new SPARQL `WebClient` uses a more specific User-Agent (`MovieArchive/0.1 (https://github.com/simon-reich/movie-archive)`) than the other two clients' bare `MovieArchive/0.1`, per Wikimedia's User-Agent policy for the Query Service (RESEARCH.md Pitfall 3) — a low-cost risk reduction given this project's two prior live rate-limit incidents

## Deviations from Plan

None - plan executed exactly as written. All acceptance criteria for both tasks were met, including the residue-check grep returning 0 and the tracer feedback gate (Task 1) re-verified end-to-end before Task 2's expansion began.

## Issues Encountered

Running the full backend test suite (`./gradlew test`, beyond what this plan's own `<verify>` blocks require) surfaced Postgres connection-pool exhaustion (`FATAL: sorry, too many clients already`) across unrelated test classes (`SettingsIntegrationTest`, `MovieControllerTest`, `BulkImportControllerTest`) when many concurrent Spring/Testcontainers contexts hit this sandboxed environment's shared Postgres container at once. This is a pre-existing environment resource limit unrelated to this plan's changes — `WikipediaClient` touches no database/connection-pool code, and the failure reproduces identically across classes this plan never modified. The plan's own required verification command, `./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"`, passes cleanly both in isolation and immediately after each task's changes. Also required setting `DOCKER_HOST` explicitly for this machine's OrbStack-based Docker context — Testcontainers' auto-detection did not pick up the OrbStack socket without it; a one-time environment quirk, not a code change.

## User Setup Required

None - no external service configuration required. The SPARQL endpoint (`query.wikidata.org`) is public and unauthenticated, same as the REST endpoint it replaces.

## Next Phase Readiness

- `WikipediaClient.resolveViaWikidataSparql(List<String>)` and the `fetch(..., Map<String,String>)` overload are the exact public API surface Plan 2 (`WikiReloadService.batchReload` prefetch restructuring, D-02) and Plan 3 (`BulkImportService` two-pass restructuring, D-03) depend on — both can now call the batch method once (or a few chunked times) before their per-movie loops, and pass the resulting map straight into `fetch()`.
- No blockers. The live `curl` smoke test against the real `query.wikidata.org/sparql` endpoint (per this plan's `<verification>` requirement) confirms the query template works exactly as fixture-tested, de-risking Plan 2/3's reliance on this method actually resolving real Wikidata data in production.

## Self-Check: PASSED

All files created/modified verified present on disk (WikipediaClient.java, application.properties, WikipediaClientTest.java, and all 3 wikidata-sparql fixtures). All 3 commits (a294af5, bb1ee32, fde93c3) verified present in `git log --oneline --all`.

---
*Phase: 13-wikidata-sparql-batch-lookup*
*Completed: 2026-08-27*
