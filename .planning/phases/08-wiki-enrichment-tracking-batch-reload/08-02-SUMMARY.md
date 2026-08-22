---
phase: 08-wiki-enrichment-tracking-batch-reload
plan: 02
subsystem: api
tags: [spring-boot, spring-async, jpa, wikipedia, opensearch, testcontainers, wiremock]

requires:
  - phase: 08-wiki-enrichment-tracking-batch-reload (plan 01)
    provides: "wikiLastAttemptedAt column/field, WikiReloadService/WikiReloadController tracer skeleton, findByUserIdAndWikiUrlIsNull tracer query"
provides:
  - "MovieRepository.findEligibleForWikiReload(userId, cutoff) — cooldown-window + SUCCESS-status eligibility query, final replacement for the Plan 08-01 tracer query"
  - "WikiReloadService.batchReload — @Async(\"wikiReloadExecutor\"), cooldown-filtered, Thread.sleep(pacingDelayMs)-paced between calls, no sleep after the last item or for 0-1 eligible films"
  - "AsyncConfig.wikiReloadExecutor bean — dedicated ThreadPoolTaskExecutor, core=1/max=1/queue=1"
  - "wiki.retry.cooldown-days / wiki.retry.pacing-delay-ms application.properties keys (ENV-overridable)"
  - "WikiReloadController — 202 Accepted response, TaskRejectedException -> 503 handler"
  - "WikiReloadServiceIntegrationTest — timing-based cooldown-boundary and pacing-boundary integration coverage"
  - "WikiReloadControllerTest — queued-second-trigger and rejected-third-trigger concurrency coverage"
affects: [09-manual-wiki-retry, 10-bulk-import-engine, 11-bulk-import-feedback-ui]

actuals:
  tokens: 10900
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Dedicated single-slot ThreadPoolTaskExecutor (core=1/max=1/queue=1) isolates a paced background batch from the live-request enrichmentExecutor pool and gives a natural, testable concurrency-rejection boundary"
    - "Thread.sleep pacing inside a plain (non-@Transactional) @Async loop, with the per-item DB write delegated to a separately @Transactional method — avoids holding a DB connection open across the sleep"
    - "Timing-based integration tests measure real infrastructure floors empirically first, then pick pacing-delay/thresholds with a wide safety margin above that floor, rather than trusting a plan-sketched constant"

key-files:
  created:
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java
  modified:
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
    - backend/src/main/java/de/moviearchive/config/AsyncConfig.java
    - backend/src/main/resources/application.properties
    - backend/src/test/resources/application-test.properties
    - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
    - backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java

key-decisions:
  - "findEligibleForWikiReload adds AND m.status = SUCCESS on top of the cooldown filter — ERROR-status movies never reached the Wikipedia step and have no reliable title/year, so including them would waste a paced Wikipedia call on incomplete data (RESEARCH.md Open Question 1's recommendation)"
  - "Poll test assertions on indexedAt (full pipeline completion), not wikiLastAttemptedAt (set by retryWikipedia's FIRST of two save() calls) — polling on the earlier field let a test return, and the next test's @BeforeEach cleanDb() delete the row, while the async re-index step's second save() was still in flight"
  - "Timing-boundary integration tests use pacing-delay-ms well above this Testcontainers environment's empirically observed unpaced per-movie floor (988-1614ms, dominated by WireMock round trips + OpenSearch index-with-refresh), not the 500ms the plan sketched — a delay close to the real floor makes 'did a sleep happen' indistinguishable from ordinary infrastructure jitter"
  - "WikiReloadControllerTest and WikiReloadServiceIntegrationTest both gained an @AfterEach cleanup (not just @BeforeEach) — their new tests leave users/movies behind after the class finishes, which could trip AuthControllerTest's unrelated userRepository.deleteAll() (no movies cleanup) with a foreign-key violation depending on JUnit's test-class execution order; cleaning up after every test removes the ordering dependency"

patterns-established:
  - "A dedicated bounded executor (core=1/max=1/queue=1) is both the pacing-isolation mechanism AND the DoS-mitigation boundary — a third overlapping trigger fails fast via TaskRejectedException -> 503 rather than growing an unbounded queue"

requirements-completed: [ENRICH-02, ENRICH-03]

coverage:
  - id: D1
    description: "findEligibleForWikiReload excludes movies attempted inside the 30-day cooldown window and includes never-attempted or staler-than-30-day movies, restricted to SUCCESS-status movies"
    requirement: "ENRICH-02"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java#shouldRespectCooldownWindow_excludingRecentAttempts"
        status: pass
    human_judgment: false
  - id: D2
    description: "batchReload runs fire-and-forget on the dedicated wikiReloadExecutor bean (202 Accepted returned immediately) and paces Thread.sleep(pacingDelayMs) between consecutive Wikipedia calls, with zero sleeps for 0-1 eligible films"
    requirement: "ENRICH-03"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java#shouldPaceRequestsBetweenEligibleMovies"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java#shouldNotPace_whenOnlyOneMovieEligible"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java#shouldRetryWikipediaAndReindex_forMovieMissingWikiData"
        status: pass
    human_judgment: false
  - id: D3
    description: "wikiReloadExecutor's bounded queue (1 running + 1 queued) queues a second overlapping trigger and rejects a third with 503, not an unhandled 500 (T-08-02 DoS mitigation)"
    requirement: "ENRICH-02"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java#shouldQueueSecondTrigger_whileFirstRunInProgress"
        status: pass
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java#shouldReject_whenThirdTriggerExceedsQueueCapacity"
        status: pass
    human_judgment: false
  - id: D4
    description: "wiki.retry.cooldown-days and wiki.retry.pacing-delay-ms are configurable via application.properties with ENV overrides, defaulting to 30 and 1000 respectively"
    requirement: "ENRICH-03"
    verification:
      - kind: other
        ref: "backend/src/main/resources/application.properties (wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}, wiki.retry.pacing-delay-ms=${WIKI_RETRY_PACING_DELAY_MS:1000})"
        status: pass
    human_judgment: false

duration: 23min
completed: 2026-08-22
status: complete
---

# Phase 8 Plan 2: Cooldown-Filtered, Paced, Async Batch-Reload Summary

**Batch-reload is now fire-and-forget `@Async` on a dedicated `core=1/max=1/queue=1` executor — cooldown-window + status-filtered eligibility, `Thread.sleep(pacingDelayMs)`-paced between Wikipedia calls, and a saturated queue degrades to 503 instead of an unbounded backlog**

## Performance

- **Duration:** ~23 min
- **Started:** 2026-08-22T20:38:00Z
- **Completed:** 2026-08-22T21:00:09Z
- **Tasks:** 3
- **Files modified:** 9 (1 created, 8 modified)

## Accomplishments
- `MovieRepository.findEligibleForWikiReload(userId, cutoff)` replaces Plan 08-01's tracer query — adds `status = SUCCESS` and `(wikiLastAttemptedAt IS NULL OR < cutoff)` filtering, closing ENRICH-02's cooldown contract
- `WikiReloadService.batchReload` is now `@Async("wikiReloadExecutor")`: computes the cooldown cutoff, paces `Thread.sleep(pacingDelayMs)` between consecutive calls (never after the last item, never for 0-1 eligible films), stays free of `@Transactional` per RESEARCH.md Pitfall 4
- New `AsyncConfig.wikiReloadExecutor` bean (`core=1/max=1/queue=1`) isolates the paced batch from the live-save `enrichmentExecutor` pool and gives a hard, testable concurrency-rejection boundary
- `application.properties` gains `wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` (ENV-overridable, defaulting 30/1000); `application-test.properties` sets a fast 1ms suite-wide default
- `WikiReloadController.triggerReload` now returns 202 Accepted; a new `TaskRejectedException` → 503 handler covers a saturated executor queue
- `WikiReloadServiceIntegrationTest` (new): timing-based proof of the cooldown boundary (29-day-old attempt skipped and left unchanged; never-attempted and 31-day-old attempts both retried) and the pacing boundary (3-movie batch measurably slower than 1-movie, bounded by the sleep-count floor)
- `WikiReloadControllerTest` gains `shouldQueueSecondTrigger_whileFirstRunInProgress` (202) and `shouldReject_whenThirdTriggerExceedsQueueCapacity` (503, non-empty message), closing the ENRICH-02 concurrency contract end-to-end through the real HTTP endpoint

## Task Commits

Each task was committed atomically:

1. **Task 1: Cooldown-window eligibility query + async execution + pacing delay** - `ee3fc99` (feat)
2. **Task 2: WikiReloadServiceIntegrationTest — cooldown boundary + pacing verification** - `04526d5` (test)
3. **Task 3: Concurrency test — queued second trigger, rejected third trigger** - `6d60b50` (test)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` - `findEligibleForWikiReload` replaces the tracer's `findByUserIdAndWikiUrlIsNull`
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` - `batchReload` now `@Async`, cooldown-aware, paced
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` - new `wikiReloadExecutor` bean
- `backend/src/main/resources/application.properties` - `wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms`
- `backend/src/test/resources/application-test.properties` - fast 1ms pacing default for the suite
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` - 202 Accepted + `TaskRejectedException` → 503 handler
- `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` - poll-based happy path, 2 new concurrency tests, `@AfterEach` cleanup
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` - updated to mock the new repository method signature
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java` - new: cooldown-boundary + pacing-boundary integration tests

## Decisions Made
- `findEligibleForWikiReload`'s `status = SUCCESS` clause (RESEARCH.md Open Question 1's recommendation) — `ERROR`-status movies never reached the Wikipedia step and have no reliable title/year to look up.
- Poll on `indexedAt` (full pipeline completion) rather than `wikiLastAttemptedAt` in every timing-sensitive test — the latter is set by `retryWikipedia()`'s FIRST of two `save()` calls, well before the re-index step's second `save()` sets `indexedAt`; polling on the earlier field let a test return (and the next test's `cleanDb()` delete the row) while the async re-index write was still in flight.
- Timing-boundary thresholds (2500ms in `WikiReloadServiceIntegrationTest`, 2000ms in `WikiReloadControllerTest`'s concurrency tests) were set well above this Testcontainers environment's empirically observed unpaced per-movie floor (988-1614ms), not the 500ms the plan sketched — see Deviations below.
- Added `@AfterEach` cleanup to both new/modified integration test classes to prevent cross-test-class data leakage into `AuthControllerTest`'s unrelated `userRepository.deleteAll()` — see Deviations below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `WikiReloadServiceTest` compile break from the repository method rename**
- **Found during:** Task 1
- **Issue:** `WikiReloadServiceTest.shouldIsolateFailures_inBatchLoop` mocked `movieRepository.findByUserIdAndWikiUrlIsNull(userId)`, which Task 1 removes from `MovieRepository` in favor of `findEligibleForWikiReload(userId, cutoff)` — a compile error, not listed in the plan's Task 1 `<files>` but directly caused by the repository change.
- **Fix:** Updated the mock to `findEligibleForWikiReload(eq(userId), any(Instant.class))`.
- **Files modified:** `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java`
- **Committed in:** `ee3fc99` (Task 1 commit)

**2. [Rule 1 - Bug] `WikiReloadControllerTest` happy-path poll raced with the async re-index save**
- **Found during:** Task 1 verify (`./gradlew test --tests WikiReloadControllerTest`)
- **Issue:** Following the plan's literal instruction to poll on `wikiLastAttemptedAt`, the test returned as soon as `retryWikipedia()`'s FIRST `save()` completed (setting `wikiLastAttemptedAt` + wiki fields), well before the re-index step's SECOND `save()` (setting `indexedAt`) had run. The next test method's `@BeforeEach cleanDb()` then deleted the movie row while that second `save()` was still in flight, producing a Hibernate `StaleStateException` ("Row was updated or deleted by another transaction") swallowed inside `retryWikipedia()`'s catch block — which silently left `indexedAt` null and failed the test's own `assertThat(indexedAt).isNotNull()` assertion.
- **Fix:** Poll on `indexedAt` instead — the last field the full pipeline sets. Same fix applied preemptively in Task 2's new `WikiReloadServiceIntegrationTest` and Task 3's new concurrency tests.
- **Files modified:** `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java`
- **Verification:** `./gradlew test --tests WikiReloadControllerTest` green, re-run twice for flakiness
- **Committed in:** `ee3fc99` (Task 1 commit)

**3. [Rule 1 - Bug] Plan's "well under 500ms" pacing-boundary threshold is unachievable in this environment**
- **Found during:** Task 2 verify
- **Issue:** The plan's `shouldNotPace_whenOnlyOneMovieEligible` design assumed real per-movie processing overhead was negligible next to a 500ms pacing delay. Empirically, a single `retryWikipedia()` call (4 sequential WireMock round trips + Postgres saves + OpenSearch index-with-refresh) took 988-1614ms in this Testcontainers environment with ZERO pacing involved — a 500ms bound is not just tight, it's structurally false regardless of correctness. It also made the companion 3-movie pacing assertion (`>= 1000ms`) nearly meaningless, since 3 movies' unpaced floor alone (~3-4.8s) already exceeds that bound.
- **Fix:** Raised the class's pacing-delay-ms override to 2500ms (a clear margin above the observed floor) and rebalanced both assertions: single-movie `< 2500ms` (comfortable margin above the ~1-1.6s floor, comfortably below one full pacing interval), 3-movie `>= 5000ms` (the mandatory floor from 2 sleeps at 2500ms, comfortably above the ~4.8s unpaced-worst-case ceiling for 3 movies). Also pre-warmed OpenSearch index creation via `indexingService.ensureIndexExists()` outside the timed window, since first-write index creation alone cost ~1s and had nothing to do with pacing.
- **Files modified:** `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java`
- **Verification:** Full test class run twice consecutively, both green (`tests="3" failures="0"`)
- **Committed in:** `04526d5` (Task 2 commit)

**4. [Rule 3 - Blocking] Cross-test-class FK violation in `AuthControllerTest`/`AuthIntegrationTest` during the full-suite run**
- **Found during:** Task 3's mandated full-suite verification (`./gradlew test`)
- **Issue:** The full suite failed with 11 failures, all `DataIntegrityViolationException: ... violates foreign key constraint "movies_user_id_fkey"` inside `AuthControllerTest`/`AuthIntegrationTest`'s own `@BeforeEach` (`userRepository.deleteAll()`, no movies cleanup). All test classes share one singleton per-JVM Postgres container (`AbstractIntegrationTest`'s static block). `WikiReloadControllerTest`'s and `WikiReloadServiceIntegrationTest`'s tests only cleaned up via `@BeforeEach` (before their OWN next run), leaving users+movies behind after the class finished. JUnit's test-class execution order happened to run `WikiReloadControllerTest` immediately before `AuthControllerTest` in this run, so `userRepository.deleteAll()` hit residual movies still referencing residual users from my class.
- **Fix:** Added `@AfterEach` cleanup (same `movieRepository.deleteAll(); userRepository.deleteAll();` as the existing `@BeforeEach`) to both `WikiReloadControllerTest` and `WikiReloadServiceIntegrationTest`, removing the ordering dependency entirely regardless of which class JUnit happens to run next. Did not modify `AuthControllerTest`/`AuthIntegrationTest` — out of scope, pre-existing convention shared by several other test classes.
- **Files modified:** `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java`, `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java`
- **Verification:** Full suite (`./gradlew test`) run twice consecutively: 132/132 tests green both times
- **Committed in:** `6d60b50` (Task 3 commit)

---

**Total deviations:** 4 auto-fixed (3 test-design bugs, 1 blocking cross-test-class isolation issue)
**Impact on plan:** All four were necessary to get a real, non-flaky green suite; none changed production code behavior beyond what Task 1 already specified. No scope creep — all fixes landed in files the plan already had me touching or creating.

## Issues Encountered
- Local Testcontainers Docker socket misconfiguration (same as Plan 08-01): `~/.testcontainers.properties` resolves to `/var/run/docker.sock`, absent on this OrbStack setup. Worked around per-invocation with `DOCKER_HOST`/`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` pointed at the OrbStack socket; no repo files changed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- ENRICH-01 through ENRICH-03 are now fully delivered: attempt tracking (Plan 08-01), cooldown-filtered + paced + async batch-reload with a 503-degrading concurrency boundary (this plan).
- Phase 9 (manual per-film retry button) can call `WikiReloadService.retryWikipedia(Movie)` directly — unchanged by this plan, already proven end-to-end by both `WikiReloadServiceTest` and the new `WikiReloadServiceIntegrationTest`.
- Full backend test suite: 132 tests, 0 failures (`./gradlew test`), verified stable across 2 consecutive full-suite runs.
- No blockers.

---
*Phase: 08-wiki-enrichment-tracking-batch-reload*
*Completed: 2026-08-22*

## Self-Check: PASSED

All created/modified files verified present on disk; all task commits (`ee3fc99`, `04526d5`, `6d60b50`) and the plan-metadata commit (`e7ee6e5`) verified present in `git log`.
