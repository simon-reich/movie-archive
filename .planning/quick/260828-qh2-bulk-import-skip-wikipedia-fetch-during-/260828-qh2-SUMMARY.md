---
phase: quick
plan: 260828-qh2
subsystem: enrichment
tags: [spring-async, bulk-import, wikipedia, wikireload, enrichment-pipeline]

# Dependency graph
requires:
  - phase: 8-wiki-enrichment-tracking-batch-reload
    provides: "WikiReloadService.batchReload() + MovieRepository.findEligibleForWikiReload() cooldown-window backfill job"
  - phase: 10-bulk-import-engine
    provides: "BulkImportService two-pass dispatch loop (Pass 1 match/save, Pass 2 enrich)"
provides:
  - "EnrichmentService.enrich(UUID, boolean skipWikipedia) overload skipping Step 3 entirely"
  - "BulkImportService Pass 2 dispatching skipWikipedia=true for every matched line"
affects: [bulk-import-performance, wiki-reload-backfill, enrichment-pipeline]

actuals:
  tokens: 6658
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "boolean flag parameter (skipWikipedia) instead of a caller-supplied pre-resolved data map, when the only purpose of the map was to skip an internal step"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
    - backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java

key-decisions:
  - "enrich(UUID, boolean skipWikipedia) replaces the old Map<String,String>-based preResolvedWikiTitles overload — bulk import no longer needs a pre-resolved Wikidata title map since it skips the Wikipedia step entirely"
  - "Pass 1.5's Wikidata SPARQL batch prefetch and Pass 1's imdbId-prefetch (resolveAndPersistImdbId) are deleted outright rather than left dormant, since their sole consumer (the Wikipedia-including Pass 2 call) no longer exists"
  - "EnrichmentIntegrationTest's single-arg-enrich Wikipedia-call-count assertion uses moreThanOrExactly(1), not exactly 1 — the missing-page WireMock stub triggers WikipediaClient's full 6-step fallback cascade, so pinning to an exact count would make the test brittle to fallback-cascade internals unrelated to this task's scope"

patterns-established: []

requirements-completed: []

coverage:
  - id: D1
    description: "enrichmentService.enrich(movieId, true) persists TMDB+OMDB data with status=SUCCESS but leaves all wiki* fields and wikiLastAttemptedAt null, making zero Wikipedia HTTP requests"
    verification:
      - kind: unit
        ref: "EnrichmentServiceTest#shouldSkipWikipedia_whenSkipWikipediaTrue"
        status: pass
      - kind: integration
        ref: "EnrichmentIntegrationTest#shouldSkipWikipedia_andBeImmediatelyEligibleForWikiReload_whenSkipWikipediaTrue"
        status: pass
    human_judgment: false
  - id: D2
    description: "A movie enriched via enrich(movieId, true) is immediately returned by MovieRepository.findEligibleForWikiReload — no cooldown wait needed"
    verification:
      - kind: integration
        ref: "EnrichmentIntegrationTest#shouldSkipWikipedia_andBeImmediatelyEligibleForWikiReload_whenSkipWikipediaTrue"
        status: pass
    human_judgment: false
  - id: D3
    description: "enrich(movieId) single-arg overload is unchanged and still fetches Wikipedia"
    verification:
      - kind: unit
        ref: "EnrichmentServiceTest#shouldSetWikiLastAttemptedAt_onWikipediaSuccess"
        status: pass
      - kind: integration
        ref: "EnrichmentIntegrationTest#shouldFetchWikipedia_whenSingleArgEnrichUsed"
        status: pass
    human_judgment: false
  - id: D4
    description: "BulkImportService.runImport()'s Pass 2 dispatches enrich(movieId, true) for every matched line; the Wikidata SPARQL batch prefetch and imdbId-prefetch are removed"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldSkipWikipediaForEveryMatchedMovie_inPass2"
        status: pass
    human_judgment: false

duration: 9min
completed: 2026-08-28
status: complete
---

# Quick Task 260828-qh2: Bulk import skips Wikipedia fetch during import Summary

**`EnrichmentService.enrich(UUID, boolean skipWikipedia)` overload replaces the old pre-resolved-titles map; `BulkImportService` Pass 2 now dispatches it with `skipWikipedia=true`, and the now-dead Wikidata SPARQL batch prefetch is deleted.**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-08-28T19:17:14+02:00 (Task 1 commit)
- **Completed:** 2026-08-28T19:26:19+02:00 (Task 2 commit)
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- `EnrichmentService` gained a `skipWikipedia` boolean overload; `doEnrich()` wraps Step 3 (Wikipedia) in `if (!skipWikipedia)`, leaving TMDB (Step 1), OMDB (Step 2), persist-SUCCESS (Step 4), and OpenSearch index (Step 5) unconditional
- The old `enrich(UUID, Map<String,String> preResolvedWikiTitles)` overload and the ternary between four-/five-argument `WikipediaClient.fetch(...)` calls are gone — the kept branch always calls the four-argument overload
- `BulkImportService.runImport()`'s Pass 2 now calls `enrichmentService.enrich(movieId, true)`; Pass 1.5's Wikidata SPARQL batch prefetch and Pass 1's `resolveAndPersistImdbId` imdbId-prefetch are deleted, along with the now-unused `movieRepository`/`wikipediaClient` fields and constructor parameters
- New tests prove: zero Wikipedia HTTP calls and immediate `findEligibleForWikiReload` eligibility under `skipWikipedia=true`; continued Wikipedia fetching under the unchanged single-arg `enrich(UUID)`; and Pass 2 dispatching `enrich(movieId, true)` for every matched line, never the single-arg overload

## Task Commits

Each task was committed atomically:

1. **Task 1: EnrichmentService — thread skipWikipedia through enrich()/doEnrich()** - `9feb25f` (feat)
2. **Task 2: BulkImportService — Pass 2 skips Wikipedia; remove the now-dead SPARQL prefetch** - `635a744` (feat)

**Plan metadata:** handled by orchestrator (docs commit not made by this executor per quick-task constraints)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` - `enrich(UUID, boolean skipWikipedia)` overload; Step 3 wrapped in `if (!skipWikipedia)`
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - Pass 2 calls `enrich(movieId, true)`; Pass 1.5 SPARQL prefetch and `resolveAndPersistImdbId` removed; `movieRepository`/`wikipediaClient` fields dropped
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` - new test for `skipWikipedia=true` (no Wikipedia call, `wikiLastAttemptedAt` stays null)
- `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java` - new WireMock-backed tests: zero Wikipedia requests + immediate wiki-reload eligibility under skip; Wikipedia still hit under single-arg `enrich(UUID)`
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - SPARQL-batching and imdbId-prefetch tests replaced with a test proving Pass 2's skip-Wikipedia dispatch

## Decisions Made
- Kept the `skipWikipedia` flag as a plain `boolean` rather than an enum/richer type — matches the plan's scope and the codebase's existing simple-parameter convention on this method
- `EnrichmentIntegrationTest#shouldFetchWikipedia_whenSingleArgEnrichUsed` asserts `moreThanOrExactly(1)` Wikipedia requests, not exactly `1` as the plan's action text literally specified — the missing-page WireMock stub causes `WikipediaClient`'s documented 6-step fallback cascade to fire on every candidate, so a full single-arg `enrich()` run against that stub legitimately makes 6 requests. Pinning to an exact count would couple this test to `WikipediaClient`'s internal fallback implementation, which is out of this plan's scope; "at least 1" preserves the test's actual intent (prove the single-arg path still hits the endpoint at all, contrasting with the 0-request skip path)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed brittle exact-count Wikipedia-request assertion in new integration test**
- **Found during:** Task 1 (EnrichmentService tests) — verification run
- **Issue:** The plan's action text specified `wireMock.verify(1, getRequestedFor(urlPathEqualTo("/w/api.php")))` for the single-arg `enrich(UUID)` test. Running it against the `@BeforeEach` missing-page stub showed `WikipediaClient` actually makes 6 requests (its documented 6-step fallback cascade, per CLAUDE.md), not 1 — the test failed with `VerificationException: Expected exactly 1 requests ... but received 6`.
- **Fix:** Changed the assertion to `wireMock.verify(moreThanOrExactly(1), ...)`, added a comment explaining why, and added the `moreThanOrExactly` static import.
- **Files modified:** backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java
- **Verification:** `EnrichmentIntegrationTest` passes (Docker-backed Testcontainers run)
- **Committed in:** 9feb25f (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug — test assertion corrected to match actual, documented fallback-cascade behavior)
**Impact on plan:** No scope creep; the fix only corrects a test assertion to match `WikipediaClient`'s pre-existing, documented behavior. Production code changes match the plan exactly.

## Issues Encountered
- Local Testcontainers cache (`~/.testcontainers.properties`) pointed at a stale `docker.client.strategy` from a prior Docker Desktop socket path that no longer exists under the current OrbStack setup, causing `EnrichmentIntegrationTest` and `WikiReloadServiceIntegrationTest` to fail with `Could not find a valid Docker environment` on the first run. Worked around by exporting `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` for the `./gradlew test` invocations in this session — this is a local machine/environment configuration issue, not a code defect, and was not otherwise touched.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Bulk import now dispatches `enrich(movieId, true)` for every matched line — a large import run no longer pays the Wikipedia 6-step/10-candidate fallback cost per movie during the synchronous dispatch loop
- Bulk-imported movies rely entirely on the existing, already-paced `WikiReloadService.batchReload()` job (Phase 8) to backfill their Wikipedia data afterward — no new scheduling/trigger work was needed since `wikiLastAttemptedAt` staying null already makes them eligible immediately
- `WikiReloadService.java`, `WikipediaClient.java`, `MovieController.java`, and `BulkImportController.java` are confirmed byte-for-byte unchanged (`git diff --stat` empty)

---
*Phase: quick*
*Completed: 2026-08-28*

## Self-Check: PASSED

All 5 modified source/test files and the SUMMARY.md itself found on disk. Both task commits (`9feb25f`, `635a744`) confirmed present in `git log`.
