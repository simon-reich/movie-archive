# Phase 13: Wikidata SPARQL Batch Lookup - Research

**Researched:** 2026-08-26
**Domain:** Wikidata SPARQL Query Service integration + Spring async batch restructuring
**Confidence:** MEDIUM-HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01 (Batching scope — one method, everywhere):** The new SPARQL-based Wikidata lookup becomes the *only* Wikidata path, used by all three callers of `WikipediaClient.fetch()` — save-flow (`EnrichmentService`), manual retry (`WikiReloadService.retryWikipedia`), and batch-reload (`WikiReloadService.batchReload`). Single-movie callers (save-flow, manual retry) simply invoke it with a 1-element IMDb ID list. — Reversibility: reversible — matches Phase 12's D-03 "one client, one method, no path special-cased" pattern; purely an internal method replacement inside `WikipediaClient`.

**D-02 (Batch-reload restructuring):** `WikiReloadService.batchReload()` is restructured to prefetch: gather all cooldown-eligible movies' IMDb IDs first, resolve them via one (or a few chunked) SPARQL call(s) *before* the per-movie loop starts, then loop through movies using the pre-resolved title (falling through to the existing candidate-URL cascade per movie when a given IMDb ID had no SPARQL match). This is what actually delivers "dozens of IDs per request" — calling the SPARQL method once per movie from inside the existing loop would not reduce request count versus today, only swap the endpoint. — Reversibility: costly — reshapes `batchReload()`'s control flow; reverting means re-inlining the per-movie Wikidata call.

**D-03 (Bulk-import restructuring, two-pass):** `BulkImportService`'s enrichment loop is also restructured for batching — it has the same one-movie-at-a-time exposure that caused the original ~630-movie incident. Approach: two-pass. First resolve TMDB details (and therefore `imdbId`) for all matched lines in the run — unlike batch-reload, bulk-import's `imdbId` today is only discovered inside `EnrichmentService.enrich()`'s own TMDB detail call. Then issue one batched SPARQL call for all collected IMDb IDs. Then proceed per line through OMDB/Wikipedia using the cached SPARQL results. — Reversibility: costly — changes `BulkImportService.processLine()`/`runImport()`'s sequencing; reverting means collapsing back to per-line TMDB+enrich.

**D-04 (Dev-visibility log removal, Phase 12 D-05):** Remove the temporary `wiki-resolution.log` dev-visibility logging (the `logResolution()` method and its call sites in `WikipediaClient`) entirely as part of this phase. It served its debugging purpose during Phase 12 and is explicitly framed there as removable dev-only scaffolding. — Reversibility: reversible — pure deletion of a self-contained temporary method + `@Value` property + call sites; no schema, no dependents.

### Claude's Discretion
- Exact SPARQL query shape (e.g. `VALUES` clause binding multiple IMDb IDs via `wdt:P345`, combined with `wikibase:sitelinks`/`schema:about` to get the enwiki title in the same query) — technical/research call. **Resolved by this research: see Architecture Patterns > Pattern 1.**
- Batch chunk size — how many IMDb IDs per SPARQL request, given `query.wikidata.org`'s practical URL-length/query-complexity limits. Not raised as a locked preference during discussion; pick a conservative default and document the reasoning, consistent with this project's pattern of erring toward safety after two live rate-limit incidents. **Resolved by this research: 50 IDs/chunk, see Standard Stack > Alternatives Considered and Common Pitfalls #4.**
- Whether the new SPARQL Wikidata step reuses `WikipediaClient`'s existing `paceRequest()`/`backoffUntil` 429-handling machinery (same question Phase 12 left open for the REST calls, still open here for SPARQL) — technical integration detail. **Resolved by this research: yes, fully reusable — see Summary and Don't Hand-Roll.**
- Exact restructuring shape for `batchReload()`'s and `BulkImportService`'s prefetch step (e.g. a private helper returning `Map<String imdbId, String enwikiTitle>`, where SPARQL misses are simply absent from the map) — planner's call. **Partially addressed: see Open Questions #2.**

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope. Two pending todos were reviewed against this phase and explicitly not folded:
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — bulk-import results-page UI enhancements; unrelated to the Wikidata lookup mechanism this phase changes.
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — UI progress indicator for batch-reload; UI-layer concern, unrelated to this phase's backend SPARQL lookup mechanism.
</user_constraints>

<phase_requirements>
## Phase Requirements

This phase has no formal `REQUIREMENTS.md` IDs — per ROADMAP.md's note, it carries forward Phase 12's decision-as-requirement pattern, where CONTEXT.md's locked decisions (D-01..D-04 above) function as the phase's requirements.

| ID | Description | Research Support |
|----|-------------|-------------------|
| D-01 | SPARQL batch lookup becomes the only Wikidata path, used by all three `fetch()` callers | Architecture Patterns (System Architecture Diagram, Pattern 1-3); Don't Hand-Roll |
| D-02 | `batchReload()` restructured to prefetch-then-loop | System Architecture Diagram ("BATCH-RELOAD PATH"); Open Questions #2 |
| D-03 | `BulkImportService` restructured to two-pass (TMDB-detail collection → SPARQL batch → per-line enrich) | System Architecture Diagram ("BULK-IMPORT PATH"); Common Pitfalls #2 (imdbId not populated at match time) |
| D-04 | Remove `logResolution()`/`resolutionLogPath` dev-visibility scaffolding | Not independently researched — pure deletion, no technical risk; confirmed call sites via `[VERIFIED: WikipediaClient.java:210-225]` |
</phase_requirements>

## Summary

This phase replaces `WikipediaClient.tryFetchViaWikidata()`'s two-call REST flow (CirrusSearch `action=query&list=search` for P345, then a REST sitelinks lookup) with a single batched SPARQL query against `https://query.wikidata.org/sparql`. The query.wikidata.org SPARQL endpoint is a *distinct* service from `www.wikidata.org` (the REST/action-API host the current code and Phase 12's rate-limit incident hit) — it has its own documented usage limits, its own 429/Retry-After behavior, and does not share the CirrusSearch-backed search quota that caused the Phase 12 incident. This is confirmed by the official Wikidata Query Service User Manual (mediawiki.org), not assumed.

The core technical deliverable is a `VALUES`-clause SPARQL query that binds N IMDb IDs at once, joins through `wdt:P345`, and resolves the English Wikipedia article title via the `schema:about` / `schema:isPartOf` / `schema:name` triple pattern (the standard, widely-documented way to cross-reference a Wikidata item to its Wikipedia sitelink in RDF — confirmed via multiple independent community sources, though the exact combined query has not been run live in this session; treat it as `[CITED-pattern, ASSUMED-exact-syntax]` and smoke-test it manually via `curl` before wiring it into `WikipediaClient`, given this project's two prior live rate-limit surprises).

The existing `paceRequest()`/`backoffUntil`/`recordRateLimited()` 429-handling machinery in `WikipediaClient` is directly reusable for the SPARQL endpoint: the official docs confirm query.wikidata.org returns HTTP 429 with a `Retry-After` header when a client (IP + User-Agent) exceeds its quota — the same shape `recordRateLimited()` already parses. No new backoff logic is needed, only a new paced call site.

**Primary recommendation:** Add a new `WebClient` bean bound to `https://query.wikidata.org` (a third host, distinct from both `en.wikipedia.org` and `www.wikidata.org`), issue the batch SPARQL query via GET with `Accept: application/sparql-results+json`, chunk IMDb IDs conservatively at 50 per request, reuse `paceRequest(wikidataRequestPacingMs)` / `recordRateLimited()` unchanged, and restructure `batchReload()`/`BulkImportService` as described in CONTEXT.md D-02/D-03 to call this new batch method once (or a few times) up front rather than once per movie inside the existing loops.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| SPARQL batch IMDb→title resolution | API / Backend (`WikipediaClient`) | — | External API integration belongs in the existing client layer; no browser/frontend involvement (backend-only phase) |
| Batch-reload prefetch restructuring | API / Backend (`WikiReloadService`) | — | Orchestration of a batched external call before a persistence loop; pure backend concern |
| Bulk-import two-pass restructuring | API / Backend (`BulkImportService`, `EnrichmentService`) | — | Same as above; no UI change (feedback UI explicitly deferred, see CONTEXT.md) |
| 429/backoff handling | API / Backend (`WikipediaClient` shared state) | — | Cross-cutting concern already centralized in one `@Component`; extending it is cheaper and safer than a new mechanism |

## Package Legitimacy Audit

**Not applicable — this phase introduces no new external dependencies.** It reuses `org.springframework.boot:spring-boot-starter-webflux` (`WebClient`, already on the classpath and already used by `WikipediaClient`/`OmdbClient`/`TmdbClient`) and Jackson's `JsonNode` (already used throughout the enrichment package for parsing REST/SPARQL JSON responses alike). No `npm install` / `pip install` / new Gradle dependency is required.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-webflux` (`WebClient`) | already in `build.gradle.kts` [VERIFIED: backend/build.gradle.kts:34] `implementation("org.springframework.boot:spring-boot-starter-webflux")` | Issue the SPARQL GET request | Already the project's standard HTTP client for all external API calls (CLAUDE.md: "WebClient vs RestTemplate ... WebClient (already on classpath) is the current standard") |
| Jackson `JsonNode` | Spring Boot BOM-managed | Parse `application/sparql-results+json` response | Already used identically for TMDB/OMDB/Wikidata REST responses in `WikipediaClient.java` |

### Supporting
No new supporting libraries required — this phase is a pure query/response-shape change plus control-flow restructuring inside existing classes.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| SPARQL `VALUES` clause batch query | `wbgetentities` REST API with `sites=enwiki` and multiple `ids` params | `wbgetentities` accepts up to 50 QIDs per call, but you'd first need the QIDs — which still requires either a per-ID search call (the exact problem being fixed) or a separate P345→QID batch lookup. SPARQL resolves IMDb ID → enwiki title in one hop, one request; the REST alternative needs two batched calls minimum. Not recommended given the phase goal of "one request, dozens of IDs." |
| GET request (query string) | POST request (query in body) | GET is documented as fine and cacheable for "smaller queries" (mediawiki.org User Manual); POST avoids URL-length limits but isn't cached. At the recommended chunk size (50 IDs, template below), the URL stays well under 2KB — GET is the correct default here (see Common Pitfalls if the chunk size is later increased). |

**Installation:** None — no new dependency.

**Version verification:** N/A — no new package added; existing `spring-boot-starter-webflux` version is Spring-Boot-BOM-managed per this project's locked stack (build.gradle.kts).

## Architecture Patterns

### System Architecture Diagram

```
BATCH-RELOAD PATH (D-02):
  WikiReloadService.batchReload(userId)
    │
    ├─▶ 1. movieRepository.findEligibleForWikiReload(userId, cutoff)   [existing]
    │        → List<Movie> eligible
    │
    ├─▶ 2. NEW prefetch step: collect eligible[].imdbId → chunk(50)
    │        → for each chunk: wikipediaClient.resolveViaWikidataSparql(chunk)
    │        → merge into one Map<imdbId, enwikiTitle>   (misses simply absent)
    │
    └─▶ 3. existing per-movie loop (unchanged shape)
             for movie in eligible:
               self.retryWikipedia(movie)   [Transactional, unchanged]
                 → wikipediaClient.fetch(origTitle, title, year, imdbId)
                     → uses PRE-RESOLVED title from step 2's map if present
                     → else falls through to existing candidate cascade (unchanged)
               Thread.sleep(pacingDelayMs)   [existing, between movies]

BULK-IMPORT PATH (D-03, two-pass):
  BulkImportService.runImport(email, tmdbKey, rawLines, batchId)
    │
    ├─▶ PASS 1 (NEW): for each rawLine
    │        self.processLine(...) → on SAVED: fetch TMDB detail → imdbId
    │        (imdbId is NOT yet on Movie at match time — TMDB detail call
    │         must be pulled forward from inside EnrichmentService.enrich(),
    │         see Pitfall 2 below)
    │        collect all imdbIds from this run into one List
    │
    ├─▶ PASS 1.5 (NEW): chunk(50) → wikipediaClient.resolveViaWikidataSparql(chunk)
    │        → merge into one Map<imdbId, enwikiTitle>
    │
    └─▶ PASS 2: for each matched line
             enrichmentService.enrich(movieId)   [existing @Async entry point]
               → Wikipedia step uses PRE-RESOLVED title from the map if present
               → else falls through to existing candidate cascade (unchanged)

SPARQL CALL SHAPE (both paths funnel through the same new method):
  WikipediaClient.resolveViaWikidataSparql(List<String> imdbIds)
    │
    ├─▶ paceRequest(wikidataRequestPacingMs)          [reused, unchanged]
    ├─▶ GET https://query.wikidata.org/sparql?query={VALUES-clause SPARQL}
    │        Accept: application/sparql-results+json
    │        User-Agent: <descriptive UA, see Pitfall 3>
    ├─▶ on 429 → recordRateLimited(e, "sparql batch")   [reused, unchanged]
    └─▶ parse results.bindings[] → Map<imdbId, enwikiTitle>
             (bindings absent for any imdbId with no P345 match or no enwiki
             sitelink — caller treats absence as "fall through to cascade")
```

### Recommended Project Structure

No new files/folders — all changes are inside existing classes:
```
backend/src/main/java/de/moviearchive/enrichment/
├── WikipediaClient.java        # tryFetchViaWikidata() replaced by resolveViaWikidataSparql()
├── WikiReloadService.java      # batchReload() gets a prefetch step before its loop
└── EnrichmentService.java      # TMDB-detail extraction pulled into a reusable step (see Pitfall 2)

backend/src/main/java/de/moviearchive/bulkimport/
└── BulkImportService.java      # runImport()/processLine() restructured to two-pass (D-03)

backend/src/test/resources/fixtures/
└── wikidata-sparql/            # NEW fixture directory, same convention as fixtures/wikidata/
    ├── batch-found.json        # results.bindings for a successful multi-ID resolution
    ├── batch-partial.json      # some IDs resolve, some don't (bindings array shorter than input)
    └── batch-empty.json        # zero bindings (none of the batch's IDs have P345/enwiki match)
```

### Pattern 1: VALUES-clause batch SPARQL query
**What:** A single SPARQL `SELECT` that binds every IMDb ID in the chunk via `VALUES`, joins through `wdt:P345`, and resolves the enwiki article name via the `schema:about`/`schema:isPartOf` triple pattern.
**When to use:** Every call to `resolveViaWikidataSparql()` — this is the only query shape needed; chunking (not query restructuring) is how larger ID lists are handled.
**Example:**
```sparql
# Source: constructed from documented Wikidata RDF conventions (schema:about /
# schema:isPartOf / schema:name for Wikipedia-sitelink resolution — pattern confirmed
# via community sources: bobdc.com/blog/imdb2wp, Wikidata SPARQL query service/queries
# archives; NOT run live against query.wikidata.org in this research session —
# [ASSUMED — verify with a manual curl smoke test before wiring into code, see
# Common Pitfalls #1]
SELECT ?imdbId ?articleName WHERE {
  VALUES ?imdbId { "tt1375666" "tt0111161" "tt0068646" }
  ?film wdt:P345 ?imdbId .
  ?article schema:about ?film ;
           schema:isPartOf <https://en.wikipedia.org/> ;
           schema:name ?articleName .
}
```
Java construction (string-concat the VALUES literals — each IMDb ID is TMDB's canonical `tt\d+` form, safe to embed as a quoted string literal without additional escaping, same case-sensitivity assumption already documented in `WikipediaClient.java:236-238` [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:236-238] `"Note: haswbstatement string-property matching is case-sensitive; movie.getImdbId() is always TMDB's canonical lowercase \"tt\\d+\" form, so this is not a practical risk."`):
```java
String valuesClause = imdbIds.stream()
        .map(id -> "\"" + id + "\"")
        .collect(Collectors.joining(" "));
String query = "SELECT ?imdbId ?articleName WHERE { VALUES ?imdbId { " + valuesClause + " } "
        + "?film wdt:P345 ?imdbId . "
        + "?article schema:about ?film ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?articleName . }";
```

### Pattern 2: WebClient GET with SPARQL query string + Accept header
**What:** WebClient issues a GET with the SPARQL query as a URL-encoded query parameter and an explicit `Accept: application/sparql-results+json` header.
**When to use:** The single HTTP call inside `resolveViaWikidataSparql()`.
**Example:**
```java
// Source: mediawiki.org/wiki/Wikidata_Query_Service/User_Manual (CITED — confirms both
// the format=json query param AND the Accept header are valid; Accept header shown here
// as the SPARQL-1.1-standard mechanism) + en.wikibooks.org/wiki/SPARQL/Wikidata_Query_Service
JsonNode response = sparqlWebClient.get()
        .uri(uriBuilder -> uriBuilder.path("/sparql").queryParam("query", query).build())
        .accept(MediaType.parseMediaType("application/sparql-results+json"))
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
```

### Pattern 3: Parsing SPARQL 1.1 JSON results
**What:** Standard SPARQL Query Results JSON Format — `results.bindings` is an array of objects, one per matched row, each row keyed by the SELECT'd variable names.
**When to use:** Turning the SPARQL response into `Map<String imdbId, String enwikiTitle>`.
**Example:**
```java
// Source: SPARQL 1.1 Query Results JSON Format (W3C standard) — response shape confirmed
// via en.wikibooks.org/wiki/SPARQL/Wikidata_Query_Service [CITED]
Map<String, String> resolved = new HashMap<>();
JsonNode bindings = response.path("results").path("bindings");
if (bindings.isArray()) {
    for (JsonNode row : bindings) {
        String imdbId = row.path("imdbId").path("value").asText(null);
        String title = row.path("articleName").path("value").asText(null);
        if (imdbId != null && title != null) {
            resolved.put(imdbId, title);
        }
    }
}
return resolved;
```
Example response shape (hand-constructed from the documented SPARQL 1.1 JSON format, not captured live — use as the shape for WireMock fixtures):
```json
{
  "head": { "vars": ["imdbId", "articleName"] },
  "results": {
    "bindings": [
      { "imdbId": { "type": "literal", "value": "tt1375666" },
        "articleName": { "type": "literal", "value": "Inception", "xml:lang": "en" } }
    ]
  }
}
```

### Anti-Patterns to Avoid
- **Calling `resolveViaWikidataSparql()` once per movie inside the existing loops:** This is explicitly called out in CONTEXT.md D-02/D-03 — it "would not reduce request count versus today, only swap the endpoint." The prefetch step MUST run once (or a few chunked times) *before* the per-movie loop starts.
- **Reusing the existing `wikidataWebClient` bean for the SPARQL call:** That bean's base URL is `https://www.wikidata.org` [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:99,105-108] `"@Value(\"${wikidata.base-url:https://www.wikidata.org}\") String wikidataBaseUrl"` / `"this.wikidataWebClient = builder.baseUrl(wikidataBaseUrl)..."`. The SPARQL endpoint is a **different host**, `query.wikidata.org`. A new `WebClient` bean (or the same builder with a new base URL property, e.g. `wikidata.sparql-base-url`) is required.
- **Wrapping the new prefetch method in `@Retryable`:** Same rule the project already applies to `fetch()`/`batchReload()`/`runImport()` [CITED: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:28-31] `"Neither retryWikipedia() nor batchReload() is annotated @Retryable — WikipediaClient.fetch() already exhausts a 10-candidate fallback internally; wrapping the caller in Spring-managed retry would re-run that entire cascade..."`. The batch SPARQL method should swallow its own failures (network error, 429-exhausted, malformed response) and return whatever subset it managed to resolve, never throw.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SPARQL query result parsing | A custom SPARQL results parser/model class | Plain `JsonNode` path traversal (as shown in Pattern 3) | The SPARQL 1.1 JSON shape is simple (2-3 levels of nesting) and the project already parses TMDB/OMDB/Wikidata REST JSON the same way with zero DTOs — consistent with existing style, no new dependency (e.g. no need for `rdf4j` or a SPARQL client library) |
| 429/backoff handling for the SPARQL endpoint | A second, SPARQL-specific backoff tracker | The existing shared `backoffUntil`/`recordRateLimited()`/`paceRequest(long)` in `WikipediaClient` | Confirmed (mediawiki.org User Manual) that query.wikidata.org returns the same `429` + `Retry-After` shape the existing code already parses — no reason to duplicate the mechanism |

**Key insight:** This phase's SPARQL integration is a "shape swap," not new infrastructure — the existing WebClient-per-external-service pattern, the existing 429/backoff AtomicReference, and the existing JsonNode-based parsing style all apply unchanged. The actual engineering work is the *chunking and two-pass control-flow restructuring* (D-02/D-03), not the HTTP call itself.

## Common Pitfalls

### Pitfall 1: The exact SPARQL query has not been run live against query.wikidata.org in this research session
**What goes wrong:** The `schema:about`/`schema:isPartOf`/`schema:name` pattern is a well-established, widely-documented RDF convention for resolving a Wikidata item to its Wikipedia sitelink (confirmed via multiple independent community sources — see Sources), but no single official Wikidata page was found during this research with this *exact* query combined with `wdt:P345` and a `VALUES` clause. Treat the query template in this document as `[ASSUMED — pattern is well-supported, exact syntax unverified live]`.
**Why it happens:** Wikidata's own example-query pages (`Wikidata:SPARQL_query_service/queries/examples`, `Wikidata:SPARQL_tutorial`) were checked directly and do not contain this precise combined example verbatim — the pattern is assembled from general SPARQL/RDF knowledge of how Wikidata's `schema:about` sitelink triples work, cross-referenced against community blog posts and archived query-request threads.
**How to avoid:** Before wiring `resolveViaWikidataSparql()` into `WikipediaClient`, run one manual smoke test: `curl --header "Accept: application/sparql-results+json" -G 'https://query.wikidata.org/sparql' --data-urlencode 'query=SELECT ?imdbId ?articleName WHERE { VALUES ?imdbId { "tt1375666" } ?film wdt:P345 ?imdbId . ?article schema:about ?film ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?articleName . }'` and confirm it returns `Inception` for `tt1375666`. Given this project's history of two prior live rate-limit surprises (Phase 12's REST search endpoint, and the original Wikipedia REST incident), the planner should make this manual verification an explicit early task/checkpoint, not something discovered mid-implementation.
**Warning signs:** Empty `results.bindings` for IDs known to have a Wikipedia article; a SPARQL syntax error (400) from the endpoint.

### Pitfall 2: `imdbId` is not populated at bulk-import match time
**What goes wrong:** `BulkImportService.processLine()` only knows `tmdbId` (from search results) when a line is matched and saved [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:203-210] — `saveAndUpsert()` calls `movieService.initiate(email, match.tmdbId())`, no TMDB *detail* call (which is what yields `imdb_id`) happens here. The `imdbId` field is only set later, inside `EnrichmentService.enrich()`'s Step 1 [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:75-79] `"String imdbId = tmdbDetail.path(\"external_ids\").path(\"imdb_id\").asText(null); ... movie.setImdbId(imdbId);"`. If the two-pass restructuring (D-03) tries to collect `imdbId`s directly off freshly-created `Movie` rows before calling `enrich()`, it will find them all `null`.
**Why it happens:** The TMDB *search* endpoint (`processLine()`'s call) and the TMDB *detail* endpoint (`enrich()`'s call, the one with `external_ids` appended) are different TMDB calls with different response shapes — search results don't include `imdb_id`.
**How to avoid:** Pass 1 of the two-pass restructuring must itself call the TMDB detail endpoint (the same one `EnrichmentService.enrich()` Step 1 calls) for every newly-matched line, extract `imdb_id` there, and either (a) persist it onto the `Movie` row immediately so `enrich()`'s own later detail call is redundant-but-harmless, or (b) thread the resolved `imdbId` through to `enrich()` so it doesn't redo the TMDB call. Option (a) is simpler and matches the existing idempotent-save pattern; flag as a planner decision (CONTEXT.md leaves "exact restructuring shape" to Claude's Discretion).
**Warning signs:** SPARQL batch calls in bulk-import always resolve zero IDs even though the equivalent single-movie retry works fine.

### Pitfall 3: The current `User-Agent` header may not satisfy Wikimedia's policy for the SPARQL endpoint
**What goes wrong:** `WikipediaClient`'s existing `WebClient` beans send `User-Agent: MovieArchive/0.1` [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:102-108] `".defaultHeader(\"User-Agent\", \"MovieArchive/0.1\")"` for both the `webClient` and `wikidataWebClient` beans. The Wikimedia Foundation User-Agent Policy requires the UA string to include a way to contact the operator (an email address or a URL describing the project) — a bare app-name-and-version string is exactly the kind of "generic" UA the policy calls out as blockable. This has apparently not caused a hard block yet (Phase 12 shipped using this UA against `www.wikidata.org`), but the SPARQL endpoint is documented as actively enforcing this ("clients who don't comply ... may be blocked completely").
**Why it happens:** The UA was set once in Phase-0-era client setup and never revisited against the Wikimedia policy specifically.
**How to avoid:** When adding the new SPARQL `WebClient` bean, set its `User-Agent` to include a contact reference, e.g. `MovieArchive/0.1 (https://github.com/simon-reich/movie-archive)` — cheap to do, removes a known risk factor given this project already has two rate-limit incidents in its history. Not strictly required by CONTEXT.md's decisions, but strongly recommended; flag as a planner discretion item, not a locked requirement.
**Warning signs:** SPARQL calls fail with a 403 or an explicit "set a user-agent" error body even though the request is well-formed.

### Pitfall 4: URL length grows silently if chunk size is later increased
**What goes wrong:** GET is fine at the recommended 50-ID chunk size (query string stays well under ~1KB), but if someone later "optimizes" by bumping the chunk size to reduce request count further, GET requests can silently start failing (or getting truncated) once total URL length approaches typical proxy/server limits (commonly ~8KB but not guaranteed, and not documented specifically for query.wikidata.org).
**Why it happens:** No hard URL-length limit is documented by Wikidata for this endpoint; the User Manual only says "recommended to use GET for smaller queries and POST for larger queries" without a numeric threshold [CITED: mediawiki.org/wiki/Wikidata_Query_Service/User_Manual].
**How to avoid:** Keep the chunk size at the conservative default (50) documented in this research. If a future phase needs a larger chunk size, switch to POST (`query.wikidata.org/bigdata/namespace/wdq/sparql` with the query in the request body) rather than growing the GET URL indefinitely.
**Warning signs:** SPARQL calls that worked for small batches start returning 400s or connection resets only for larger batches.

### Pitfall 5: Ignoring 429s from the SPARQL endpoint risks escalation to a 24-hour ban
**What goes wrong:** Per Wikimedia mailing-list guidance, a client that "ignores HTTP 429 for long enough" against the Query Service can be banned for 24 hours, a materially worse outcome than the existing per-request backoff.
**Why it happens:** The existing `recordRateLimited()` mechanism already extends the shared `backoffUntil` window and is directly reusable (see Pattern 2 in Don't Hand-Roll) — the risk is only if the new call site is added *without* wiring it through `recordRateLimited()`/`paceRequest()`.
**How to avoid:** Ensure `resolveViaWikidataSparql()` catches `WebClientResponseException` for status 429 and calls `recordRateLimited(e, "sparql batch")` exactly like the existing `tryFetchViaWikidata()`/`tryFetch()`/`tryFetchViaSearch()` methods do [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:269-275].
**Warning signs:** Repeated 429s in logs with no growing backoff window between them.

## Code Examples

### Chunking a list into fixed-size batches (Java, no new dependency)
```java
// Source: standard idiom, no external library needed — Guava's Lists.partition() is NOT
// currently a dependency of this project; avoid adding it for a single call site.
static <T> List<List<T>> chunk(List<T> items, int size) {
    List<List<T>> chunks = new ArrayList<>();
    for (int i = 0; i < items.size(); i += size) {
        chunks.add(items.subList(i, Math.min(i + size, items.size())));
    }
    return chunks;
}
```

### New WebClient bean for the SPARQL endpoint
```java
// Extends the existing constructor pattern in WikipediaClient.java (lines 97-109) with a
// third WebClient bound to a third host.
private final WebClient sparqlWebClient;

public WikipediaClient(WebClient.Builder builder,
                       @Value("${wikipedia.base-url:https://en.wikipedia.org}") String baseUrl,
                       @Value("${wikidata.base-url:https://www.wikidata.org}") String wikidataBaseUrl,
                       @Value("${wikidata.sparql-base-url:https://query.wikidata.org}") String sparqlBaseUrl) {
    // ... existing two clients unchanged ...
    this.sparqlWebClient = builder
            .baseUrl(sparqlBaseUrl)
            .defaultHeader("User-Agent", "MovieArchive/0.1 (https://github.com/simon-reich/movie-archive)")
            .build();
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Two REST calls per movie (`CirrusSearch action=query&list=search` for P345, then REST sitelinks) | One batched SPARQL `VALUES` query for up to 50 IMDb IDs | This phase (13) | Reduces N movies × 2 requests to `ceil(N/50)` requests; entirely avoids the CirrusSearch-backed endpoint that tripped Wikidata's anonymous rate limiter in Phase 12's live testing |

**Deprecated/outdated:**
- `WikipediaClient.tryFetchViaWikidata(String imdbId)` (single-ID REST flow) — replaced by `resolveViaWikidataSparql(List<String> imdbIds)` per D-01. The per-movie candidate-URL cascade (`buildCandidates()`, `tryFetch()`, `tryFetchViaSearch()`) is UNCHANGED — it remains the fallback path when SPARQL has no match for a given ID.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The exact `wdt:P345` + `schema:about`/`schema:isPartOf`/`schema:name` SPARQL query template (Pattern 1) is syntactically correct and returns the expected shape when run live against `query.wikidata.org/sparql` | Architecture Patterns > Pattern 1; Common Pitfalls #1 | If the query has a syntax error or the predicate direction is reversed, `resolveViaWikidataSparql()` returns zero results for every batch, silently degrading to the (still-functional, unchanged) candidate cascade for every movie — no data loss, but zero benefit from this phase until fixed. Low risk of breakage, but real risk of the phase's core value (batching) not actually working until manually verified. |
| A2 | A conservative chunk size of 50 IMDb IDs per SPARQL request keeps the GET URL comfortably under any undocumented length limit and avoids tripping the documented 60s/60s query-time rate limit | Standard Stack > Alternatives Considered; Common Pitfalls #4 | If wrong (limit is much lower than assumed), some chunks could return 400 errors; the fix is simply lowering the constant, no architectural change needed |
| A3 | `MovieArchive/0.1 (https://github.com/simon-reich/movie-archive)` is a sufficiently "good" User-Agent per Wikimedia's policy to avoid being blocked | Common Pitfalls #3 | Low risk — worst case is the same 429/block behavior the current generic UA already risks; this change only reduces risk, never increases it |

## Open Questions

1. **Should the SPARQL query also validate against a redirect/disambiguation edge case (an item with multiple enwiki sitelinks, or a moved/merged Wikidata item)?**
   - What we know: The existing REST sitelinks flow takes the first (only) sitelink returned; the SPARQL `schema:about`/`isPartOf` pattern would, in the rare case of duplicate triples, return multiple bindings for the same `imdbId`.
   - What's unclear: Whether this is a real-world occurrence for films specifically (most films have exactly one Wikidata item and one enwiki article).
   - Recommendation: Not worth defensive code for v1 of this phase — if `Map<imdbId, title>` construction encounters a duplicate key, last-write-wins is an acceptable degradation (same practical outcome as today's "take first hit" REST behavior). Document as a known edge case, don't block the phase on it.

2. **Exact prefetch-helper method signature/location for `batchReload()` and `BulkImportService` (CONTEXT.md leaves this to planner discretion)**
   - What we know: CONTEXT.md suggests "a private helper returning `Map<String imdbId, String enwikiTitle>`, where SPARQL misses are simply absent from the map."
   - What's unclear: Whether this helper lives in `WikipediaClient` itself (as the public entry point `resolveViaWikidataSparql`, doing its own internal chunking) or as a separate orchestration helper in each caller that chunks and calls a lower-level single-chunk method.
   - Recommendation: Put chunking INSIDE `WikipediaClient.resolveViaWikidataSparql(List<String> imdbIds)` itself (it already owns pacing/backoff state) so both callers (`WikiReloadService`, `BulkImportService`) can pass an arbitrarily large list without knowing about the chunk-size constant. This keeps the "one client, one method" pattern from Phase 12's D-03 intact.

## Environment Availability

Skipped — this phase depends only on outbound HTTPS to `query.wikidata.org`, a public unauthenticated endpoint with no local install/tooling requirement. No new local dependency to audit (reuses the project's existing WebClient/Java 25/Spring Boot stack, all already verified present in Phase 0-12 work).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`useJUnitPlatform()` [VERIFIED: backend/build.gradle.kts:102]) + WireMock 3.13.0 [VERIFIED: backend/build.gradle.kts:23,75] `val wiremockVersion = "3.13.0"` / `testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")` + Testcontainers (postgres) |
| Config file | none — plain Gradle `test` task; per-test WireMock base URLs injected via `@DynamicPropertySource` (see `WikipediaClientTest.java:28-37`) |
| Quick run command | `./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
No formal REQUIREMENTS.md IDs for this phase (carries forward Phase 12's decision-as-requirement pattern per ROADMAP.md). Mapping against CONTEXT.md's locked decisions instead:

| Decision | Behavior | Test Type | Automated Command | File Exists? |
|----------|----------|-----------|--------------------|--------------|
| D-01 | `WikipediaClient` resolves via SPARQL batch for a 1-element list (single-movie callers) | unit (WireMock) | `./gradlew test --tests "*WikipediaClientTest*"` | ❌ Wave 0 — new test methods needed in existing `WikipediaClientTest.java` |
| D-02 | `batchReload()` prefetches via one/few SPARQL calls before its per-movie loop, falls through per-movie on SPARQL miss | unit/integration | `./gradlew test --tests "*WikiReloadService*"` | Check — confirm existing `WikiReloadServiceTest` (or equivalent) exists before assuming a fixture |
| D-03 | `BulkImportService` two-pass: TMDB-detail-then-SPARQL-batch-then-per-line enrich | integration | `./gradlew test --tests "*BulkImportService*"` | Check — confirm existing test class name/location |
| D-04 | `logResolution()`/`resolutionLogPath` removed; no residual call sites | unit | `./gradlew test --tests "*WikipediaClientTest*"` + grep for `logResolution` | ❌ Wave 0 — add a compile-time absence check is unnecessary; deletion is verified by successful compilation + no test referencing it |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"` (fast, WireMock-only, no Testcontainers spin-up needed for this specific class)
- **Per wave merge:** `./gradlew test` (full suite, includes Testcontainers Postgres)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/resources/fixtures/wikidata-sparql/batch-found.json` — SPARQL JSON response fixture, multiple IDs all resolve
- [ ] `backend/src/test/resources/fixtures/wikidata-sparql/batch-partial.json` — fixture, some IDs resolve and some don't (fewer bindings than input IDs)
- [ ] `backend/src/test/resources/fixtures/wikidata-sparql/batch-empty.json` — fixture, zero bindings
- [ ] New test methods in `WikipediaClientTest.java` covering: single-ID batch call (1-element list, D-01 single-movie path), chunk-size boundary (need to confirm actual `WikiReloadServiceTest`/`BulkImportServiceTest` file names and locations exist before planning their specific edits — not verified in this research session)

*Executor note: this research did not locate/open `WikiReloadServiceTest.java` or `BulkImportServiceTest.java` — confirm their existence and current coverage during planning/Wave 0, not assumed here.*

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | no | This phase touches no auth surface — internal service-to-external-API integration only |
| V3 Session Management | no | N/A |
| V4 Access Control | no | N/A — `batchReload`/`retryWikipedia`/`enrich` already enforce per-user scoping via existing `userId`/movie ownership checks, unchanged by this phase |
| V5 Input Validation | yes | IMDb IDs embedded in the SPARQL query string are TMDB-sourced, already validated as `tt\d+` by upstream TMDB response shape [VERIFIED: backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:75-78] — no free-form user input reaches the SPARQL query string, so standard string-escaping (quote-wrapping each ID literal, as shown in Pattern 1) is sufficient; no parameterized-query library needed for a batch-constant of trusted-shape values |
| V6 Cryptography | no | No secrets/keys involved — the SPARQL endpoint is public and unauthenticated |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| SPARQL injection via a maliciously-crafted IMDb ID value | Tampering | Not applicable in practice — `movie.getImdbId()` values originate exclusively from TMDB's `external_ids.imdb_id` field (never user-typed free text), and are shape-validated as `tt\d+` by TMDB itself. Still, wrap each literal in quotes (Pattern 1) rather than concatenating raw into the query body unescaped, as defense-in-depth. |
| Denial of the outbound integration via aggressive/unbounded batching | Tampering / DoS (self-inflicted, against the Wikidata service) | The chunk-size cap (50) + reused `paceRequest`/`recordRateLimited` backoff is the standard mitigation already established by this project's two prior rate-limit incidents; this phase's whole purpose is to further harden this |

## Sources

### Primary (HIGH confidence)
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — read in full this session; all `[VERIFIED]` line citations above
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — read in full this session
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — read in full this session
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` — read in full this session
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — read in full this session
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` + `backend/src/test/resources/fixtures/wikidata/*.json` — read in full this session
- `backend/build.gradle.kts`, `backend/src/main/resources/application.properties` — read in full this session
- [Wikidata Query Service/User Manual (mediawiki.org)](https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual) — official docs, fetched directly this session: 60s query timeout, 60s/60s rate limit, 30 errors/min, 5 parallel queries/IP, GET-vs-POST guidance, 429 + Retry-After behavior, User-Agent policy enforcement

### Secondary (MEDIUM confidence)
- [Wikimedia Foundation User-Agent Policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy) — referenced via WebSearch, policy requirements for contact info in UA string
- [en.wikibooks.org/wiki/SPARQL/Wikidata_Query_Service](https://en.wikibooks.org/wiki/SPARQL/Wikidata_Query_Service) — GET/POST endpoints, JSON content negotiation confirmed
- Mail-archive / phabricator threads on 429 + Retry-After behavior and 24h-ban escalation for the Query Service — cross-confirms the mediawiki.org User Manual's own statement of the same behavior

### Tertiary (LOW confidence)
- [bobdc.com/blog/imdb2wp](https://www.bobdc.com/blog/imdb2wp/) — community blog demonstrating `wdt:P345` + `contains(str(?wppage),'//en.wikipedia')` pattern; informed but did not fully confirm the exact `schema:about`/`schema:isPartOf`/`schema:name` combination used in this document's query template — hence Pitfall 1's explicit "verify live" recommendation
- Various Wikidata `Request a query` / `SPARQL query service/queries` archive pages — general VALUES-clause and schema:about/isPartOf usage patterns, not a single copy-paste-ready example for this exact use case

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependency, pure reuse of already-verified project stack
- Architecture: HIGH — restructuring shape is fully specified by CONTEXT.md D-01..D-04, confirmed against actual current code read this session
- SPARQL query shape / limits: MEDIUM — endpoint limits and 429/Retry-After behavior are HIGH confidence (official docs, fetched directly); the exact query template is MEDIUM/LOW confidence pending a live smoke test (Pitfall 1)
- Pitfalls: HIGH — grounded in actual code read this session (Pitfall 2, 5) and official docs (Pitfall 3, 4)

**Research date:** 2026-08-26
**Valid until:** 30 days (Wikidata Query Service limits are documented as "subject to change depending on resources and usage patterns" — re-verify if this phase's implementation slips past ~2026-09-25)
