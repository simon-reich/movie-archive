package de.moviearchive.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieStatus;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller test for POST /admin/wiki-reload/{userId}.
 * Needs BOTH OpenSearch (via AbstractOpenSearchTest) AND WireMock (Wikipedia stubbing).
 * Java forbids extending two base test classes, so the WireMockExtension registration
 * is duplicated locally here rather than inherited from AbstractWireMockTest.
 */
@AutoConfigureMockMvc
class WikiReloadControllerTest extends AbstractOpenSearchTest {

    @RegisterExtension
    protected static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void overrideWikipediaBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("wikipedia.base-url", wireMock::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private OpenSearchClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Creates an ACTIVE user with BCrypt-encoded password and returns the saved entity. */
    private User createActiveUser(String email) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = new User(email, encoder.encode("Password1!"));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** Logs in and returns the "Bearer <token>" header value. */
    private String loginAndGetToken(String email) throws Exception {
        String response = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(response).get("accessToken").asText();
        return "Bearer " + accessToken;
    }

    /** Counter used to generate unique tmdbIds within a test run to avoid unique-constraint violations. */
    private int tmdbIdSeq = 2000;

    /**
     * Persists a Movie for the given user with rawTmdbJson set (required by DocumentBuilder),
     * status=SUCCESS, and the given wikiUrl (null = missing wiki data, eligible for reload).
     * Uses a unique tmdbId per call to avoid the (user_id, tmdb_id) unique constraint.
     */
    private Movie persistMovie(User user, String title, String wikiUrl) throws Exception {
        int tmdbId = tmdbIdSeq++;
        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"%s\",\"original_title\":\"%s\"," +
                "\"overview\":\"A test movie.\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
                tmdbId, title, title);

        Movie movie = new Movie(user, tmdbId);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setReleaseDate(LocalDate.parse("2010-07-16"));
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setWikiUrl(wikiUrl);
        return movieRepository.save(movie);
    }

    /** Deletes the OpenSearch index for the given userId if it exists; ignores not-found. */
    private void deleteIndexIfExists(String indexName) throws Exception {
        try {
            client.indices().delete(
                    org.opensearch.client.opensearch.indices.DeleteIndexRequest.of(
                            r -> r.index(indexName)));
        } catch (org.opensearch.client.opensearch._types.OpenSearchException e) {
            if (!"index_not_found_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    private String loadFixture(String path) throws IOException {
        return new String(
                getClass().getClassLoader().getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * Polls until the movie's indexedAt is set (batchReload is now fire-and-forget @Async,
     * so the triggering request returns before the batch finishes) or the timeout elapses.
     * Mirrors EnrichmentIntegrationTest.pollForCompletion. Polls on indexedAt rather than
     * wikiLastAttemptedAt — the latter is set by the FIRST of retryWikipedia()'s two save()
     * calls, well before the re-index step's second save() sets indexedAt; polling on the
     * earlier field would let the test assert (and the next test's @BeforeEach cleanDb()
     * delete the row) while the async re-index save is still in flight.
     */
    private Movie pollForWikiAttempt(java.util.UUID movieId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Movie m = movieRepository.findById(movieId).orElseThrow();
            if (m.getIndexedAt() != null) {
                return m;
            }
            Thread.sleep(100);
        }
        return movieRepository.findById(movieId).orElseThrow();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * T-08-01: POST /admin/wiki-reload/{userId} must return 403 when the JWT subject
     * does not match the path userId (IDOR protection).
     */
    @Test
    void shouldReturn403_whenUserMismatch() throws Exception {
        User userA = createActiveUser("wiki-reload-a@example.com");
        User userB = createActiveUser("wiki-reload-b@example.com");

        String tokenB = loginAndGetToken("wiki-reload-b@example.com");

        mockMvc.perform(post("/admin/wiki-reload/" + userA.getId())
                        .header("Authorization", tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));
    }

    /**
     * ENRICH-01/ENRICH-02: A movie missing wiki data gets its Wikipedia data fetched,
     * wikiLastAttemptedAt set, and is re-indexed into OpenSearch (indexedAt set) — D-02.
     */
    @Test
    void shouldRetryWikipediaAndReindex_forMovieMissingWikiData() throws Exception {
        User user = createActiveUser("wiki-reload-happy@example.com");
        String token = loginAndGetToken("wiki-reload-happy@example.com");
        String indexName = "movies-" + user.getId();
        deleteIndexIfExists(indexName);

        Movie movie = persistMovie(user, "Inception", null);

        String sectionsJson = loadFixture("fixtures/wikipedia/inception-sections.json");
        String plotSectionJson = loadFixture("fixtures/wikipedia/inception-plot-section.json");
        String criticsSectionJson = loadFixture("fixtures/wikipedia/inception-critics-section.json");
        String summaryJson = loadFixture("fixtures/wikipedia/inception-plot.json");

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("sections"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sectionsJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(summaryJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(plotSectionJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(criticsSectionJson)));

        mockMvc.perform(post("/admin/wiki-reload/" + user.getId())
                        .header("Authorization", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("started"));

        Movie reloaded = pollForWikiAttempt(movie.getId(), 5000);
        assertThat(reloaded.getWikiUrl()).isNotNull();
        assertThat(reloaded.getWikiLastAttemptedAt()).isNotNull();
        assertThat(reloaded.getIndexedAt()).isNotNull();
    }
}
