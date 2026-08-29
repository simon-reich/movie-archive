---
phase: 12-wikidata-based-wikipedia-lookup
plan: 01
subsystem: api
tags: [wikidata, wikipedia, webclient, spring-boot, wiremock, enrichment]

# Dependency graph
requires:
  - phase: 03-save-movie-flow
    provides: WikipediaClient candidate-URL cascade, EnrichmentService/WikiReloadService callers
  - phase: 08-wiki-enrichment-tracking-batch-reload
    provides: WikipediaClient's paceRequest()/backoffUntil 429 pacing machinery
provides:
  - "WikipediaClient.fetch() now tries a Wikidata P345 (IMDb ID) cross-reference first, before the candidate-URL cascade"
  - "Second WebClient bound to wikidata.base-url, paced through the existing shared backoff window"
  - "Temporary plain-text wiki-resolution.log showing which path each Wikipedia lookup resolved through (D-05)"
affects: [13-*, any future phase touching WikipediaClient, EnrichmentService, or WikiReloadService]

# Actuals (#2632)
actuals:
  tokens: 7700
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "One WebClient bean per external host, built from the same injected WebClient.Builder (mirrors OmdbClient's pattern)"
    - "Optional-returning private methods that never throw WikipediaNotFoundException directly — only fetch()'s final fallthrough throws"
    - "Temporary, gitignored, plain-text side-log for dev visibility (not SLF4J) — logResolution()"

key-files:
  created:
    - backend/src/test/resources/fixtures/wikidata/search-found.json
    - backend/src/test/resources/fixtures/wikidata/search-not-found.json
    - backend/src/test/resources/fixtures/wikidata/sitelinks-found.json
  modified:
    - backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
    - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
    - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
    - backend/src/main/resources/application.properties
    - backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
    - backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
    - .gitignore

key-decisions:
  - "Wikidata lookup uses the two-call MediaWiki-API chain (haswbstatement CirrusSearch + REST sitelinks), not SPARQL — stays on wikidata.org with the same JSON-parsing idiom already used in this file (D-01, per RESEARCH.md)"
  - "Second WebClient built from the same injected WebClient.Builder, reusing it for both baseUrl and wikidataBaseUrl in the constructor — each .build() call snapshots the builder's state at that point, producing two independently-configured clients"
  - "Mockito's anyString() does NOT match null in Mockito 2+ (contrary to the plan's stated assumption) — fixed by switching the 4th fetch() argument matcher to nullable(String.class) in WikiReloadServiceTest and EnrichmentServiceTest, since test fixture Movies have a null imdbId"

patterns-established:
  - "logResolution(title, year, outcome) — temporary D-05 dev-visibility helper, explicitly framed as removable; call sites at all 4 fetch() outcome points (Wikidata hit, candidate hit, search hit, not-found)"

requirements-completed: [D-01, D-02, D-03, D-04, D-05]

coverage:
  - id: D1
    description: "WikipediaClient.fetch() tries Wikidata P345 lookup first, before the candidate-URL cascade (D-01)"
    requirement: "D-01"
    verification:
      - kind: unit
        ref: "WikipediaClientTest#shouldReturnResult_viaWikidata_whenImdbIdMatchesP345"
        status: pass
    human_judgment: false
  - id: D2
    description: "All documented fall-through paths (no imdbId, zero Wikidata hits, no enwiki sitelink, 429) land cleanly on the existing cascade, never throwing early"
    requirement: "D-01"
    verification:
      - kind: unit
        ref: "WikipediaClientTest#shouldFallThroughToCascade_whenWikidataSearchHasNoHits"
        status: pass
      - kind: unit
        ref: "WikipediaClientTest#shouldFallThroughToCascade_whenWikidataItemHasNoEnwikiSitelink"
        status: pass
      - kind: unit
        ref: "WikipediaClientTest#shouldHonorRetryAfterBackoff_onWikidataCall"
        status: pass
    human_judgment: false
  - id: D3
    description: "EnrichmentService and WikiReloadService (retryWikipedia + batchReload) pass movie.getImdbId() through with zero other caller-side changes (D-03)"
    requirement: "D-03"
    verification:
      - kind: unit
        ref: "EnrichmentServiceTest (all 4 tests, updated to 4-arg fetch() mock)"
        status: pass
      - kind: unit
        ref: "WikiReloadServiceTest (all 3 tests, updated to 4-arg fetch() mock)"
        status: pass
      - kind: integration
        ref: "WikiReloadServiceIntegrationTest (real WikipediaClient bean wired via Spring context)"
        status: pass
    human_judgment: false
  - id: D4
    description: "OMDB's client, ordering, and field mappings are byte-for-byte unchanged (D-02); no backfill/bulk-reprocessing trigger added (D-04)"
    requirement: "D-02"
    verification: []
    human_judgment: false
  - id: D5
    description: "wiki-resolution.log written with one human-readable line per Wikipedia enrichment attempt, gitignored (D-05)"
    requirement: "D-05"
    verification:
      - kind: manual_procedural
        ref: "cd backend && rm -f wiki-resolution.log && ./gradlew test --tests WikipediaClientTest --tests WikiReloadServiceTest --tests EnrichmentServiceTest && cat wiki-resolution.log"
        status: pass
    human_judgment: false

duration: 22min
completed: 2026-08-26
status: complete
---

# Phase 12 Plan 01: Wikidata-based Wikipedia lookup Summary

**WikipediaClient.fetch() now resolves Wikipedia articles via a direct Wikidata IMDb-ID (P345) cross-reference before falling back to the existing 6/10-step candidate-URL cascade, plus a temporary human-readable resolution log so the user can see which path each lookup took.**

## Performance

- **Duration:** ~22 min
- **Started:** 2026-08-26T17:15:00Z (approx.)
- **Completed:** 2026-08-26T17:31:51Z
- **Tasks:** 3
- **Files modified:** 11 (8 modified, 3 new fixture files)

## Accomplishments
- `WikipediaClient.fetch()` gained a 4th `imdbId` parameter and now tries a Wikidata P345 haswbstatement search + REST sitelinks lookup first, delegating to the existing `tryFetch()` for content extraction once a title is resolved — zero new parsing logic.
- Both new Wikidata calls are paced through the same shared `paceRequest()`/`backoffUntil` machinery already used for `en.wikipedia.org` 429s — no separate/unpaced client.
- `EnrichmentService.enrich()` and `WikiReloadService.retryWikipedia()` (and transitively `batchReload()`) both automatically exercise the new Wikidata-first path via a one-argument change at each call site (`movie.getImdbId()`) — zero caller-side special-casing (D-03).
- 4 new `WikipediaClientTest` tests cover the happy path and all 3 documented D-01 fall-through cases (zero search hits, 404 sitelink, 429-on-Wikidata pacing regression) — 7 tests total, all green.
- A temporary, gitignored `backend/wiki-resolution.log` now records one plain-text line per Wikipedia enrichment attempt (`Title (Year): outcome`), explicitly framed as dev-only and trivially removable (D-05).

## Task Commits

Each task was committed atomically:

1. **Task 1: Wikidata-first lookup wired end-to-end (happy path)** - `477cf9a` (feat)
2. **Task 2: Wikidata fallback edge cases — no match, no sitelink, rate-limit pacing** - `7cd031a` (test)
3. **Task 3: Temporary dev-visibility resolution log (D-05)** - `7da91b5` (feat)

_Task 1 included an in-scope Rule 1 bug fix to the two Mockito test files it modified (see Deviations)._

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` - Added `tryFetchViaWikidata()`, second `wikidataWebClient`, `logResolution()`, changed `fetch()` signature to 4 args
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` - Call site passes `movie.getImdbId()` as 4th arg
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` - Call site passes `movie.getImdbId()` as 4th arg
- `backend/src/main/resources/application.properties` - Added `wikidata.base-url` and `wiki.resolution-log.path` properties
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` - 4 new Wikidata tests, `@DynamicPropertySource` extended, 3 existing tests updated to 4-arg `fetch()`
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` - Mockito stubs/verifies updated to 4-arg `fetch()` with `nullable(String.class)`
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` - Mockito stubs updated to 4-arg `fetch()` with `nullable(String.class)`
- `backend/src/test/resources/fixtures/wikidata/search-found.json` - Wikidata search hit fixture
- `backend/src/test/resources/fixtures/wikidata/search-not-found.json` - Wikidata search empty-hit fixture
- `backend/src/test/resources/fixtures/wikidata/sitelinks-found.json` - Wikidata sitelinks REST response fixture
- `.gitignore` - Added `backend/wiki-resolution.log`

## Decisions Made
- Reused the injected `WebClient.Builder` for both the existing `webClient` and the new `wikidataWebClient` fields (two sequential `.baseUrl(...).build()` calls on the same builder) rather than requesting a second injected builder — each `.build()` snapshots the builder's state at call time, so this is safe and matches the plan's explicit sketch.
- Fixed a real bug in the plan's own guidance: it stated "Mockito's `anyString()` matches null too" for updating `WikiReloadServiceTest`/`EnrichmentServiceTest` mocks — this is incorrect for Mockito 2+ (`anyString()` explicitly excludes null). Since these tests' `Movie` fixtures have a null `imdbId`, the 4th `fetch()` argument matcher was changed to `nullable(String.class)` instead.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Mockito `anyString()` does not match null — fixed WikiReloadServiceTest/EnrichmentServiceTest matchers**
- **Found during:** Task 1, running the updated test suite
- **Issue:** The plan's step 7 said to update the Mockito mocks to `fetch(anyString(), anyString(), anyInt(), anyString())`, asserting "Mockito's anyString() matches null too, so this is safe even where movie.getImdbId() is null." This is false for Mockito 2+: `anyString()` excludes null. Since `WikiReloadServiceTest.newMovie()` and several `EnrichmentServiceTest` fixtures produce a `Movie` with a null `imdbId`, the mocked `wikipediaClient.fetch(...)` call didn't match any stub under Mockito's strict-stubs mode, throwing `PotentialStubbingProblem` at runtime. In `WikiReloadServiceTest` this was caught by `retryWikipedia()`'s generic `catch (Exception e)` block, causing only 1 `movieRepository.save()` call instead of the expected 2 (`TooFewActualInvocations`), and a second test failed with `ArgumentsAreDifferent` on the batch-loop verify.
- **Fix:** Changed the 4th argument matcher from `anyString()` to `nullable(String.class)` in both `WikiReloadServiceTest.java` and `EnrichmentServiceTest.java` (all `when(...)`, `doReturn(...).when(...)`, and `verify(...)` call sites).
- **Files modified:** `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java`, `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java`
- **Verification:** `./gradlew test --tests WikiReloadServiceTest --tests EnrichmentServiceTest` — all tests pass.
- **Committed in:** `477cf9a` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Necessary for the plan's own stated tests to actually pass; no scope creep — confined to the two test files the plan already listed as modified.

## Issues Encountered

- **Testcontainers Docker environment auto-detection failed** in this worktree's shell environment: `~/.testcontainers.properties` had a cached `UnixSocketClientProviderStrategy` pointing at the default `/var/run/docker.sock`, but this machine's Docker context is OrbStack (`~/.orbstack/run/docker.sock`), which doesn't exist at the default path. Worked around by exporting `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` for all `./gradlew test` invocations in this session. This is a pre-existing local-machine environment quirk, not caused by this phase's changes, and out of this plan's scope to permanently fix.
- **Full `./gradlew test` run (all ~176 tests) is flaky in this sandboxed environment**, independent of this phase's changes: running the entire suite in one JVM intermittently fails a shifting subset of unrelated Testcontainers-based integration tests (`SettingsIntegrationTest`, `MovieDetailControllerTest`, `WikiReloadServiceIntegrationTest`, etc.) with `PSQLException`/`FlywaySqlException` during Spring context bootstrap — consistent with Postgres connection-pool/resource contention from many concurrent `@SpringBootTest` contexts, not a code defect. Verified this is unrelated to Phase 12's changes by re-running every failing class in isolation (`WikipediaClientTest`, `WikiReloadServiceIntegrationTest`, `MovieDetailControllerTest`, `SettingsIntegrationTest`) — all pass cleanly when run alone or in the plan's specified `--tests` subsets. All of this plan's task-level `<verify>` commands (scoped to `WikipediaClientTest`, `WikiReloadServiceTest`, `EnrichmentServiceTest`) pass consistently, as does `WikiReloadServiceIntegrationTest` (the integration test that wires the real `WikipediaClient` bean end-to-end through Spring). Flagged here for visibility, not treated as a blocker for this plan.

## User Setup Required

None - no external service configuration required. `wikidata.base-url` and `wiki.resolution-log.path` both have working defaults (`https://www.wikidata.org` / `./wiki-resolution.log`) and are only overridden in tests via WireMock.

## Next Phase Readiness

- `WikipediaClient.fetch()`'s new 4-arg signature and Wikidata-first behavior are fully wired through both callers; no other caller of `fetch()` exists in the codebase (confirmed via full-repo grep before implementation).
- The ~630 previously-failed bulk-import films are intentionally left untouched (D-04) — the user can now manually trigger per-film retry or batch-reload to observe the new Wikidata-first lookup working in practice, and can inspect `backend/wiki-resolution.log` to see which resolution path each attempt took.
- No blockers for future phases. The pre-existing Docker/Testcontainers environment quirk and full-suite flakiness noted above are local-environment observations, not phase-12-introduced regressions.

## Self-Check: PASSED

All created/modified files verified present on disk; all 3 task commit hashes (`477cf9a`, `7cd031a`, `7da91b5`) verified present in `git log --oneline --all`.

---
*Phase: 12-wikidata-based-wikipedia-lookup*
*Completed: 2026-08-26*
