---
phase: 13-wikidata-sparql-batch-lookup
plan: 02
subsystem: api
tags: [wikidata, sparql, batch-reload, wikipedia, enrichment]

# Dependency graph
requires:
  - phase: 13-wikidata-sparql-batch-lookup (Plan 1)
    provides: "WikipediaClient.resolveViaWikidataSparql(List<String>) -> Map<String,String> and the fetch(..., Map<String,String>) overload — the batch-prefetch-aware public API this plan's restructuring calls into"
provides:
  - "WikiReloadService.batchReload() prefetches Wikidata for its entire cooldown-eligible set in one (or a few chunked) SPARQL call(s) before its per-movie loop starts"
  - "WikiReloadService.retryWikipedia(Movie, Map<String,String>) — the 2-arg overload batchReload() threads its prefetched map through"
  - "WikiReloadService.doRetryWikipedia(Movie, Map<String,String>) — private extraction shared by both retryWikipedia() overloads"
affects: [13-wikidata-sparql-batch-lookup (Plan 3: BulkImportService two-pass restructuring, same D-01/D-02 pattern applied to bulk-import)]

actuals:
  tokens: 2603
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Prefetch-then-loop restructuring: gather all IDs needed by a per-item loop, resolve them in one batched external call before the loop starts, then thread the resulting map through the unchanged per-item loop body"
    - "Public method overload + private do-prefix extraction: retryWikipedia(Movie) / retryWikipedia(Movie, Map) both delegate to a shared private doRetryWikipedia(Movie, Map), keeping the single-item and batch-prefetch call shapes identical in every step except Wikidata resolution"

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java

key-decisions:
  - "batchReload()'s imdbId collection (map + filter null/blank + distinct + toList) happens once, immediately after eligible is populated and before the per-movie loop — matches CONTEXT.md D-02's requirement that the prefetch runs before, not inside, the loop"
  - "retryWikipedia(Movie) delegates to doRetryWikipedia(movie, null); the null preResolvedTitles sentinel is what tells WikipediaClient.fetch() to fall back to its own single-ID SPARQL path, keeping the manual single-movie retry path's behavior byte-for-byte unchanged from before this plan"

patterns-established:
  - "Prefetch-then-loop for batch external-API restructuring (D-02) — the shape Plan 3's BulkImportService restructuring should mirror for its own two-pass approach"

requirements-completed: [D-02]

coverage:
  - id: D1
    description: "batchReload() calls wikipediaClient.resolveViaWikidataSparql(...) exactly once per invocation, positioned before the per-movie loop, with all eligible movies' imdbIds present in that single call's argument"
    requirement: "D-02"
    verification:
      - kind: unit
        ref: "WikiReloadServiceTest#shouldCallResolveViaWikidataSparqlOnce_withAllEligibleImdbIds — status: pass"
        status: pass
    human_judgment: false
  - id: D2
    description: "A 2-movie batchReload() run causes exactly 1 real HTTP request to /sparql (WireMock-verified), not 2 — the concrete, observable proof of D-02's stated purpose"
    requirement: "D-02"
    verification:
      - kind: integration
        ref: "WikiReloadServiceIntegrationTest#shouldMakeOneSparqlCall_forWholeBatch_notOnePerMovie — status: pass"
        status: pass
    human_judgment: false
  - id: D3
    description: "A movie whose imdbId has no SPARQL match (or isn't in the prefetched map) still resolves its Wikipedia page via the unchanged candidate-URL cascade"
    requirement: "D-02"
    verification:
      - kind: integration
        ref: "WikiReloadServiceIntegrationTest#shouldMakeOneSparqlCall_forWholeBatch_notOnePerMovie (asserts both movies' wikiUrl non-null after a SPARQL-empty batch response) — status: pass"
        status: pass
    human_judgment: false
  - id: D4
    description: "retryWikipedia(Movie) (1-arg, manual single-movie retry) is unaffected in shape — still resolves Wikidata via WikipediaClient's internal single-ID SPARQL path, routing through the unchanged 4-argument fetch() overload"
    requirement: "D-02"
    verification:
      - kind: unit
        ref: "WikiReloadServiceTest#shouldSetTimestampAndWikiFields_onRetrySuccess and #shouldSetTimestampOnly_whenWikipediaNotFound (both call the 1-arg retryWikipedia(movie), zero stub changes needed) — status: pass"
        status: pass
    human_judgment: false

duration: 24min
completed: 2026-08-27
status: complete
---

# Phase 13 Plan 2: batchReload() Prefetch Restructuring Summary

**Restructured `WikiReloadService.batchReload()` to resolve Wikidata for its entire cooldown-eligible movie set in one SPARQL call before its per-movie loop starts, instead of resolving per movie inside the loop (D-02).**

## Performance

- **Duration:** 24 min
- **Started:** 2026-08-27T12:15:00Z (approx, worktree HEAD assertion)
- **Completed:** 2026-08-27T12:39:00Z (approx)
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- `WikiReloadService.batchReload()` collects every cooldown-eligible movie's `imdbId` (filtering null/blank, deduped) and calls `wikipediaClient.resolveViaWikidataSparql(imdbIds)` exactly once per invocation, before the per-movie loop begins — never once per movie
- New `retryWikipedia(Movie, Map<String,String>)` overload threads the prefetched map through the unchanged per-movie loop; the existing `retryWikipedia(Movie)` (1-arg, manual single-movie retry) is byte-for-byte unaffected in behavior — both now delegate to a shared private `doRetryWikipedia(Movie, Map<String,String>)`
- A movie whose `imdbId` has no SPARQL match still falls through to the existing candidate-URL cascade, exactly as it did before this phase — proven by a real WireMock-backed integration test, not just unit mocks
- New unit test proves `resolveViaWikidataSparql` is called exactly once with BOTH of a 2-movie batch's imdbIds in the same list argument; new integration test proves exactly 1 real HTTP request lands on `/sparql` for a 2-movie batch (not 2), with both movies still resolving `wikiUrl` via the fallback cascade after the SPARQL miss

## Task Commits

Each task was committed atomically:

1. **Task 1: batchReload() prefetch restructuring + retryWikipedia(Movie, Map) overload (D-02)** - `3936836` (feat)
2. **Task 2: Prove the batching contract — one SPARQL call for N eligible movies** - `487c9ef` (test)

**Plan metadata:** committed as part of this SUMMARY (worktree mode — orchestrator finalizes STATE.md/ROADMAP.md after merge)

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` - `batchReload()` gains the imdbId-collection + `resolveViaWikidataSparql()` prefetch step before its loop, and now calls `self.retryWikipedia(movie, resolvedTitles)`; new public `retryWikipedia(Movie, Map<String,String>)` overload; new private `doRetryWikipedia(Movie, Map<String,String>)` holding the extracted retry body (branches on `preResolvedTitles != null` to pick the 5- or 4-argument `fetch()` overload); `import java.util.Map;` added
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` - `shouldIsolateFailures_inBatchLoop` updated to the 5-argument `fetch()` matcher/verify (batchReload's prefetch path always calls the 5-arg overload now); new `shouldCallResolveViaWikidataSparqlOnce_withAllEligibleImdbIds` proving the one-call-with-both-ids contract at the unit level
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java` - `overrideWikipediaBaseUrl`'s `@DynamicPropertySource` now also registers `wikidata.sparql-base-url` against the shared WireMock instance (defensive — this test class's movies never have `imdbId` set today, so zero SPARQL calls fire regardless, but this keeps the class safe if a future test adds one); new `shouldMakeOneSparqlCall_forWholeBatch_notOnePerMovie` — the real, WireMock-backed proof of D-02's stated purpose

## Decisions Made
- The imdbId-collection stream (`map(Movie::getImdbId).filter(...).distinct().toList()`) lives inline in `batchReload()` rather than as a separate private helper — it's a single 5-line expression used exactly once, and `WikipediaClient.resolveViaWikidataSparql()` already owns all chunking/pacing/dedup logic downstream, so no additional abstraction was warranted here
- `doRetryWikipedia`'s branch (`preResolvedTitles != null ? fetch(..., preResolvedTitles) : fetch(...)`) is a ternary rather than an if/else — keeps the extracted method's single responsibility (assemble args, delegate, handle the two exception paths) visually flat and matches the plan's exact specification

## Deviations from Plan

None - plan executed exactly as written. Both tasks' acceptance criteria were met, including the exact 5-argument `fetch()` matcher update and the new unit+integration test pair proving the one-call-per-batch contract.

## Issues Encountered

`WikiReloadServiceIntegrationTest#shouldRespectCooldownWindow_excludingRecentAttempts` fails in this sandboxed environment with a `Connection refused` against the class's WireMock instance and a Hibernate optimistic-locking warning ("Row was updated or deleted by another transaction"). This was investigated by temporarily reverting this plan's files to their pre-Task-1 state (commit `49f3611`, the merged Plan 1 baseline) via `git checkout 49f3611 -- <paths>` followed by a `git reset HEAD -- <paths>` to keep the git index correctly aligned with HEAD, then restoring this plan's changes from a scratchpad backup — the failure reproduces byte-for-byte identically on the unmodified baseline, confirming it is a pre-existing environment flake (this same Testcontainers/WireMock resource-contention class of issue was already documented in 13-01-SUMMARY.md's "Issues Encountered"), not a regression introduced by this plan. All other tests in the class — including the new `shouldMakeOneSparqlCall_forWholeBatch_notOnePerMovie` — pass individually and alongside each other. Both tasks' own required `<verify>`/`<acceptance_criteria>` commands (`WikiReloadServiceTest`, and this plan's two new tests) pass cleanly. As in Plan 1, `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` was required for Testcontainers to find this machine's OrbStack Docker socket.

## User Setup Required

None - no external service configuration required. This plan only restructures internal control flow around Plan 1's already-shipped SPARQL client.

## Next Phase Readiness

- Plan 3 (`BulkImportService` two-pass restructuring, D-03) can follow the exact same "collect IDs → one batch resolve call → thread map through unchanged per-item logic" shape this plan establishes for `batchReload()`, adapted for bulk-import's two-pass sequencing (TMDB-detail-then-SPARQL-batch-then-per-line enrich).
- No blockers. `WikiReloadService`'s `batchReload()`/`retryWikipedia()` surface is stable and independent of Plan 3's `BulkImportService`/`EnrichmentService` changes — no shared files between the two plans' Task diffs.

## Self-Check: PASSED

Both modified-file sets verified present on disk (`WikiReloadService.java`, `WikiReloadServiceTest.java`, `WikiReloadServiceIntegrationTest.java`). Both commits (`3936836`, `487c9ef`) verified present in `git log --oneline --all`.

---
*Phase: 13-wikidata-sparql-batch-lookup*
*Completed: 2026-08-27*
