# Phase 13: Wikidata SPARQL Batch Lookup - Pattern Map

**Mapped:** 2026-08-26
**Files analyzed:** 4 modified (no new files — pure in-place restructuring per RESEARCH.md's "Recommended Project Structure")
**Analogs found:** 4 / 4 (all analogs are the files' own current state — this phase modifies existing methods in place; the "pattern to copy" is the surrounding code style already present in each file)

## File Classification

| Modified File | Role | Data Flow | Closest Analog | Match Quality |
|----------------|------|-----------|-----------------|----------------|
| `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` (add `resolveViaWikidataSparql()`, remove `tryFetchViaWikidata()` + `logResolution()`) | service (external-API client) | request-response (batch) | itself — `tryFetchViaWikidata()` (lines 240-280) is the exact method being replaced; `tryFetchViaSearch()`/`tryFetch()` (lines 300-369) show the same paceRequest/try-catch/429 idiom to keep | exact |
| `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` (add prefetch step to `batchReload()`) | service (orchestration/batch) | batch | itself — `batchReload()` (lines 113-138) is the loop being restructured | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` (two-pass restructuring of `runImport()`/`processLine()`) | service (orchestration/batch) | batch | `WikiReloadService.batchReload()` (same prefetch-then-loop shape, per D-02/D-03 parallel) | role-match (cross-file, same restructuring pattern) |
| `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` (extract TMDB-detail-then-imdbId step reusable by bulk-import Pass 1) | service (CRUD/orchestration) | request-response | itself — `enrich()` Step 1 (lines 62-79) is the code being pulled forward/reused | exact |
| `backend/src/test/resources/fixtures/wikidata-sparql/*.json` (NEW fixture dir) | test fixture | request-response | `backend/src/test/resources/fixtures/wikidata/` (`search-found.json`, `sitelinks-found.json`, `search-not-found.json`) | exact (same directory convention, new subdirectory) |
| `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` (add SPARQL test methods) | test | request-response | itself — existing WireMock `@DynamicPropertySource` + fixture-loading pattern (lines 26-49) | exact |

## Pattern Assignments

### `WikipediaClient.java` — replace `tryFetchViaWikidata()` with `resolveViaWikidataSparql()`

**Analog:** same file, `tryFetchViaWikidata()` (lines 240-280) and constructor (lines 97-109)

**Constructor / WebClient bean pattern to extend** (lines 97-109):
```java
public WikipediaClient(WebClient.Builder builder,
                       @Value("${wikipedia.base-url:https://en.wikipedia.org}") String baseUrl,
                       @Value("${wikidata.base-url:https://www.wikidata.org}") String wikidataBaseUrl) {
    this.baseUrl = baseUrl;
    this.webClient = builder
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", "MovieArchive/0.1")
            .build();
    this.wikidataWebClient = builder
            .baseUrl(wikidataBaseUrl)
            .defaultHeader("User-Agent", "MovieArchive/0.1")
            .build();
}
```
Add a third `sparqlWebClient` field + constructor param exactly this shape (RESEARCH.md's "New WebClient bean for the SPARQL endpoint" gives the literal code — reuse it verbatim, including the improved contact-info User-Agent per Pitfall 3).

**Paced-call + 429 pattern to copy** (lines 244-280, the method being replaced — copy the *shape*, not the two-REST-call content):
```java
private Optional<WikipediaResult> tryFetchViaWikidata(String imdbId) {
    if (imdbId == null || imdbId.isBlank()) {
        return Optional.empty();
    }
    try {
        paceRequest(wikidataRequestPacingMs);
        JsonNode searchResponse = wikidataWebClient.get()
                .uri(...)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        ...
    } catch (WebClientResponseException e) {
        if (e.getStatusCode().value() == 429) {
            recordRateLimited(e, "wikidata imdbId=" + imdbId);
        } else {
            log.debug("Wikidata lookup failed for imdbId={} status={}", imdbId, e.getStatusCode().value());
        }
        return Optional.empty();
    } catch (Exception e) {
        log.debug("Wikidata lookup exception for imdbId={}: {}", imdbId, e.getMessage());
        return Optional.empty();
    }
}
```
New method must follow the identical `paceRequest(wikidataRequestPacingMs)` → try → `WebClientResponseException` 429-branch (`recordRateLimited`) → generic-exception branch → **never throw, always return a best-effort result** shape. Signature per RESEARCH.md: `Map<String, String> resolveViaWikidataSparql(List<String> imdbIds)` — on any failure return whatever subset was resolved (or empty map), matching this project's "swallow-and-degrade" convention already used by `tryFetchViaWikidata`/`tryFetchViaSearch`.

**Chunking helper** (new, no existing analog in this file — RESEARCH.md's "Code Examples" chunk() idiom is the concrete source):
```java
static <T> List<List<T>> chunk(List<T> items, int size) {
    List<List<T>> chunks = new ArrayList<>();
    for (int i = 0; i < items.size(); i += size) {
        chunks.add(items.subList(i, Math.min(i + size, items.size())));
    }
    return chunks;
}
```
Call this internally from `resolveViaWikidataSparql()` (chunk size 50) so callers pass an arbitrarily large list — matches Phase 12's "one client, one method" pattern (RESEARCH.md Open Question #2 recommendation).

**`fetch()` call-site change** (lines 178-207) — currently:
```java
public WikipediaResult fetch(String originalTitle, String title, int year, String imdbId) {
    Optional<WikipediaResult> viaWikidata = tryFetchViaWikidata(imdbId);
    if (viaWikidata.isPresent()) { ... }
    ...
}
```
Per D-01, single-movie callers invoke the new batch method with a 1-element list; `fetch()`'s signature is unchanged, only its internal Wikidata step swaps from `tryFetchViaWikidata(imdbId)` to something like `resolveViaWikidataSparql(List.of(imdbId)).get(imdbId)` (or `fetch()` gains an optional pre-resolved-title parameter for D-02/D-03's prefetch-map case — planner's call per RESEARCH.md's Open Question #2, either shape must preserve the "falls through to candidate cascade unchanged" contract at lines 185-203).

**Deletion (D-04)** — remove entirely, no replacement:
- `resolutionLogPath` `@Value` field (lines 78-82)
- `logResolution()` method (lines 216-225)
- All 4 call sites inside `fetch()` (lines 182, 191, 200, 204)

---

### `WikiReloadService.java` — `batchReload()` prefetch step

**Analog:** same file, current `batchReload()` (lines 113-138)

**Current shape (the loop being restructured):**
```java
@Async("wikiReloadExecutor")
public void batchReload(UUID userId) {
    Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
    List<Movie> eligible = movieRepository.findEligibleForWikiReload(userId, cutoff);
    log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligible.size());

    for (int i = 0; i < eligible.size(); i++) {
        Movie movie = eligible.get(i);
        try {
            self.retryWikipedia(movie);
        } catch (Exception e) {
            log.warn("Wiki batch-reload: unexpected error for movieId={}: {}", movie.getId(), e.getMessage());
        }
        if (i < eligible.size() - 1) {
            try { Thread.sleep(pacingDelayMs); } catch (InterruptedException e) { ...; return; }
        }
    }
    log.info("Wiki batch-reload complete userId={} processed={}", userId, eligible.size());
}
```
**Insert per D-02, between `eligible` fetch and the loop:**
```java
List<String> imdbIds = eligible.stream()
        .map(Movie::getImdbId)
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .toList();
Map<String, String> resolved = wikipediaClient.resolveViaWikidataSparql(imdbIds);
```
Then thread `resolved` (or `resolved.get(movie.getImdbId())`) into the per-movie call inside the loop — `self.retryWikipedia(movie)` needs either an overload accepting a pre-resolved title, or `WikipediaClient.fetch()` needs an overload accepting one. Preserve the existing not-@Transactional-on-batchReload / @Transactional-only-on-retryWikipedia split (class javadoc lines 18-32) — the prefetch call itself must NOT be wrapped in a transaction or `@Retryable` (class javadoc's explicit rule, lines 28-31, applies identically to this new step).

**Constructor/field pattern (unchanged, just context for where new state would go if needed):** lines 33-65, `@Lazy self` proxy pattern — no new self-invocation needed for the prefetch step since it doesn't require `@Transactional`.

---

### `BulkImportService.java` — two-pass restructuring

**Analog:** `WikiReloadService.batchReload()`'s prefetch-then-loop shape (same file family, same pattern applied per D-03), plus this file's own current `runImport()`/`processLine()` (lines 86-195).

**Current single-pass shape being replaced** (lines 86-111):
```java
@Async("bulkImportExecutor")
public void runImport(String email, String tmdbKey, List<String> rawLines, UUID batchId) {
    for (int i = 0; i < rawLines.size(); i++) {
        try {
            self.processLine(email, tmdbKey, rawLines.get(i), batchId).ifPresent(enrichmentService::enrich);
        } catch (Exception e) { ... }
        progressService.publish(batchId, i + 1, rawLines.size());
        if (i < rawLines.size() - 1) { Thread.sleep(pacingDelayMs); ... }
    }
    progressService.complete(batchId);
}
```
**Two-pass restructuring per D-03** needs:
1. Pass 1: for each matched line, call the TMDB *detail* endpoint (same one `EnrichmentService.enrich()` Step 1 calls, lines 64-79 below) to obtain `imdbId`, persist it onto the `Movie` row immediately (RESEARCH.md Pitfall 2's recommended Option (a) — simpler, matches existing idempotent-save pattern already used by `upsertLine()`, lines 216-230).
2. Pass 1.5: `wikipediaClient.resolveViaWikidataSparql(collectedImdbIds)` once for the whole run.
3. Pass 2: existing per-line `self.processLine(...).ifPresent(enrichmentService::enrich)` loop, unchanged in shape — `enrichmentService.enrich()` internally uses the pre-resolved map (needs to be threaded in — same open question as `WikiReloadService`).

**CR-01 commit-ordering rule to preserve** (comment at lines 90-93, `runImport()`) — must still apply to Pass 1's TMDB-detail call:
```java
// CR-01: processLine()'s @Transactional method returns before we get here, so
// its transaction has already committed — safe to fire the @Async enrich() call
// now. Calling enrich() from inside processLine() (while its own transaction is
// still open) raced the enrichment thread against the not-yet-committed INSERT.
```
Any new Pass-1 TMDB-detail-and-persist-imdbId step must respect the same commit-before-next-step ordering — do it inside (or right after) `saveAndUpsert()`'s transaction (lines 203-210), not from the un-transactional `runImport()` loop directly against a stale reference.

**Pacing/per-line failure isolation pattern to keep unchanged** (lines 88-107) — Pass 1's per-line TMDB-detail loop should reuse the same `try { ... } catch (Exception e) { log.warn(...) }` + `Thread.sleep(pacingDelayMs)` idiom already used in Pass 2/`runImport()`.

---

### `EnrichmentService.java` — TMDB-detail extraction (Pass 1 source pattern)

**Analog:** same file, `enrich()` Step 1 (lines 62-79) — this is the exact TMDB-detail-then-imdbId logic Pitfall 2 says must be "pulled forward" for bulk-import's Pass 1:
```java
// === Step 1: TMDB detail (MANDATORY) ===
log.info("Enriching movieId={} tmdbId={}", movieId, movie.getTmdbId());
JsonNode tmdbDetail = tmdbClient.fetchDetail(movie.getTmdbId(), tmdbKey);
movie.setRawTmdbJson(tmdbDetail);
movie.setTitle(tmdbDetail.path("title").asText(null));
movie.setOriginalTitle(tmdbDetail.path("original_title").asText(null));
String releaseDate = tmdbDetail.path("release_date").asText(null);
if (releaseDate != null && !releaseDate.isBlank()) {
    movie.setReleaseDate(LocalDate.parse(releaseDate));
}
int runtimeVal = tmdbDetail.path("runtime").asInt(0);
movie.setRuntime(runtimeVal > 0 ? runtimeVal : null);
// imdb_id comes from external_ids.imdb_id (appended via append_to_response)
String imdbId = tmdbDetail.path("external_ids").path("imdb_id").asText(null);
if (imdbId != null && imdbId.isBlank()) {
    imdbId = null;
}
movie.setImdbId(imdbId);
```
If BulkImportService's Pass 1 calls this same logic (either by extracting it into a shared method both `enrich()` and bulk-import's Pass-1 helper call, or by duplicating the minimal `tmdbClient.fetchDetail()` + `imdb_id` extraction), it must persist `movie.setImdbId(imdbId)` + `movieRepository.save(movie)` so `enrich()`'s own later Step 1 re-running is redundant-but-harmless (RESEARCH.md Pitfall 2, Option (a)).

**Wikipedia step call site to update for pre-resolved title** (lines 97-114) — same `wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId())` call as `WikiReloadService.retryWikipedia()` (they are already identical); whatever signature change `fetch()` gains for pre-resolved titles must be applied consistently at both call sites (this file line 103, and `WikiReloadService.java` line 79).

---

## Shared Patterns

### Paced external call + 429 backoff (reuse unchanged)
**Source:** `WikipediaClient.java` — `paceRequest(long)` (lines 122-130), `recordRateLimited()` (lines 148-163), shared `backoffUntil` `AtomicReference<Instant>` (line 95)
**Apply to:** the new `resolveViaWikidataSparql()` method — call `paceRequest(wikidataRequestPacingMs)` before the SPARQL GET, and on `WebClientResponseException` with status 429 call `recordRateLimited(e, "sparql batch")` exactly like every other method in this class (`tryFetchViaWikidata`, `tryFetchViaSearch`, `tryFetch`, `fetchSection`, all following the identical `if (e.getStatusCode().value() == 429) { recordRateLimited(...); } else { log.debug(...); }` branch).

### Self-proxy for @Transactional/@Async correctness
**Source:** `WikiReloadService.java` lines 48-65 (`@Lazy self` field + constructor javadoc), mirrored verbatim in `BulkImportService.java` lines 43, 56 (`@Lazy BulkImportService self`)
**Apply to:** any new prefetch helper that must run inside/outside a transaction boundary — do NOT call it via `this.` from the same class if it needs its own `@Transactional`/`@Async` semantics; route through `self.`.

### Never wrap orchestrating batch methods in @Retryable
**Source:** `WikiReloadService.java` class javadoc, lines 28-31: `"Neither retryWikipedia() nor batchReload() is annotated @Retryable — WikipediaClient.fetch() already exhausts a 10-candidate fallback internally; wrapping the caller in Spring-managed retry would re-run that entire cascade..."`
**Apply to:** `resolveViaWikidataSparql()`, the new `batchReload()` prefetch step, and any new `BulkImportService` Pass-1/Pass-1.5 methods — none of these should carry `@Retryable`; failures must be swallowed internally and return a partial/empty result.

### Per-line/per-movie failure isolation with pacing
**Source:** `WikiReloadService.batchReload()` lines 119-136 and `BulkImportService.runImport()` lines 88-107 — identical `try { ... } catch (Exception e) { log.warn(...) }` then conditional `Thread.sleep(pacingDelayMs)` (skipped after the last item) idiom.
**Apply to:** any new Pass-1 per-line loop added to `BulkImportService` for TMDB-detail resolution — reuse this exact idiom rather than inventing a new one.

### WireMock fixture-directory convention
**Source:** `backend/src/test/resources/fixtures/wikidata/{search-found,sitelinks-found,search-not-found}.json`, loaded via `WikipediaClientTest.loadFixture()` (lines 45-49) and stubbed via `wireMock.stubFor(get(urlPathEqualTo(...))...)` (line 57+)
**Apply to:** new `backend/src/test/resources/fixtures/wikidata-sparql/{batch-found,batch-partial,batch-empty}.json` per RESEARCH.md's Wave 0 Gaps — same `loadFixture()` + `wireMock.stubFor()` pattern, new `@DynamicPropertySource` entry for the SPARQL base URL (extending the existing block at `WikipediaClientTest.java` lines 28-37, which already registers `wikidata.base-url`; add `wikidata.sparql-base-url` alongside it).

## No Analog Found

None — this phase touches only existing files with existing direct predecessors for every changed method (RESEARCH.md confirms "no new files/folders" except the test-fixture subdirectory, which has a direct sibling analog).

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/enrichment/`, `backend/src/main/java/de/moviearchive/bulkimport/`, `backend/src/test/java/de/moviearchive/movie/`, `backend/src/test/resources/fixtures/`
**Files scanned:** `WikipediaClient.java`, `WikiReloadService.java`, `EnrichmentService.java`, `BulkImportService.java`, `WikipediaClientTest.java`, `fixtures/wikidata/*.json` (all read in full this session; RESEARCH.md's `[VERIFIED]` citations cross-checked against direct reads)
**Pattern extraction date:** 2026-08-26
**Note:** RESEARCH.md for this phase already contains extensive `[VERIFIED: file:line]` code citations and a fully worked architecture diagram — this PATTERNS.md reuses those citations but re-confirms them against direct file reads and adds the specific "copy this shape" framing the planner needs (which fields/methods/JavaDoc rules must be preserved verbatim vs. which logic must change).
