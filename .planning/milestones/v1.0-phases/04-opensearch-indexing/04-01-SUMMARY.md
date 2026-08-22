---
phase: 04-opensearch-indexing
plan: 01
subsystem: testing
tags: [opensearch, testcontainers, junit5, spring-boot-test]

requires:
  - phase: 03-save-movie-flow
    provides: MovieRepository, UserRepository, AbstractIntegrationTest base class pattern

provides:
  - AbstractOpenSearchTest base class with singleton GenericContainer (opensearch:2.19.0)
  - IndexingIntegrationTest @Disabled stubs for IDX-01, IDX-02, IDX-03 (Wave 2)
  - ReindexControllerTest @Disabled stubs for IDX-04 (Wave 3)
  - movies-index.json skeleton with custom_english_analyzer on classpath

affects: [04-02-indexing-service, 04-03-reindex-controller]

tech-stack:
  added: []
  patterns:
    - "AbstractOpenSearchTest extends AbstractIntegrationTest with GenericContainer singleton (no new dependency)"
    - "@DynamicPropertySource overrides opensearch.host and opensearch.port from container"
    - "movies-index.json at src/main/resources/opensearch/ loaded via getResourceAsStream"
    - "_skeleton:true root marker in JSON so plan 04-02 can detect and replace"

key-files:
  created:
    - backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java
    - backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java
    - backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java
    - backend/src/main/resources/opensearch/movies-index.json
  modified:
    - backend/src/test/resources/application-test.properties

key-decisions:
  - "GenericContainer used for OpenSearch Testcontainer (not opensearch-testcontainers 4.x — incompatible with OS 2.x)"
  - "AbstractOpenSearchTest extends AbstractIntegrationTest to reuse Postgres singleton alongside OpenSearch singleton"
  - "_skeleton:true added to movies-index.json root so plan 04-02 can detect the placeholder and replace the full mapping"

patterns-established:
  - "AbstractOpenSearchTest: same static-block singleton pattern as AbstractIntegrationTest — prevents container restart between test classes"
  - "opensearch.host/port stub values in application-test.properties overridden at runtime by @DynamicPropertySource"

requirements-completed: [IDX-01, IDX-02, IDX-03, IDX-04]

duration: 15min
completed: 2026-05-17
---

# Phase 4 Plan 01: OpenSearch Test Infrastructure Summary

**OpenSearch Testcontainers base class with singleton GenericContainer (opensearch:2.19.0) plus @Disabled stub test classes and movies-index.json skeleton providing the Nyquist Wave 0 compliance for plans 04-02 and 04-03**

## Performance

- **Duration:** 15 min
- **Started:** 2026-05-17T13:10:00Z
- **Completed:** 2026-05-17T13:25:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- Created `AbstractOpenSearchTest` extending `AbstractIntegrationTest` with a JVM-singleton `GenericContainer` for `opensearchproject/opensearch:2.19.0`, `@DynamicPropertySource` for `opensearch.host`/`opensearch.port`, and static-block start pattern
- Created `IndexingIntegrationTest` with 6 `@Disabled` stub methods matching the exact names required by `04-VALIDATION.md` (`shouldIndexFilm_afterPostgresPersist`, `shouldLeaveIndexedAtNull_whenOsFails`, `shouldCreateIndex_whenNotExists`, `shouldNotThrow_whenIndexAlreadyExists`, `shouldNormalizeAccents`, `shouldStemEnglishWords`)
- Created `ReindexControllerTest` with `@AutoConfigureMockMvc` and 4 `@Disabled` stub methods (`shouldReturn403_whenUserMismatch`, `shouldFullReindex`, `shouldIndexOnlyPending`, `shouldReturnIndexedCount`)
- Created `movies-index.json` skeleton at `src/main/resources/opensearch/` with `custom_english_analyzer`, `stop_english` filter, 3 placeholder mappings, and `_skeleton:true` marker
- Verified: 10 new tests run as skipped (0 failures), `compileTestJava` exits 0, no new Gradle dependency added

## Task Commits

1. **Task 1: AbstractOpenSearchTest base class + test-profile properties** - `251ad43` (test)
2. **Task 2: @Disabled test stubs for IDX-01..04 + index JSON skeleton** - `a9102b9` (test)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified

- `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` — OpenSearch Testcontainers base class (GenericContainer singleton, @DynamicPropertySource)
- `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` — @Disabled stubs for IDX-01/02/03 (6 test methods)
- `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — @Disabled stubs for IDX-04 (4 test methods)
- `backend/src/main/resources/opensearch/movies-index.json` — Index definition skeleton (custom_english_analyzer + 3 placeholder mappings + `_skeleton:true`)
- `backend/src/test/resources/application-test.properties` — Added `opensearch.host=localhost` and `opensearch.port=9200` stub entries

## Decisions Made

- **GenericContainer over opensearch-testcontainers**: `opensearch-testcontainers` 4.x is incompatible with OpenSearch 2.x; `GenericContainer` requires no new dependency (already on classpath via `spring-boot-testcontainers`).
- **Extends AbstractIntegrationTest**: Both Postgres and OpenSearch singletons coexist — OpenSearch tests need a real DB for movie/user data.
- **`_skeleton:true` JSON marker**: Signals to plan 04-02 that the JSON file is a placeholder, preventing accidental use of incomplete mappings.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Wave 0 complete: all test class files and JSON skeleton exist on disk
- Plan 04-02 can now implement `IndexingService`, `DocumentBuilder`, `OpenSearchConfig`, and make the 6 `IndexingIntegrationTest` methods green
- Plan 04-03 can implement `ReindexController` and make the 4 `ReindexControllerTest` methods green
- No blockers

## Known Stubs

- `backend/src/main/resources/opensearch/movies-index.json` — `"_skeleton": true` at root; mappings contain only 3 placeholder fields (`title`, `original_title`, `personal_notes`). Intentional: full 40+ field mapping is deferred to plan 04-02. Plan 04-02 will detect `_skeleton:true` and replace the file.
- `IndexingIntegrationTest.@BeforeEach cleanDb()` — empty body (signature only). Cleanup logic (delete movies, users, OS index) deferred to plan 04-02.

---
*Phase: 04-opensearch-indexing*
*Completed: 2026-05-17*

## Self-Check: PASSED

Files exist:
- backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java: FOUND
- backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java: FOUND
- backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java: FOUND
- backend/src/main/resources/opensearch/movies-index.json: FOUND
- backend/src/test/resources/application-test.properties: FOUND (modified)

Commits verified:
- 251ad43: Task 1 — AbstractOpenSearchTest + test-profile properties
- a9102b9: Task 2 — @Disabled stubs + movies-index.json skeleton
