---
status: complete
phase: 13-wikidata-sparql-batch-lookup
source: [13-01-SUMMARY.md, 13-02-SUMMARY.md, 13-03-SUMMARY.md]
started: 2026-08-27T11:18:17Z
updated: 2026-08-27T11:18:17Z
---

## Current Test

[testing complete]

## Tests

### 1. WikipediaClient exposes exactly one Wikidata resolution method (resolveViaWikidataSparql) used by both fetch() overloads; the REST-era two-call method (tryFetchViaWikidata) no longer exists in the source file
expected: WikipediaClient exposes exactly one Wikidata resolution method (resolveViaWikidataSparql) used by both fetch() overloads; the REST-era two-call method (tryFetchViaWikidata) no longer exists in the source file
result: pass
source: automated
coverage_id: D1

### 2. A batch of N IMDb IDs resolves in ceil(N/50) SPARQL requests, never N requests; an empty IMDb-ID list makes zero HTTP calls
expected: A batch of N IMDb IDs resolves in ceil(N/50) SPARQL requests, never N requests; an empty IMDb-ID list makes zero HTTP calls
result: pass
source: automated
coverage_id: D2

### 3. When a caller supplies a pre-resolved title map (even empty), fetch() never issues an additional per-movie SPARQL call — hit or miss both skip the network call
expected: When a caller supplies a pre-resolved title map (even empty), fetch() never issues an additional per-movie SPARQL call — hit or miss both skip the network call
result: pass
source: automated
coverage_id: D3

### 4. The temporary wiki-resolution.log dev-visibility logging (logResolution, resolutionLogPath, and all call sites) is fully removed
expected: The temporary wiki-resolution.log dev-visibility logging (logResolution, resolutionLogPath, and all call sites) is fully removed
result: pass
source: automated
coverage_id: D4

### 5. A 429 from the SPARQL endpoint engages the same shared backoff window (recordRateLimited/backoffUntil) every other WikipediaClient method already writes to, not a separate/unpaced path
expected: A 429 from the SPARQL endpoint engages the same shared backoff window (recordRateLimited/backoffUntil) every other WikipediaClient method already writes to, not a separate/unpaced path
result: pass
source: automated
coverage_id: D5

### 6. The SPARQL VALUES-clause query shape (wdt:P345 + schema:about/isPartOf/name) is syntactically correct and resolves IMDb IDs to enwiki titles against the real query.wikidata.org endpoint, not just against WireMock fixtures
expected: The SPARQL VALUES-clause query shape (wdt:P345 + schema:about/isPartOf/name) is syntactically correct and resolves IMDb IDs to enwiki titles against the real query.wikidata.org endpoint, not just against WireMock fixtures
result: pass
source: automated
coverage_id: D6

### 7. batchReload() calls wikipediaClient.resolveViaWikidataSparql(...) exactly once per invocation, positioned before the per-movie loop, with all eligible movies' imdbIds present in that single call's argument
expected: batchReload() calls wikipediaClient.resolveViaWikidataSparql(...) exactly once per invocation, positioned before the per-movie loop, with all eligible movies' imdbIds present in that single call's argument
result: pass
source: automated
coverage_id: D1

### 8. A 2-movie batchReload() run causes exactly 1 real HTTP request to /sparql (WireMock-verified), not 2 — the concrete, observable proof of D-02's stated purpose
expected: A 2-movie batchReload() run causes exactly 1 real HTTP request to /sparql (WireMock-verified), not 2 — the concrete, observable proof of D-02's stated purpose
result: pass
source: automated
coverage_id: D2

### 9. A movie whose imdbId has no SPARQL match (or isn't in the prefetched map) still resolves its Wikipedia page via the unchanged candidate-URL cascade
expected: A movie whose imdbId has no SPARQL match (or isn't in the prefetched map) still resolves its Wikipedia page via the unchanged candidate-URL cascade
result: pass
source: automated
coverage_id: D3

### 10. retryWikipedia(Movie) (1-arg, manual single-movie retry) is unaffected in shape — still resolves Wikidata via WikipediaClient's internal single-ID SPARQL path, routing through the unchanged 4-argument fetch() overload
expected: retryWikipedia(Movie) (1-arg, manual single-movie retry) is unaffected in shape — still resolves Wikidata via WikipediaClient's internal single-ID SPARQL path, routing through the unchanged 4-argument fetch() overload
result: pass
source: automated
coverage_id: D4

### 11. BulkImportService.runImport() resolves TMDB detail (imdbId) for every newly-matched line BEFORE issuing a single batched SPARQL call for the whole run
expected: BulkImportService.runImport() resolves TMDB detail (imdbId) for every newly-matched line BEFORE issuing a single batched SPARQL call for the whole run
result: pass
source: automated
coverage_id: D-03-1

### 12. A 2-line bulk-import run calls wikipediaClient.resolveViaWikidataSparql() exactly once with both lines' imdbIds present together, never once per line
expected: A 2-line bulk-import run calls wikipediaClient.resolveViaWikidataSparql() exactly once with both lines' imdbIds present together, never once per line
result: pass
source: automated
coverage_id: D-03-2

### 13. enrichmentService.enrich(UUID, Map) (2-arg) is called once per matched line, threading the SAME resolved map; enrich(UUID) (1-arg) is never called from runImport()
expected: enrichmentService.enrich(UUID, Map) (2-arg) is called once per matched line, threading the SAME resolved map; enrich(UUID) (1-arg) is never called from runImport()
result: pass
source: automated
coverage_id: D-03-3

### 14. resolveAndPersistImdbId() returns null and never calls movieRepository.save() when tmdbClient.fetchDetail() throws — never throws itself, matching this codebase's swallow-and-degrade convention
expected: resolveAndPersistImdbId() returns null and never calls movieRepository.save() when tmdbClient.fetchDetail() throws — never throws itself, matching this codebase's swallow-and-degrade convention
result: pass
source: automated
coverage_id: D-03-4

### 15. EnrichmentService.enrich(UUID) (1-arg, save-flow) still calls the 4-argument WikipediaClient.fetch(...) overload unchanged — zero behavioral change from this plan
expected: EnrichmentService.enrich(UUID) (1-arg, save-flow) still calls the 4-argument WikipediaClient.fetch(...) overload unchanged — zero behavioral change from this plan
result: pass
source: automated
coverage_id: D-03-5

### 16. BulkImportControllerTest's existing full-pipeline integration tests pass with no new WireMock stubs, proving the empty-list guard from Plan 1 keeps this test network-safe
expected: BulkImportControllerTest's existing full-pipeline integration tests pass with no new WireMock stubs, proving the empty-list guard from Plan 1 keeps this test network-safe
result: pass
source: automated
coverage_id: D-03-6

## Summary

total: 16
passed: 16
issues: 0
pending: 0
skipped: 0

## Gaps

[none yet]
