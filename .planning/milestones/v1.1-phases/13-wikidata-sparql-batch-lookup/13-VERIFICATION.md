---
phase: 13-wikidata-sparql-batch-lookup
verified: 2026-08-27T13:15:00Z
status: passed
score: 14/14 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 13: Wikidata SPARQL Batch Lookup Verification Report

**Phase Goal:** Replace the per-movie two-call Wikidata REST lookup (CirrusSearch `action=query&list=search` for P345 + REST sitelinks) with a batched SPARQL query against `query.wikidata.org/sparql` that resolves multiple IMDb IDs to their enwiki article titles in a single request.
**Verified:** 2026-08-27T13:15:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | D-01: WikipediaClient's Wikidata resolution is a single SPARQL-based method — the two-call REST flow no longer exists | ✓ VERIFIED | `grep -cE 'tryFetchViaWikidata\|logResolution\|resolutionLogPath\|wikidataWebClient' WikipediaClient.java` returns 0; no `www.wikidata.org` reference remains in source or `application.properties` |
| 2 | D-01: fetch() has exactly one internal Wikidata entry point for both the no-prefetch (single-ID) case and the prefetched-map case | ✓ VERIFIED | `resolveWikidataResult(imdbId, preResolvedTitles)` is the sole entry point called by both `fetch(4-arg)` (delegates to 5-arg with `null`) and `fetch(5-arg, Map)` — read directly in `WikipediaClient.java:192-244` |
| 3 | D-04: temporary wiki-resolution.log dev-visibility logging fully removed | ✓ VERIFIED | Same residue grep (row 1) confirms `logResolution`/`resolutionLogPath` absent; `application.properties` has no resolution-log property |
| 4 | An empty IMDb-ID list makes zero HTTP calls | ✓ VERIFIED | Code inspection: `resolveViaWikidataSparql` returns `Map.of()` before any HTTP call when the filtered list is empty (`WikipediaClient.java:263-265`); confirmed in practice — `BulkImportControllerTest` (14/14 pass) never stubs TMDB detail or `/sparql` and still passes, proving the empty-list guard holds live |
| 5 | A batch of more than 50 IMDb IDs is split into multiple SPARQL requests, not one oversized request and not one per ID | ✓ VERIFIED | `WikipediaClientTest#shouldChunkRequests_whenMoreThanFiftyImdbIds` passes — 51 ids produce exactly 2 `/sparql` requests |
| 6 | A caller-supplied pre-resolved title map (even empty) means fetch() never issues an additional per-movie SPARQL call | ✓ VERIFIED | `WikipediaClientTest#shouldSkipSparqlCall_whenPreResolvedMapProvidedAndImdbIdAbsent` and `#shouldUsePreResolvedTitle_whenPresentInMap` both pass, asserting `wireMock.verify(0, ...)` on `/sparql` |
| 7 | D-02: WikiReloadService.batchReload() gathers all cooldown-eligible movies' imdbIds and resolves them via one resolveViaWikidataSparql() call before the per-movie loop starts | ✓ VERIFIED | Code inspection confirms prefetch step precedes the loop (`WikiReloadService.java:142-147`); `WikiReloadServiceTest#shouldCallResolveViaWikidataSparqlOnce_withAllEligibleImdbIds` (unit) and `WikiReloadServiceIntegrationTest#shouldMakeOneSparqlCall_forWholeBatch_notOnePerMovie` (WireMock-backed, real HTTP count) both pass |
| 8 | D-02: a movie whose imdbId has no SPARQL match still falls through to the existing candidate-URL cascade | ✓ VERIFIED | `shouldMakeOneSparqlCall_forWholeBatch_notOnePerMovie` asserts both movies' `wikiUrl` end up non-null after a SPARQL-empty batch response |
| 9 | WikiReloadService.retryWikipedia(Movie) (1-arg) is unaffected in shape | ✓ VERIFIED | `shouldSetTimestampAndWikiFields_onRetrySuccess` and `shouldSetTimestampOnly_whenWikipediaNotFound` pass with zero stub edits — still route through the unchanged 4-arg `fetch()` |
| 10 | D-03: BulkImportService.runImport() resolves TMDB detail/imdbId for every matched line BEFORE one batched SPARQL call, then enriches using cached results | ✓ VERIFIED | Code inspection confirms the three-pass structure (`BulkImportService.java:107-167`: Pass 1 match+resolve, Pass 1.5 one SPARQL call, Pass 2 enrich); `BulkImportServiceTest#shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun` passes, proving exactly one `resolveViaWikidataSparql` call with both lines' ids together |
| 11 | EnrichmentService.enrich(UUID) (1-arg, save-flow) is unaffected in shape | ✓ VERIFIED | `EnrichmentServiceTest` (4/4) passes with zero edits to stubs — still routes through the unchanged 4-arg `fetch()` |
| 12 | A bulk-import run resolves Wikidata for every matched line's imdbId in at most ceil(N/50) SPARQL requests, never one per line | ✓ VERIFIED | Same chunking logic as truth #5 is reused (single shared `resolveViaWikidataSparql`); `shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun` directly proves the 2-line case resolves in 1 call |
| 13 | Code-review CR-01 fix: Pass 2 dispatch is paced and per-call error-isolated (no unbounded/unpaced tight loop into the bounded enrichmentExecutor) | ✓ VERIFIED | `BulkImportService.java:148-164` shows `Thread.sleep(pacingDelayMs)` between dispatches and a per-call try/catch — matches commit `cf35820`'s diff exactly, read directly from current source |
| 14 | Code-review CR-02 fix: `wiki.retry.cooldown-days` reverted from the leftover TEMPORARY `0` back to `30` | ✓ VERIFIED | `application.properties` line: `wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}` — TEMPORARY comment removed, default restored |

**Score:** 14/14 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` | resolveViaWikidataSparql, chunk, resolveChunkViaWikidataSparql, resolveWikidataResult, fetch 5-arg overload, sparqlWebClient field | ✓ VERIFIED | All present, wired, and match plan spec exactly (read full file) |
| `backend/src/test/resources/fixtures/wikidata-sparql/batch-found.json` | SPARQL JSON Results fixture | ✓ VERIFIED | Exists on disk |
| `backend/src/test/resources/fixtures/wikidata-sparql/batch-partial.json` | Two-binding fixture | ✓ VERIFIED | Exists on disk |
| `backend/src/test/resources/fixtures/wikidata-sparql/batch-empty.json` | Zero-binding fixture | ✓ VERIFIED | Exists on disk |
| Old REST-era fixtures (`fixtures/wikidata/*.json`) | Should be deleted | ✓ VERIFIED | Directory no longer exists |
| `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` | retryWikipedia 2-arg overload, batchReload prefetch step, doRetryWikipedia extraction | ✓ VERIFIED | Present, matches plan exactly |
| `backend/src/main/java/de/moviearchive/bulkimport/dto/MatchedLine.java` | New record (UUID movieId, int tmdbId) | ✓ VERIFIED | Exists, one-line record as specified |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` | resolveAndPersistImdbId, two-pass runImport, paced Pass 2 (post-review fix) | ✓ VERIFIED | Present, matches fixed code exactly |
| `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` | enrich 2-arg overload, doEnrich extraction | ✓ VERIFIED | Present, matches plan exactly |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `WikipediaClient.fetch(4-arg)` | `fetch(5-arg, null)` -> `resolveWikidataResult` -> `resolveViaWikidataSparql(List.of(imdbId))` | delegation | ✓ WIRED | Confirmed by reading `fetch()`'s body |
| `WikipediaClient.fetch(5-arg, Map)` | `resolveWikidataResult(imdbId, Map)` | direct call, no per-movie SPARQL on batch path | ✓ WIRED | Confirmed; tests prove zero `/sparql` calls when a map is supplied |
| `WikiReloadService.batchReload()` | `WikipediaClient.resolveViaWikidataSparql(imdbIds)` (once) -> `retryWikipedia(movie, resolvedTitles)` (per movie) -> `WikipediaClient.fetch(..., resolvedTitles)` | prefetch-then-loop | ✓ WIRED | Confirmed by code + integration test asserting exactly 1 real HTTP request for a 2-movie batch |
| `BulkImportService.runImport()` Pass 1 | `self.processLine()` -> `self.resolveAndPersistImdbId()` -> Pass 1.5 `wikipediaClient.resolveViaWikidataSparql(allCollectedImdbIds)` -> Pass 2 `enrichmentService.enrich(movieId, resolvedTitles)` | two-pass sequencing | ✓ WIRED | Confirmed by code + unit test proving one SPARQL call for 2 matched lines and threading of the same map |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| SPARQL query template resolves a real IMDb id against the live Wikidata endpoint | `curl` against `query.wikidata.org/sparql` with the exact query string used in `resolveChunkViaWikidataSparql` for `tt1375666` | Returned `articleName: "Inception"` | ✓ PASS |
| Full targeted test run (all phase-relevant classes in one Gradle invocation) | `./gradlew test --tests WikipediaClientTest --tests WikiReloadServiceTest --tests WikiReloadServiceIntegrationTest --tests BulkImportServiceTest --tests BulkImportControllerTest --tests EnrichmentServiceTest` | BUILD SUCCESSFUL; 46/46 tests pass (10+4+5+9+14+4) | ✓ PASS |
| Residue check for deleted REST-era identifiers | `grep -cE 'tryFetchViaWikidata\|logResolution\|resolutionLogPath\|wikidataWebClient' WikipediaClient.java` | 0 | ✓ PASS |
| Debt-marker scan (TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER) on all phase-modified files | `grep -nE` across all 5 modified/created source files | No matches | ✓ PASS |

Note: `./gradlew test` (full unfiltered suite) was NOT run as the authoritative check, per the task brief — this environment has a documented pre-existing Postgres/Testcontainers connection-pool exhaustion under full-suite parallel forking that reproduces identically on the pre-phase-13 baseline. The targeted multi-class run above (which exercises every test class this phase touches, run together in a single Gradle invocation to also rule out cross-class WireMock/Testcontainers interference) is used as authoritative evidence instead, and all counts match the SUMMARY files' claims exactly.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| D-01 | 13-01 | Single SPARQL-based Wikidata resolution method replaces two-call REST flow | ✓ SATISFIED | Truths 1, 2, 5, 6 |
| D-02 | 13-02 | batchReload() prefetches Wikidata for entire eligible set before per-movie loop | ✓ SATISFIED | Truths 7, 8, 9 |
| D-03 | 13-03 | BulkImportService two-pass restructuring: TMDB-detail-then-SPARQL-batch-then-enrich | ✓ SATISFIED | Truths 10, 11, 12 |
| D-04 | 13-01 | Temporary dev-visibility resolution log removed | ✓ SATISFIED | Truth 3 |

No orphaned requirements — this phase does not use a formal REQUIREMENTS.md tracking surface (Phase 12's decision-as-requirement pattern, as documented in ROADMAP.md and 13-CONTEXT.md); absence from REQUIREMENTS.md is expected, not a gap.

### Anti-Patterns Found

None. No TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER markers, no empty stub implementations, no hardcoded-empty data flows found in any of the phase-modified files (`WikipediaClient.java`, `WikiReloadService.java`, `EnrichmentService.java`, `BulkImportService.java`, `MatchedLine.java`).

### Code Review Fix Verification (13-REVIEW.md)

Both Critical findings from the code review were independently re-verified against current source, not taken on the SUMMARY's word:

- **CR-01** (Pass 2 unpaced/unisolated dispatch into a bounded executor): confirmed fixed — `BulkImportService.java:148-164` now paces with `Thread.sleep(pacingDelayMs)` between dispatches and wraps each `enrichmentService.enrich()` call in its own try/catch, mirroring Pass 1's convention. Matches commit `cf35820`'s diff exactly.
- **CR-02** (`wiki.retry.cooldown-days` TEMPORARY value of 0 shipping to production): confirmed fixed — `application.properties` now reads `wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}` with the stale TEMPORARY comment removed.

Both fixes were verified by running the exact test classes the review's disposition cites (`BulkImportServiceTest`, `BulkImportControllerTest`, `WikiReloadServiceTest`, `WikiReloadServiceIntegrationTest`, `EnrichmentServiceTest`) together in this verification session — all 46 tests pass, matching the review's own verification claim.

The two open Warnings (WR-02: transient TMDB-failure edge case degrading a Wikidata match; WR-01 is resolved as a side effect of CR-01's fix) and the one Info finding (IN-01: no defense-in-depth format validation on imdbId in the SPARQL VALUES clause) remain open by the reviewer's own explicit disposition as lower-severity, non-blocking follow-ups — not part of this phase's D-01–D-04 must-haves, and not re-raised here as gaps.

## Gaps Summary

None. All 14 merged must-have truths (from PLAN frontmatter across all 3 plans, plus the post-review Critical-fix claims) are independently verified against current source code and passing tests — not merely asserted by SUMMARY.md. The live SPARQL query template was independently re-confirmed against the real `query.wikidata.org` endpoint during this verification (not just trusting Plan 1's own smoke-test claim). All git commits referenced in the SUMMARY files exist on `main`. Phase goal — replacing the per-movie two-call REST Wikidata lookup with a batched SPARQL query that resolves dozens of IMDb IDs per request across all three callers (save-flow, batch-reload, bulk-import) — is achieved.

---

_Verified: 2026-08-27T13:15:00Z_
_Verifier: Claude (gsd-verifier)_
