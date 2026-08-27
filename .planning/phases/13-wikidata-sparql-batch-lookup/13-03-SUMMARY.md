---
phase: 13-wikidata-sparql-batch-lookup
plan: 03
subsystem: api
tags: [bulk-import, enrichment, wikidata, sparql, two-pass, wiremock]

# Dependency graph
requires:
  - phase: 13-wikidata-sparql-batch-lookup
    provides: "WikipediaClient.resolveViaWikidataSparql(List<String>) and the fetch(..., Map<String,String>) prefetch-aware overload from Plan 1 — the exact public API surface this plan's two-pass restructuring calls"
provides:
  - "BulkImportService.resolveAndPersistImdbId(UUID, int, String) -> String — fetches TMDB detail for a newly-matched line and persists its imdbId immediately, before any Wikipedia lookup fires"
  - "BulkImportService.runImport()'s two-pass shape: Pass 1 (match+save+resolve imdbId), Pass 1.5 (one batched SPARQL call for the whole run), Pass 2 (enrich each matched line with the shared resolved map)"
  - "EnrichmentService.enrich(UUID, Map<String,String>) — the batch-prefetch-aware overload; enrich(UUID) (save-flow) is unchanged in shape, both now share doEnrich()"
  - "de.moviearchive.bulkimport.dto.MatchedLine(UUID movieId, int tmdbId) — replaces bare UUID returns from processLine()/saveAndUpsert()"
affects: []

actuals:
  tokens: 4965
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Two-pass batch restructuring: collect-then-resolve-then-act, replacing a per-item TMDB-detail-then-enrich loop with match+save (Pass 1) -> one batched external call (Pass 1.5) -> per-item enrich threading the shared result map (Pass 2)"
    - "@Async method overloading (enrich(UUID) / enrich(UUID, Map)) both delegating to a shared private doEnrich() so a single-item caller and a batch caller share one pipeline, branching only at the external-call site that differs"

key-files:
  created:
    - backend/src/main/java/de/moviearchive/bulkimport/dto/MatchedLine.java
  modified:
    - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
    - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
    - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java

key-decisions:
  - "resolveAndPersistImdbId() persists the imdbId onto the Movie row immediately (Pitfall 2 from RESEARCH.md) rather than threading it through as an in-memory value to enrich() — makes EnrichmentService's own later TMDB detail call redundant-but-harmless, matching the codebase's idempotent-save convention instead of introducing a new parameter-passing path"
  - "EnrichmentService.enrich(UUID) and enrich(UUID, Map) both delegate to a private doEnrich(UUID, Map) — the branch happens only at the Wikipedia fetch() call site (4-arg vs 5-arg overload), keeping the TMDB/OMDB/persist/index steps identical for both callers with zero duplication"

patterns-established:
  - "A batch orchestrating method (runImport()) accumulates results from its self-proxied per-item calls into local collections during the loop, then performs exactly one aggregate external call after the loop, then a second loop to act on each item using the aggregate result — the concrete shape D-02/D-03 both prescribe for future batch-restructuring work in this codebase"

requirements-completed: [D-03]

coverage:
  - id: D-03-1
    description: "BulkImportService.runImport() resolves TMDB detail (imdbId) for every newly-matched line BEFORE issuing a single batched SPARQL call for the whole run"
    requirement: "D-03"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun — status: pass"
        status: pass
    human_judgment: false
  - id: D-03-2
    description: "A 2-line bulk-import run calls wikipediaClient.resolveViaWikidataSparql() exactly once with both lines' imdbIds present together, never once per line"
    requirement: "D-03"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun — status: pass"
        status: pass
    human_judgment: false
  - id: D-03-3
    description: "enrichmentService.enrich(UUID, Map) (2-arg) is called once per matched line, threading the SAME resolved map; enrich(UUID) (1-arg) is never called from runImport()"
    requirement: "D-03"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun — status: pass"
        status: pass
    human_judgment: false
  - id: D-03-4
    description: "resolveAndPersistImdbId() returns null and never calls movieRepository.save() when tmdbClient.fetchDetail() throws — never throws itself, matching this codebase's swallow-and-degrade convention"
    requirement: "D-03"
    verification:
      - kind: unit
        ref: "BulkImportServiceTest#shouldReturnNull_whenTmdbDetailCallFails — status: pass"
        status: pass
    human_judgment: false
  - id: D-03-5
    description: "EnrichmentService.enrich(UUID) (1-arg, save-flow) still calls the 4-argument WikipediaClient.fetch(...) overload unchanged — zero behavioral change from this plan"
    requirement: "D-03"
    verification:
      - kind: unit
        ref: "EnrichmentServiceTest (all 4 pre-existing tests) — status: pass, zero edits to stubs"
        status: pass
    human_judgment: false
  - id: D-03-6
    description: "BulkImportControllerTest's existing full-pipeline integration tests pass with no new WireMock stubs, proving the empty-list guard from Plan 1 keeps this test network-safe"
    requirement: "D-03"
    verification:
      - kind: integration
        ref: "BulkImportControllerTest (all existing tests) — status: pass, unmodified"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-08-27
status: complete
---

# Phase 13 Plan 3: Two-Pass Bulk-Import Restructuring Summary

**Restructured `BulkImportService.runImport()` into an explicit two-pass shape — match+save+resolve-imdbId for every line, one batched SPARQL call for the whole run, then per-line enrichment threading the shared resolved-title map — replacing the old per-line TMDB-detail-then-enrich dispatch that carried the same one-movie-at-a-time exposure as the original ~630-movie rate-limit incident.**

## Performance

- **Duration:** ~25 min
- **Completed:** 2026-08-27
- **Tasks:** 2
- **Files modified:** 4 (1 new record, 2 modified source, 1 modified test)

## Accomplishments
- New `MatchedLine(UUID movieId, int tmdbId)` record replaces bare `UUID` returns from `processLine()`/`saveAndUpsert()`, carrying the `tmdbId` a matched line needs for its own TMDB detail call
- New `BulkImportService.resolveAndPersistImdbId(UUID, int, String)` fetches TMDB detail for a newly-matched line and persists the extracted `imdbId` onto the `Movie` row immediately — front-loading the imdbId discovery that previously only happened one movie at a time, inside `EnrichmentService.enrich()`'s own TMDB detail call
- `runImport()` restructured into three phases: **Pass 1** matches+saves every raw line and resolves+persists each newly-matched line's imdbId; **Pass 1.5** issues exactly one `wikipediaClient.resolveViaWikidataSparql()` call for every imdbId collected across the whole run; **Pass 2** calls the new `enrichmentService.enrich(UUID, Map)` overload once per matched line, threading the SAME resolved map into every call
- New `EnrichmentService.enrich(UUID, Map<String, String>)` overload shares a common `doEnrich(UUID, Map)` private method with the existing `enrich(UUID)` (save-flow, unaffected in shape) — the two diverge only at the Wikipedia `fetch()` call site (4-arg vs. 5-arg overload)
- A Mockito-verified test (`shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun`) proves a 2-line bulk-import run resolves Wikidata for both lines' imdbIds in exactly one SPARQL call, both `enrich(UUID, Map)` calls threading the identical resolved map, and the 1-arg `enrich(UUID)` overload never firing from `runImport()`

## Task Commits

Each task was committed atomically:

1. **Task 1: Two-pass restructuring — TMDB-detail-then-SPARQL-batch-then-enrich (D-03)** - `3349500` (feat)
2. **Task 2: Prove the two-pass contract — one SPARQL call for N matched lines** - `6dedea9` (test)

**Plan metadata:** committed as part of this SUMMARY (worktree mode — orchestrator finalizes STATE.md/ROADMAP.md after merge)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/bulkimport/dto/MatchedLine.java` - New record `MatchedLine(UUID movieId, int tmdbId)`, mirroring `MovieInitiateResult`'s one-line pattern
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` - New `movieRepository`/`wikipediaClient` constructor dependencies; `processLine()`/`saveAndUpsert()` return type changed `Optional<UUID>` -> `Optional<MatchedLine>`; new `resolveAndPersistImdbId()`; `runImport()` restructured into Pass 1 / Pass 1.5 / Pass 2
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` - `enrich(UUID)` body extracted into private `doEnrich(UUID, Map<String,String>)`; new `enrich(UUID, Map<String,String>)` overload; Wikipedia step branches on whether a pre-resolved map was supplied
- `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` - Added `movieRepository`/`wikipediaClient` mocks and `self`-proxy wiring (mirrors `WikiReloadServiceTest`'s convention); 3 new test methods covering `resolveAndPersistImdbId()`'s success/fail-closed paths and the two-pass SPARQL-batching contract

## Decisions Made
- `resolveAndPersistImdbId()` persists the resolved imdbId onto the `Movie` row immediately (per RESEARCH.md Pitfall 2) rather than threading it through as an in-memory value into `enrich()` — this makes `EnrichmentService`'s own later TMDB detail call redundant-but-harmless and matches the codebase's existing idempotent-save convention, avoiding a new parameter-passing path between `BulkImportService` and `EnrichmentService`
- `EnrichmentService.enrich(UUID)` and `enrich(UUID, Map)` both delegate to a shared private `doEnrich(UUID, Map)` — the two callers diverge only at the single Wikipedia `fetch()` call site (4-arg vs. 5-arg overload), keeping the TMDB/OMDB/persist/OpenSearch-index steps byte-identical for both the save-flow and bulk-import paths with zero duplication

## Deviations from Plan

**1. [Rule 1 - Bug] Fixed lambda "effectively final" compile error in `resolveAndPersistImdbId()`**
- **Found during:** Task 1 compile verification
- **Issue:** The plan's action text described reassigning `imdbId` to `null` when blank, then referencing it inside a lambda passed to `movieRepository.findById(movieId).ifPresent(...)` — Java requires lambda-captured locals to be effectively final, and a reassigned local fails that check
- **Fix:** Introduced an intermediate `extractedImdbId` local for the blank-check reassignment, then a `final String imdbId = extractedImdbId;` copy before the lambda
- **Files modified:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`
- **Commit:** `3349500`

**2. [Rule 1 - Bug] Fixed a test-only movieId collision in the two-pass contract test**
- **Found during:** Task 2 test run
- **Issue:** The plan's Task 2 action text stubbed `movieRepository.findById(any())` for the 2-line SPARQL-batch test but relied on the `@BeforeEach`'s shared `lenient().when(movieService.initiate(anyString(), anyInt()))` stub, which returns one fixed `MovieInitiateResult` (one fixed UUID) for every `initiate()` call regardless of `tmdbId` — this collapsed both matched lines onto the same `movieId` key in `imdbIdByMovieId`'s `HashMap`, so only the second line's imdbId survived and the SPARQL-call assertion failed (`Wanted but not invoked` with only 1 id instead of 2)
- **Fix:** Added two specific `when(movieService.initiate(EMAIL, <tmdbId>))` stubs returning distinct UUIDs, overriding the shared lenient stub for this test only
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
- **Commit:** `6dedea9`

**3. [Rule 1 - Bug] Marked `userRepository.findByEmail()`'s `@BeforeEach` stub `lenient()`**
- **Found during:** Task 2 test run
- **Issue:** `shouldPersistImdbId_whenTmdbDetailReturnsOne()` and `shouldReturnNull_whenTmdbDetailCallFails()` call `resolveAndPersistImdbId()` directly, which never reaches `userRepository.findByEmail()` (only `processLine()`/`runImport()` do) — the pre-existing strict `when(userRepository.findByEmail(EMAIL))` stub in `@BeforeEach` tripped Mockito's `UnnecessaryStubbingException` for these two new tests
- **Fix:** Marked the stub `lenient()`
- **Files modified:** `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java`
- **Commit:** `6dedea9`

**4. [Minor cleanup] Dropped an unused `de.moviearchive.movie.Movie` import**
- **Found during:** Task 1 compile verification
- **Issue:** The plan's action text listed `de.moviearchive.movie.Movie` among the imports to add to `BulkImportService.java`, but the final implementation's lambda parameter type is inferred (`movie -> { ... }`), leaving the explicit import unused
- **Fix:** Removed the unused import
- **Files modified:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java`
- **Commit:** `3349500`

## Issues Encountered

None beyond the deviations documented above. All verification commands specified in the plan (`BulkImportServiceTest`, `EnrichmentServiceTest`, `BulkImportControllerTest`) pass green, including the full-pipeline integration test with zero new WireMock stubs — confirming Plan 1's empty-list guard on `resolveViaWikidataSparql()` keeps that test network-safe exactly as the plan's acceptance criteria require. Real outbound calls to TMDB/Wikidata during async background processing in this sandboxed test environment fail closed with `Connection refused`, which is expected and does not affect any test assertion (the same pre-existing sandbox behavior noted in Plan 1's SUMMARY).

## User Setup Required

None.

## Next Phase Readiness

- This plan completes D-03 (the last of Phase 13's remaining locked decisions covered by this wave). `BulkImportService`'s enrichment dispatch no longer has the one-movie-at-a-time exposure that caused the original ~630-movie rate-limit incident — a bulk import of N matched lines now resolves Wikidata data in at most `ceil(N/50)` SPARQL requests (Plan 1's chunk size), never N.
- No blockers for merge. This plan's files (`BulkImportService`, `EnrichmentService`, `MatchedLine`, `BulkImportServiceTest`) do not overlap with the concurrently-executing Plan 2's files (`WikiReloadService`).

## Self-Check: PASSED

All files created/modified verified present on disk: `MatchedLine.java`, `BulkImportService.java`, `EnrichmentService.java`, `BulkImportServiceTest.java`. Both commits (`3349500`, `6dedea9`) verified present in `git log --oneline --all`.

---
*Phase: 13-wikidata-sparql-batch-lookup*
*Completed: 2026-08-27*
