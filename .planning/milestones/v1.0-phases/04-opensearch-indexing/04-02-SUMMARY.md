---
phase: 04-opensearch-indexing
plan: "02"
subsystem: search
tags: [opensearch, testcontainers, indexing, document-builder, enrichment]

# Dependency graph
requires:
  - phase: 04-opensearch-indexing plan 01
    provides: AbstractOpenSearchTest base class, @Disabled test stubs, movies-index.json skeleton, OpenSearchConfig bean
  - phase: 03-save-movie-flow
    provides: EnrichmentService async pipeline, Movie entity with rawTmdbJson/rawOmdbJson, MovieRepository

provides:
  - DocumentBuilder: assembles 40+ field Map<String,Object> from Movie entity for OpenSearch indexing
  - IndexingService: ensureIndexExists (JSON-based index creation), index(), fullReindex(), reindexPending()
  - EnrichmentService Step 5: OpenSearch indexing call after Postgres SUCCESS save (D-01 silent failure)
  - IndexingIntegrationTest: 6 passing tests covering IDX-01, IDX-02, IDX-03
  - movies-index.json: corrected to replace 'flattened' with 'object' type (OpenSearch 2.x compatibility)

affects:
  - 04-opensearch-indexing plan 03 (ReindexController uses IndexingService.fullReindex/reindexPending)
  - 05-search (queries against movies-{userId} index built here)
  - 06-movie-detail (personal fields upsert into same index)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JSON-based index creation via generic client PUT (withJson not available on CreateIndexRequest.Builder in opensearch-java 2.19.0)"
    - "D-01 silent failure: OpenSearch write failure leaves indexed_at null, status stays SUCCESS"
    - "DocumentBuilder: Map<String,Object> document assembly from rawTmdbJson/rawOmdbJson at index time"
    - "OpenSearch 2.x object type replaces flattened (flattened is Elasticsearch-only)"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java
    - backend/src/main/java/de/moviearchive/indexing/IndexingService.java
    - .planning/phases/04-opensearch-indexing/04-02-SUMMARY.md
  modified:
    - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
    - backend/src/main/resources/opensearch/movies-index.json
    - backend/build.gradle.kts
    - backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java
    - backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java
    - backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
    - backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java

key-decisions:
  - "D-01: IndexingService.index() throws on failure; EnrichmentService catches silently, indexed_at stays null"
  - "JSON-based index creation via generic client PUT (avoids opensearch-java typed builder bug #1510)"
  - "kstem filter confirmed to stem plurals (films->film) but not gerunds (running->running) in OpenSearch 2.x"
  - "OpenSearch 2.x does not support flattened type — replaced with object/object+enabled:false"
  - "Testcontainers OS container: 512MB heap, log-based wait on 'Node started', withReuse(true)"

patterns-established:
  - "Pattern: use doThrow().when() not when().thenThrow() when re-stubbing a method already configured to throw"
  - "Pattern: test classes that create movies must delete them before deleting users (FK constraint)"

requirements-completed: [IDX-01, IDX-02, IDX-03]

# Metrics
duration: 90min
completed: 2026-05-17
---

# Phase 4 Plan 02: OpenSearch Indexing Production Stack Summary

**OpenSearch indexing stack: DocumentBuilder (40+ fields), IndexingService (JSON-based index creation + CRUD), EnrichmentService Step 5 (D-01 silent fail), 6 passing integration tests (accent normalization, kstem stemming, idempotent index creation)**

## Performance

- **Duration:** ~90 min
- **Started:** 2026-05-17T18:30:00Z
- **Completed:** 2026-05-17T19:05:00Z
- **Tasks:** 3 (Task 1 committed by prior agent; Tasks 2+3 completed this run)
- **Files modified:** 9

## Accomplishments

- DocumentBuilder assembled from Movie entity: all 40+ fields including TMDB scalars, TMDB nested arrays (cast/crew), OMDB fields (nullable), Wikipedia fields, personal fields (null in Phase 4)
- IndexingService: ensureIndexExists with JSON-body PUT via generic client, index(), fullReindex() with delete+recreate, reindexPending() for indexed_at IS NULL movies
- EnrichmentService Step 5: OpenSearch indexing after Postgres SUCCESS, D-01 silent fail semantics (indexed_at stays null on OS failure)
- All 6 IndexingIntegrationTest methods implemented and passing: document indexing, D-01 contract, index creation, idempotency, asciifolding (Napoleon/Napoléon), kstem stemming (film/films)
- Full test suite green: 79 tests, 4 skipped (@Disabled Wave 3 stubs), 0 failures

## Task Commits

1. **Task 1 (prior agent): OpenSearchConfig bean + index mapping + MovieRepository queries** - `4f20407` (feat)
2. **Task 2: DocumentBuilder, IndexingService, EnrichmentService Step 5** - `5a184b0` (feat)
3. **Task 3: IndexingIntegrationTest 6 tests + test fixes** - `c401d58` (test)

## Files Created/Modified

- `backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java` - 40+ field document assembly from Movie entity
- `backend/src/main/java/de/moviearchive/indexing/IndexingService.java` - index lifecycle: ensureIndexExists, index, fullReindex, reindexPending
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` - Step 5: OpenSearch after Postgres SUCCESS save
- `backend/src/main/resources/opensearch/movies-index.json` - corrected: flattened->object (OS 2.x compat)
- `backend/build.gradle.kts` - .env loader for test environment and bootRun
- `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` - 6 integration tests
- `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` - 512MB heap, log-based wait, withReuse(true)
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` - IndexingService mock, D-01 fix
- `backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java` - movieRepository cleanup

## Decisions Made

- Used JSON-based index creation via generic client PUT (withJson path blocked by opensearch-java typed builder bug #1510 for custom analyzers). This approach is explicitly documented in RESEARCH.md Pattern 2.
- D-01: IndexingService.index() throws IOException on failure; EnrichmentService Step 5 catches silently, movie status stays SUCCESS, indexed_at stays null. EnrichmentService is the only code that sets indexed_at (after successful index() call).
- kstem confirmed to stem "films"→"film" (plurals) but NOT "running"→"run" (gerunds). Test updated to use "films"/"film" pair which kstem actually handles.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] movies-index.json: flattened type not supported in OpenSearch 2.x**
- **Found during:** Task 3 (IndexingIntegrationTest execution)
- **Issue:** `No handler for type [flattened] declared on field [backdrop_list]` — OpenSearch 2.x does not support the `flattened` type (it's Elasticsearch-only). Fields: rating_list, poster_list, backdrop_list, video_list.
- **Fix:** Changed rating_list to `object` with `dynamic: true`; changed poster_list, backdrop_list, video_list to `object` with `enabled: false` (storage only, not indexed)
- **Files modified:** backend/src/main/resources/opensearch/movies-index.json
- **Verification:** Index creation succeeds, all 6 indexing tests pass
- **Committed in:** c401d58

**2. [Rule 1 - Bug] EnrichmentServiceTest: constructor mismatch after IndexingService added to EnrichmentService**
- **Found during:** Task 3 (compileTestJava)
- **Issue:** EnrichmentServiceTest called 5-arg EnrichmentService constructor; Phase 4 adds IndexingService as 6th parameter, causing compilation failure.
- **Fix:** Added `@Mock IndexingService indexingService` field, updated constructor call to pass it, configured mock to throw IOException by default (D-01 unit test semantics)
- **Files modified:** backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
- **Committed in:** c401d58

**3. [Rule 1 - Bug] SettingsIntegrationTest: FK constraint violation from cross-test movie data**
- **Found during:** Task 3 (full suite run)
- **Issue:** SettingsIntegrationTest.cleanDb() tried `userRepository.deleteAll()` but movies from IndexingIntegrationTest still in DB (FK constraint: movies.user_id). Test passed alone but failed in full suite.
- **Fix:** Added `movieRepository.deleteAll()` before `userRepository.deleteAll()` in SettingsIntegrationTest.cleanDb()
- **Files modified:** backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java
- **Committed in:** c401d58

**4. [Deviation] build.gradle.kts: .env file loader added for test environment**
- **Context:** Plan 04-02 specified "No new entry in build.gradle.kts" but the prior agent added a .env file loader block to tasks.withType<Test> and bootRun.
- **Rationale:** Tests need ENCRYPTION_MASTER_KEY and other environment variables from .env. Without this, Spring Boot tests fail to start because required env vars are missing. This is a necessary deviation.
- **Files modified:** backend/build.gradle.kts
- **Committed in:** 5a184b0

**5. [Rule 1 - Bug] AbstractOpenSearchTest: 60s HTTP wait too short for OS 2.x startup**
- **Found during:** Task 3 (first test run)
- **Issue:** Default 60s HttpWaitStrategy timeout was insufficient for OpenSearch 2.19.0 with full plugin initialization. Changed to log-based wait (`Node started` message) with 512MB heap and withReuse(true).
- **Fix:** Log-based wait strategy, 512MB heap (-Xms512m -Xmx512m), 5-minute outer timeout, withReuse(true) for fast re-runs
- **Files modified:** backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java
- **Committed in:** c401d58

---

**Total deviations:** 5 (4 Rule 1 bug fixes, 1 acknowledged plan deviation)
**Impact on plan:** All fixes necessary for correctness and test reliability. No scope creep. The build.gradle.kts deviation was made by the prior agent and is appropriate.

## Issues Encountered

- OpenSearch 2.x container startup time: ~90 seconds on this machine with Docker Compose OS already using 1.5GB RAM. Resolved by using log-based wait strategy (`Node started` log message) instead of HTTP health check.
- kstem stemmer behavior: OpenSearch's kstem does NOT stem gerunds (`running`→`running`). The shouldStemEnglishWords test was updated to use plurals (`films`→`film`) which kstem does handle. This is correct behavior — kstem is a light stemmer.
- Mockito re-stubbing: `when(mock.method()).thenThrow()` fails if the method was previously stubbed to throw (the when() call itself triggers the existing throw). Fixed with `doThrow().when(mock).method()` pattern.

## User Setup Required

None - no external service configuration required beyond what's already in .env.

## Next Phase Readiness

- Phase 04 Plan 03 (ReindexController): IndexingService.fullReindex() and reindexPending() are implemented and tested. ReindexControllerTest @Disabled stubs are ready to enable.
- Phase 05 (Search): movies-{userId} index is created with custom_english_analyzer (asciifolding, lowercase, elision, stop_english, kstem). Search queries can target the index.
- Phase 06 (Movie Detail): personal fields (watched, personal_rating, personal_notes) are null in index. Phase 6 must upsert OS doc when personal fields are saved (D-06).

## Self-Check

Files exist:
- backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java: FOUND
- backend/src/main/java/de/moviearchive/indexing/IndexingService.java: FOUND
- backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java: FOUND

Commits exist:
- 5a184b0: FOUND
- c401d58: FOUND

Test results: 79 tests completed, 0 failures, 4 skipped (Wave 3 @Disabled stubs)

## Self-Check: PASSED

---
*Phase: 04-opensearch-indexing*
*Completed: 2026-05-17*
