---
phase: 08-wiki-enrichment-tracking-batch-reload
plan: 01
subsystem: api
tags: [spring-boot, flyway, jpa, wikipedia, opensearch, testcontainers, wiremock]

requires: []
provides:
  - "V8 Flyway migration adding movies.wiki_last_attempted_at (TIMESTAMPTZ, nullable)"
  - "Movie.wikiLastAttemptedAt entity field"
  - "EnrichmentService.enrich() sets wikiLastAttemptedAt on every Wikipedia attempt (save-flow path)"
  - "MovieRepository.findByUserIdAndWikiUrlIsNull(UUID) — tracer eligibility query, no cooldown filter"
  - "WikiReloadService (retryWikipedia, batchReload) — Wikipedia-only retry + D-02 re-index"
  - "WikiReloadController — POST /admin/wiki-reload/{userId}, ownership-checked"
affects: [08-wiki-enrichment-tracking-batch-reload (plan 02), 09-manual-wiki-retry]

actuals:
  tokens: 8135
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Wikipedia-only retry service reusing WikipediaClient.fetch() as-is, no @Retryable on the caller"
    - "Duplicated assertOwnership + AccessDeniedException handler in WikiReloadController (matches ReindexController convention, no shared utility class)"
    - "Per-movie try/catch isolation in batchReload() loop — one failure never aborts the batch"

key-files:
  created:
    - backend/src/main/resources/db/migration/V8__add_wiki_last_attempted_at_to_movies.sql
    - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
    - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
    - backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
  modified:
    - backend/src/main/java/de/moviearchive/movie/Movie.java
    - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
    - backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java

key-decisions:
  - "Wiki timestamp is set as the FIRST statement inside EnrichmentService Step 3's try block (before the fetch call) so it covers the success branch and both catch branches uniformly"
  - "retryWikipedia(Movie) is @Transactional; batchReload(UUID) is neither @Transactional nor @Async in this tracer plan — synchronous, unpaced by design (Plan 08-02 adds async + pacing + cooldown filtering)"
  - "No hasRole(\"ADMIN\") gate on WikiReloadController — matches ReindexController's existing ownership-checked-not-privilege-escalated convention"

patterns-established:
  - "Tracer batch-reload endpoint proves controller -> service -> repository -> WikipediaClient -> IndexingService -> Postgres/OpenSearch end-to-end before Plan 08-02 layers on pacing/cooldown/async concurrency safeguards"

requirements-completed: [ENRICH-01, ENRICH-02]

coverage:
  - id: D1
    description: "Every Wikipedia enrichment attempt (success or failure) sets movie.wikiLastAttemptedAt — both in the original save-flow (EnrichmentService.enrich() Step 3) and the new retry path (WikiReloadService.retryWikipedia())"
    requirement: "ENRICH-01"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java#shouldSaveWithSuccess_whenWikipediaFails"
        status: pass
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java#shouldSetWikiLastAttemptedAt_onWikipediaSuccess"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java#shouldSaveWithSuccess_whenWikipediaFails"
        status: pass
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java#shouldSetTimestampAndWikiFields_onRetrySuccess"
        status: pass
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java#shouldSetTimestampOnly_whenWikipediaNotFound"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /admin/wiki-reload/{userId} is ownership-checked (403 on JWT-subject/path-userId mismatch, IDOR protection T-08-01) and functional end-to-end (Wikipedia retry, field persistence, OpenSearch re-index) for the happy path"
    requirement: "ENRICH-02"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java#shouldReturn403_whenUserMismatch"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java#shouldRetryWikipediaAndReindex_forMovieMissingWikiData"
        status: pass
    human_judgment: false
  - id: D3
    description: "A single per-movie failure inside the batchReload loop never aborts processing of the remaining eligible movies"
    verification:
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java#shouldIsolateFailures_inBatchLoop"
        status: pass
    human_judgment: false

duration: 18min
completed: 2026-08-22
status: complete
---

# Phase 8 Plan 1: Wiki Attempt Tracking + Tracer Batch-Reload Summary

**Wikipedia enrichment attempts (both save-flow and new retry path) are now timestamped, and `POST /admin/wiki-reload/{userId}` synchronously retries films missing wiki data end-to-end (WikipediaClient → Postgres → OpenSearch), unpaced by design pending Plan 08-02**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-08-22T20:14:00Z
- **Completed:** 2026-08-22T20:32:04Z
- **Tasks:** 3
- **Files modified:** 10 (5 created, 5 modified)

## Accomplishments
- V8 Flyway migration adds `movies.wiki_last_attempted_at` (TIMESTAMPTZ, nullable — null means "never attempted")
- `EnrichmentService.enrich()` Step 3 now sets `wikiLastAttemptedAt` as the first statement inside the Wikipedia try block, so it fires on the success branch and both catch branches (`WikipediaNotFoundException` and generic `Exception`) — closes RESEARCH.md Pitfall 3 for the original save-flow path
- New `WikiReloadService.retryWikipedia(Movie)`: Wikipedia-only retry, sets the timestamp on every attempt, re-indexes into OpenSearch on late success (D-02), never touches TMDB/OMDB data or `movie.status` (D-01)
- New `WikiReloadService.batchReload(UUID)`: synchronous, unpaced tracer loop over `findByUserIdAndWikiUrlIsNull`, per-movie failure isolation (one bad movie never aborts the batch)
- New `POST /admin/wiki-reload/{userId}` (`WikiReloadController`): ownership-checked (403 on JWT/path mismatch), no `hasRole("ADMIN")` gate, 200 on completion
- Extended `EnrichmentServiceTest`/`EnrichmentIntegrationTest` with explicit `wikiLastAttemptedAt` assertions on both the success and not-found paths
- New `WikiReloadServiceTest` (Mockito) and `WikiReloadControllerTest` (MockMvc + Testcontainers Postgres/OpenSearch + WireMock) covering the full slice

## Task Commits

Each task was committed atomically:

1. **Task 1 (tracer): Wiki attempt tracking + minimal end-to-end batch-reload path** - `9d78f84` (feat)
2. **Task 2: WikiReloadServiceTest — Mockito unit coverage** - `c37c14f` (test)
3. **Task 3: Extend EnrichmentServiceTest + EnrichmentIntegrationTest with wikiLastAttemptedAt assertions** - `9110371` (test)

_Note: Task 1 is `type="tracer"` — its `<verify>` (ReindexControllerTest + WikiReloadControllerTest) was re-run immediately after committing, per the tracer feedback gate, and passed before proceeding to Tasks 2-3._

## Files Created/Modified
- `backend/src/main/resources/db/migration/V8__add_wiki_last_attempted_at_to_movies.sql` - new column, additive, no default
- `backend/src/main/java/de/moviearchive/movie/Movie.java` - `wikiLastAttemptedAt : Instant` field
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` - Step 3 sets the timestamp on every Wikipedia attempt
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` - `findByUserIdAndWikiUrlIsNull(UUID)` tracer query (Plan 08-02 replaces with cooldown-aware `findEligibleForWikiReload`)
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` - `retryWikipedia(Movie)` + `batchReload(UUID)`
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` - `POST /admin/wiki-reload/{userId}`
- `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` - 403 ownership test + happy-path end-to-end test
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` - success/not-found/batch-isolation unit tests
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` - timestamp assertions on both Wikipedia branches
- `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java` - timestamp assertion on the not-found integration path

## Decisions Made
- Set `wikiLastAttemptedAt` as the very first statement inside the Wikipedia `try` block (both in `EnrichmentService` and `WikiReloadService`) so it unconditionally covers success and both failure branches — matches the plan's must-have truth and RESEARCH.md Pitfall 3 guidance.
- `retryWikipedia(Movie)` is `@Transactional`; `batchReload(UUID)` deliberately is not (and is not `@Async` yet) — this tracer plan is synchronous by design, Plan 08-02 adds pacing/async/cooldown.
- No `hasRole("ADMIN")` check anywhere in `WikiReloadController` — confirmed no such role/authority exists in `SecurityConfig` or `User`; matches `ReindexController`'s existing convention.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Mockito `UnnecessaryStubbingException` when overriding the WikipediaClient default stub**
- **Found during:** Task 3 (extending `EnrichmentServiceTest`)
- **Issue:** The new `shouldSetWikiLastAttemptedAt_onWikipediaSuccess` test overrides the `@BeforeEach` default "throws not found" stub with `doReturn(wiki)`. Since the override fully shadows the original stub before it's ever invoked, Mockito's strict-stubs checking flagged the `@BeforeEach` stub as unused for that specific test.
- **Fix:** Marked the `@BeforeEach` default Wikipedia stub `lenient()` — behavior is unchanged for all other tests (which do invoke it), and the new test can override it without tripping strict stubbing.
- **Files modified:** `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java`
- **Verification:** `./gradlew test --tests "de.moviearchive.movie.EnrichmentServiceTest"` green (all 5 tests, including the new one)
- **Committed in:** `9110371` (Task 3 commit)

**2. [Rule 3 - Blocking] Local Testcontainers Docker socket misconfiguration**
- **Found during:** Task 1's [BLOCKING] Flyway smoke-check step
- **Issue:** `~/.testcontainers.properties` on this machine pins `docker.client.strategy=UnixSocketClientProviderStrategy`, which resolves to `/var/run/docker.sock` — a path that does not exist on this OrbStack-based Docker setup (`/var/run/docker.sock` is absent; the real socket is `~/.orbstack/run/docker.sock`). All Testcontainers-backed tests failed with `DockerClientProviderStrategy` errors before any code change was made.
- **Fix:** Ran `./gradlew test` with `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` pointed at the OrbStack socket for each test invocation in this session. This is a per-invocation environment workaround, not a repo or `~/.testcontainers.properties` change — no files outside the worktree were modified.
- **Files modified:** none (environment-variable workaround only)
- **Verification:** `ReindexControllerTest` (pre-existing, unrelated to this plan) went from failing-on-Docker-discovery to green with the env vars set, confirming this was a pure environment issue, not a regression introduced by V8.
- **Committed in:** N/A (no repo change)

---

**Total deviations:** 2 auto-fixed (1 bug, 1 blocking/environment)
**Impact on plan:** Both auto-fixes were necessary to get a green test suite; neither changed production behavior or scope. No scope creep.

## Issues Encountered
- Initial version of `WikiReloadControllerTest.persistMovie()` only embedded the release date in `rawTmdbJson`, not on the `Movie` entity itself. Since `WikiReloadService.retryWikipedia()` reads `movie.getReleaseDate()` (not the raw JSON), the computed year defaulted to 0, causing the Wikipedia candidate URL to miss the WireMock stub (`Inception_(0_film)` instead of `Inception_(2010_film)`). Fixed by setting `movie.setReleaseDate(...)` explicitly in the test helper before the first test run — no production code was affected, caught immediately by the failing assertion.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- The full end-to-end wiki-reload architecture (controller → service → repository → WikipediaClient → IndexingService → Postgres/OpenSearch) is proven working via `WikiReloadControllerTest`.
- Plan 08-02 can now safely layer on: cooldown-window eligibility filtering (`findEligibleForWikiReload` replacing `findByUserIdAndWikiUrlIsNull`), `@Async` execution on a dedicated bounded executor, pacing between Wikipedia calls (`Thread.sleep`), and concurrency safeguards — without needing to re-validate the base plumbing.
- Full backend test suite: 127 tests, 0 failures (`./gradlew test`).
- No blockers.

---
*Phase: 08-wiki-enrichment-tracking-batch-reload*
*Completed: 2026-08-22*
