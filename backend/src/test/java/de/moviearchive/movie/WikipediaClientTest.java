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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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

        assertThatThrownBy(() -> wikipediaClient.fetch("Inception", "Inception", 2010))
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

        WikipediaResult result = wikipediaClient.fetch("Inception", "Inception", 2010);

        assertThat(result).isNotNull();
        assertThat(result.url()).contains("Inception_(2010_film)");
        assertThat(result.summary()).isNotBlank();
        assertThat(result.plot()).isNotBlank();
        assertThat(result.critics()).isNotBlank();
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
        assertThatThrownBy(() -> wikipediaClient.fetch("Inception", "Inception", 2010))
                .isInstanceOf(WikipediaNotFoundException.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(950));
    }
}
