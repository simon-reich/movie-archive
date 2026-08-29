# Phase 12: Wikidata-based Wikipedia lookup - Pattern Map

**Mapped:** 2026-08-26
**Files analyzed:** 3 (1 modified production file, 1 modified test file, N new WireMock fixtures)
**Analogs found:** 3 / 3 (all patterns sourced from within the same file being modified — this phase is additive to an existing class, not a new component)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|----------------|
| `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` (add `tryFetchViaWikidata()`, modify `fetch()` signature/body, add dev-log helper) | service (external API client) | request-response (paced, 2 sequential outbound HTTP calls) | itself — `tryFetchViaSearch()` (lines 179-208) and `tryFetch()` (lines 210-248) in the same file | exact (same class, same private-method idiom) |
| `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` (add Wikidata WireMock fixtures/tests, extend `@DynamicPropertySource`) | test | request-response (WireMock stub + assertion) | itself — existing test methods, esp. `shouldHonorRetryAfterBackoff_beforeSubsequentRequests()` (lines 137-166) | exact |
| `backend/src/test/resources/fixtures/wikidata/*.json` (new fixture dir: `search-found.json`, `search-not-found.json`, `sitelinks-found.json`) | test fixture (file I/O) | file-I/O | `backend/src/test/resources/fixtures/wikipedia/*.json` (loaded via `loadFixture()`, lines 42-46 of test) | exact — same directory convention, sibling `fixtures/{source}/` folder |
| `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` (line 103 — call-site signature only, if `fetch()` gains an `imdbId` param) | service (caller) | request-response | itself | exact — no structural change, only an added argument |
| `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` (line 79 — same call-site consideration) | service (caller) | request-response | itself | exact |

**Note on caller signature impact:** `WikipediaClient.fetch()` is currently `fetch(String originalTitle, String title, int year)` — it does NOT currently receive `imdbId`. Per RESEARCH.md's `tryFetchViaWikidata(imdbId)` design, `fetch()` needs a 4th parameter (`String imdbId`) threaded through from both call sites (`EnrichmentService.java:103`, `WikiReloadService.java:79`), both of which already have `movie.getImdbId()` available in scope (confirmed: `EnrichmentService.java:85` calls `omdbClient.fetch(movie.getImdbId(), omdbKey)` a few lines above the Wikipedia call, so `movie` is in scope at both call sites). This is a mechanical signature change, not a new pattern — same one-line-diff shape at each call site.

## Pattern Assignments

### `WikipediaClient.java` — new `tryFetchViaWikidata(String imdbId)` method

**Analog:** `tryFetchViaSearch()` and `tryFetch()`, same file (`backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java`)

**Imports already present** (lines 1-16) — no new imports needed beyond what's already there (`JsonNode`, `WebClient`, `WebClientResponseException`, `Optional`):
```java
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.Optional;
```

**Pacing pattern to reuse verbatim** (lines 84-92, `paceRequest()`):
```java
private void paceRequest() {
    Instant waitUntil = backoffUntil.get();
    Duration remaining = Duration.between(Instant.now(), waitUntil);
    if (!remaining.isNegative() && !remaining.isZero()) {
        log.warn("Wikipedia rate-limit backoff in effect — waiting {}s before next request", remaining.toSeconds());
        sleepQuietly(remaining.toMillis());
    }
    sleepQuietly(requestPacingMs);
}
```
Call `paceRequest()` before EACH of the two new outbound calls (search + sitelinks), exactly as `tryFetchViaSearch()` calls it once before its one HTTP call (line 181) and `fetch()`'s downstream `tryFetch()`/`fetchSection()` each call it before their own request (lines 212, 252).

**429 handling pattern to reuse verbatim** (lines 198-206, from `tryFetchViaSearch()`):
```java
} catch (WebClientResponseException e) {
    if (e.getStatusCode().value() == 429) {
        recordRateLimited(e, "search term=" + searchTerm);
    } else {
        log.debug("Wikipedia search API failed for term={} status={}", searchTerm, e.getStatusCode().value());
    }
} catch (Exception e) {
    log.debug("Wikipedia search API exception for term={}: {}", searchTerm, e.getMessage());
}
return Optional.empty();
```
Same shape applies to the new method: 429 → `recordRateLimited(e, "wikidata imdbId=" + imdbId)` (reuses the shared `backoffUntil` AtomicReference, lines 73/110-125); any other status or exception → `log.debug(...)` and `Optional.empty()`. A plain HTTP 404 from the sitelinks REST call is NOT an error condition — it must be treated as a normal "no match" fall-through (see RESEARCH.md Pitfall 2), same as `tryFetchViaSearch()` treats an empty `search[]` array as a normal fall-through (line 190: `if (!hits.isArray()) return Optional.empty();`).

**Query-param pattern to reuse** — parameterized `.uri()` call, NOT string concatenation (per RESEARCH.md V5 note), matching `tryFetchViaSearch()`'s own call (lines 182-187):
```java
JsonNode response = webClient.get()
        .uri("/w/api.php?action=query&list=search&srsearch={q}+film&srlimit=5&format=json",
                searchTerm + " " + year)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
```
Contrast with `tryFetch()`'s deliberate string-concat exception (lines 213-215) — that one is a documented special case (parentheses encoding), not the norm; the new Wikidata calls should follow the `tryFetchViaSearch()` parameterized style since neither `imdbId` nor `qid` need literal-parenthesis handling.

**Delegation to existing `tryFetch()` once a title is resolved** — no new extraction logic needed; call the existing private method (lines 210-248) directly, exactly as `tryFetchViaSearch()` already does at line 195 (`Optional<WikipediaResult> result = tryFetch(slug);`).

**Second WebClient bean / base-url wiring** — mirror the existing constructor pattern (lines 75-82):
```java
public WikipediaClient(WebClient.Builder builder,
                       @Value("${wikipedia.base-url:https://en.wikipedia.org}") String baseUrl) {
    this.baseUrl = baseUrl;
    this.webClient = builder
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", "MovieArchive/0.1")
            .build();
}
```
Add a second `@Value("${wikidata.base-url:https://www.wikidata.org}") String wikidataBaseUrl` constructor param and a second `WebClient` field built the same way (own `builder.baseUrl(wikidataBaseUrl)...build()`), matching how `OmdbClient` (below) and `WikipediaClient` each independently construct their own `WebClient` from an injected `builder` + `@Value` base URL — this is the established one-client-per-external-host convention in this codebase, not a one-off.

---

### `WikipediaClientTest.java` — new Wikidata test methods + fixtures

**Analog:** same file, `shouldHonorRetryAfterBackoff_beforeSubsequentRequests()` (lines 137-166) and `shouldReturnResult_whenFirstCandidateHits()` (lines 69-126)

**`@DynamicPropertySource` extension pattern** (lines 26-34) — add `wikidata.base-url` alongside the existing three:
```java
@DynamicPropertySource
static void overrideWikipediaBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("wikipedia.base-url", wireMock::baseUrl);
    registry.add("tmdb.base-url", wireMock::baseUrl);
    registry.add("omdb.base-url", wireMock::baseUrl);
    // ADD: registry.add("wikidata.base-url", wireMock::baseUrl);
    registry.add("wikipedia.rate-limit-fallback-backoff-s", () -> "1");
    registry.add("wikipedia.rate-limit-max-backoff-s", () -> "5");
}
```

**Fixture-loading pattern** (lines 42-46):
```java
private String loadFixture(String path) throws IOException {
    return new String(
            getClass().getClassLoader().getResourceAsStream(path).readAllBytes(),
            StandardCharsets.UTF_8);
}
```
Reuse unmodified for `fixtures/wikidata/*.json` files.

**WireMock stub pattern for a distinct query-param match** (lines 77-84, from `shouldReturnResult_whenFirstCandidateHits`):
```java
wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
        .withQueryParam("action", containing("parse"))
        .withQueryParam("page", containing("Inception_(2010_film)"))
        .withQueryParam("prop", containing("sections"))
        .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(sectionsJson)));
```
For the new Wikidata calls, stub on `urlPathEqualTo("/w/api.php")` + `withQueryParam("srsearch", containing("haswbstatement:P345"))` for the search call, and a separate `urlPathMatching("/w/rest.php/wikibase/v1/entities/items/.*/sitelinks/enwiki")` stub for the sitelinks call (path-based, not query-param, since the REST endpoint uses a path segment for the QID).

**404 stub pattern** — mirrors the existing 429 stub shape (lines 139-147), just with a different status/no body:
```java
wireMock.stubFor(get(urlPathMatching("/w/rest.php/wikibase/v1/entities/items/.*/sitelinks/enwiki"))
        .willReturn(aResponse().withStatus(404)));
```

**429-regression test shape to mirror** for a Wikidata-specific pacing regression test (lines 137-166) — same `atPriority()` + `Instant start/elapsed` timing-assertion structure, just targeting the Wikidata host stub instead of `/w/api.php` sections.

---

## Shared Patterns

### External API client construction (WebClient + @Value base URL)
**Source:** `WikipediaClient` constructor (lines 75-82), `OmdbClient` constructor (`backend/src/main/java/de/moviearchive/enrichment/OmdbClient.java`, lines 17-20)
**Apply to:** The new second `WebClient` instance for `wikidata.org` inside `WikipediaClient`
```java
public OmdbClient(WebClient.Builder builder,
                  @Value("${omdb.base-url:https://www.omdbapi.com}") String baseUrl) {
    this.webClient = builder.baseUrl(baseUrl).build();
}
```

### Pacing + shared backoff window (429 handling)
**Source:** `WikipediaClient.paceRequest()` / `recordRateLimited()` / `backoffUntil` (lines 73-125)
**Apply to:** Both new Wikidata HTTP calls — MUST reuse this exact `AtomicReference<Instant> backoffUntil`, not a separate mechanism (per CONTEXT.md D-05 discretion, resolved by RESEARCH.md to "reuse")

### Optional-returning private methods that never throw on "not found"
**Source:** `tryFetch()` (lines 210-248), `tryFetchViaSearch()` (lines 179-208), `fetchSection()` (lines 250-271)
**Apply to:** `tryFetchViaWikidata()` — every failure path (null/blank imdbId, empty search hits, 404 sitelink, network error, non-429 error status) returns `Optional.empty()` and lets `fetch()` fall through to `buildCandidates()`. Never throw `WikipediaNotFoundException` from inside this method — only `fetch()`'s own final fallthrough (line 157-158) throws that.

### Dev-visibility resolution log (D-05) — no existing analog, net-new pattern
**No codebase analog exists** for a plain-text append-only side-channel log; this is intentionally throwaway per CONTEXT.md D-05. Simplest compliant shape (per RESEARCH.md Code Examples section):
```java
// Sketch — small private helper inside WikipediaClient, called at each fetch() outcome point
private void logResolution(String title, int year, String line) {
    try {
        Files.writeString(
                Path.of("wiki-resolution.log"),
                "%s (%d): %s%n".formatted(title, year, line),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
        log.debug("Failed to write wiki-resolution.log: {}", e.getMessage());
    }
}
```
Call sites: after a Wikidata hit (`"found via Wikidata"`), after a candidate-cascade hit (`"fallback candidate #N (slug)"`), and in the final `WikipediaNotFoundException` path (`"not found"`) inside `fetch()`. Path should be gitignored (repo-root-relative or `@Value("${wiki.resolution-log.path:./wiki-resolution.log}")` per RESEARCH.md A1) — verify `.gitignore` covers it before implementation (executor should add an entry if missing).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| Dev-visibility resolution log write (D-05) | utility (file append) | event-driven (fire-and-forget append per lookup) | No existing plain-text side-log pattern in the codebase — all existing logging goes through SLF4J (`@Slf4j`/`log.*`), which CONTEXT.md D-05 explicitly excludes ("not the normal application log"). Use the sketch above; it is deliberately minimal since the artifact is temporary/dev-only. |

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/enrichment/`, `backend/src/test/java/de/moviearchive/movie/`, `backend/src/test/resources/fixtures/`
**Files scanned:** `WikipediaClient.java`, `WikipediaClientTest.java`, `OmdbClient.java`, `EnrichmentService.java`, `WikiReloadService.java`
**Pattern extraction date:** 2026-08-26
