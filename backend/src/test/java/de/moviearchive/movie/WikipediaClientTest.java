package de.moviearchive.movie;

import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.enrichment.WikipediaClient;
import de.moviearchive.enrichment.WikipediaNotFoundException;
import de.moviearchive.enrichment.WikipediaResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikipediaClientTest extends AbstractWireMockTest {

    @DynamicPropertySource
    static void overrideWikipediaBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("wikipedia.base-url", wireMock::baseUrl);
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("wikidata.sparql-base-url", wireMock::baseUrl);
        // A 1s Retry-After is enough to prove the backoff is honored without slowing the suite.
        registry.add("wikipedia.rate-limit-fallback-backoff-s", () -> "1");
        registry.add("wikipedia.rate-limit-max-backoff-s", () -> "5");
    }

    @Autowired
    private WikipediaClient wikipediaClient;

    private static final String MISSING_PAGE_RESPONSE =
            "{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\"}}";

    private String loadFixture(String path) throws IOException {
        return new String(
                getClass().getClassLoader().getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * All 6 candidates return "page not found" → WikipediaNotFoundException after exhausting all.
     */
    @Test
    void shouldTryAllSixCandidates_beforeFailing() {
        // Stub ALL Wikipedia section requests to return missing-page error
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MISSING_PAGE_RESPONSE)));

        assertThatThrownBy(() -> wikipediaClient.fetch("Inception", "Inception", 2010, null))
                .isInstanceOf(WikipediaNotFoundException.class)
                .hasMessageContaining("No Wikipedia page found for titles");
    }

    /**
     * First candidate (Inception_2010_film) hits → returns result with URL, summary, plot, critics.
     */
    @Test
    void shouldReturnResult_whenFirstCandidateHits() throws IOException {
        String sectionsJson = loadFixture("fixtures/wikipedia/inception-sections.json");
        String plotSectionJson = loadFixture("fixtures/wikipedia/inception-plot-section.json");
        String criticsSectionJson = loadFixture("fixtures/wikipedia/inception-critics-section.json");
        String summaryJson = loadFixture("fixtures/wikipedia/inception-plot.json");

        // First candidate is now Inception_(2010_film) — stub sections request for it
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("sections"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sectionsJson)));

        // Stub section 0 (summary/intro) — resolved title from fixture is "Inception (2010 film)"
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(summaryJson)));

        // Stub Plot section (index "1" from sections fixture)
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(plotSectionJson)));

        // Stub Critical response section (index "7" from sections fixture)
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(criticsSectionJson)));

        WikipediaResult result = wikipediaClient.fetch("Inception", "Inception", 2010, null);

        assertThat(result).isNotNull();
        assertThat(result.url()).contains("Inception_(2010_film)");
        assertThat(result.summary()).isNotBlank();
        assertThat(result.plot()).isNotBlank();
        assertThat(result.critics()).isNotBlank();
    }

    /**
     * D-01 happy path: imdbId resolves via one batched SPARQL request against /sparql to an
     * enwiki article title, and fetch() hands that title off to the existing tryFetch() for
     * section extraction — zero candidate-cascade HTTP requests. The fixture's resolved
     * articleName is exactly "Inception", matching the deleted sitelinks fixture's title
     * verbatim, so the downstream Wikipedia section stubs below are unchanged from before
     * this plan.
     */
    @Test
    void shouldReturnResult_viaWikidata_whenImdbIdMatchesP345() throws IOException {
        String batchFoundJson = loadFixture("fixtures/wikidata-sparql/batch-found.json");
        String sectionsJson = loadFixture("fixtures/wikipedia/inception-sections.json");
        String plotSectionJson = loadFixture("fixtures/wikipedia/inception-plot-section.json");
        String criticsSectionJson = loadFixture("fixtures/wikipedia/inception-critics-section.json");
        String summaryJson = loadFixture("fixtures/wikipedia/inception-plot.json");

        wireMock.stubFor(get(urlPathEqualTo("/sparql"))
                .withQueryParam("query", containing("tt1375666"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/sparql-results+json")
                        .withBody(batchFoundJson)));

        // Wikidata-resolved slug is "Inception" — matches Wikipedia's own redirect target.
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("sections"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sectionsJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(summaryJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(plotSectionJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(criticsSectionJson)));

        WikipediaResult result = wikipediaClient.fetch("Inception", "Inception", 2010, "tt1375666");

        assertThat(result).isNotNull();
        assertThat(result.url()).contains("Inception_(2010_film)");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/sparql")));
    }

    /**
     * Regression test: a 429 with a Retry-After header must actually delay subsequent
     * requests, not just get logged and immediately retried. First candidate 429s with
     * Retry-After: 1 (honoring the test override above); every other candidate + the
     * search-API fallback return a plain "page not found" 200. fetch() still ultimately
     * throws WikipediaNotFoundException (no candidate resolves), but must not do so
     * faster than the 1s backoff window — proving the backoff actually gated later
     * requests instead of being a no-op after logging.
     */
    @Test
    void shouldHonorRetryAfterBackoff_beforeSubsequentRequests() {
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .atPriority(1)
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("sections"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "1")
                        .withBody("{\"error\":\"rate limited\"}")));

        // Every other request (later candidates + search fallback) returns a normal
        // "page not found" so fetch() exhausts everything and throws, rather than hanging
        // on an unstubbed request. Lower priority (higher number) so the specific 429
        // stub above always wins for the request it targets.
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .atPriority(10)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MISSING_PAGE_RESPONSE)));

        Instant start = Instant.now();
        assertThatThrownBy(() -> wikipediaClient.fetch("Inception", "Inception", 2010, null))
                .isInstanceOf(WikipediaNotFoundException.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(950));
    }

    /**
     * A batch SPARQL response with fewer bindings than requested ids resolves only the ids
     * actually present in the response — the caller (batch-reload/bulk-import prefetch) sees
     * a partial map, not an error.
     */
    @Test
    void shouldResolveOnlyMatchedIds_whenSparqlBatchIsPartial() throws IOException {
        String batchPartialJson = loadFixture("fixtures/wikidata-sparql/batch-partial.json");

        wireMock.stubFor(get(urlPathEqualTo("/sparql"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/sparql-results+json")
                        .withBody(batchPartialJson)));

        Map<String, String> resolved = wikipediaClient.resolveViaWikidataSparql(
                List.of("tt1375666", "tt0133093", "tt0000001"));

        assertThat(resolved).hasSize(2);
        assertThat(resolved).containsEntry("tt1375666", "Inception");
        assertThat(resolved).containsEntry("tt0133093", "The Matrix");
        assertThat(resolved).doesNotContainKey("tt0000001");
    }

    /**
     * A SPARQL response with zero bindings (none of the batch's ids have a P345 match or
     * enwiki sitelink) resolves to an empty map, not an error.
     */
    @Test
    void shouldReturnEmptyMap_whenSparqlBatchHasZeroBindings() throws IOException {
        String batchEmptyJson = loadFixture("fixtures/wikidata-sparql/batch-empty.json");

        wireMock.stubFor(get(urlPathEqualTo("/sparql"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/sparql-results+json")
                        .withBody(batchEmptyJson)));

        Map<String, String> resolved = wikipediaClient.resolveViaWikidataSparql(List.of("tt9999999"));

        assertThat(resolved).isEmpty();
    }

    /**
     * A batch of 51 ids is split into exactly 2 SPARQL requests (chunk size 50) — never 1
     * oversized request and never 51 individual requests.
     */
    @Test
    void shouldChunkRequests_whenMoreThanFiftyImdbIds() throws IOException {
        String batchEmptyJson = loadFixture("fixtures/wikidata-sparql/batch-empty.json");

        wireMock.stubFor(get(urlPathEqualTo("/sparql"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/sparql-results+json")
                        .withBody(batchEmptyJson)));

        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            ids.add("tt" + String.format("%07d", i));
        }

        wikipediaClient.resolveViaWikidataSparql(ids);

        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/sparql")));
    }

    /**
     * D-01/D-02/D-03: when a caller supplies a pre-resolved title map (even empty, meaning
     * "already checked by the batch prefetch"), a miss for this movie's imdbId falls straight
     * through to the candidate cascade without ever issuing a per-movie SPARQL call.
     */
    @Test
    void shouldSkipSparqlCall_whenPreResolvedMapProvidedAndImdbIdAbsent() {
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MISSING_PAGE_RESPONSE)));

        assertThatThrownBy(() -> wikipediaClient.fetch(
                "Inception", "Inception", 2010, "tt1375666", Map.of()))
                .isInstanceOf(WikipediaNotFoundException.class);

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/sparql")));
    }

    /**
     * D-01/D-02/D-03: when the pre-resolved title map contains this movie's imdbId, fetch()
     * uses that title directly — zero SPARQL calls for a movie whose title was already
     * resolved by the caller's batch prefetch.
     */
    @Test
    void shouldUsePreResolvedTitle_whenPresentInMap() throws IOException {
        String sectionsJson = loadFixture("fixtures/wikipedia/inception-sections.json");
        String plotSectionJson = loadFixture("fixtures/wikipedia/inception-plot-section.json");
        String criticsSectionJson = loadFixture("fixtures/wikipedia/inception-critics-section.json");
        String summaryJson = loadFixture("fixtures/wikipedia/inception-plot.json");

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("sections"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sectionsJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(summaryJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(plotSectionJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(criticsSectionJson)));

        WikipediaResult result = wikipediaClient.fetch(
                "Inception", "Inception", 2010, "tt1375666", Map.of("tt1375666", "Inception"));

        assertThat(result).isNotNull();
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/sparql")));
    }

    /**
     * A 429 from /sparql must engage the SAME shared backoff window recordRateLimited()
     * already writes to for en.wikipedia.org 429s — not a separate/unpaced path. Mirrors
     * shouldHonorRetryAfterBackoff_beforeSubsequentRequests exactly, just targeting /sparql
     * via the internal single-ID path (the 4-arg fetch() overload).
     */
    @Test
    void shouldHonorRetryAfterBackoff_onSparqlCall() throws IOException {
        String batchEmptyJson = loadFixture("fixtures/wikidata-sparql/batch-empty.json");

        wireMock.stubFor(get(urlPathEqualTo("/sparql"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "1")
                        .withBody("{\"error\":\"rate limited\"}")));

        wireMock.stubFor(get(urlPathEqualTo("/sparql"))
                .atPriority(10)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/sparql-results+json")
                        .withBody(batchEmptyJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MISSING_PAGE_RESPONSE)));

        Instant start = Instant.now();
        assertThatThrownBy(() -> wikipediaClient.fetch("Inception", "Inception", 2010, "tt1375666"))
                .isInstanceOf(WikipediaNotFoundException.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(950));
    }
}
