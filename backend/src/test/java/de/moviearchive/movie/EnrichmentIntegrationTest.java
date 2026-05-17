package de.moviearchive.movie;

import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.settings.ApiKeyProvider;
import de.moviearchive.settings.EncryptionService;
import de.moviearchive.settings.UserApiKey;
import de.moviearchive.settings.UserApiKeyRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentIntegrationTest extends AbstractWireMockTest {

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("wikipedia.base-url", wireMock::baseUrl);
    }

    @Autowired
    private EnrichmentService enrichmentService;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserApiKeyRepository userApiKeyRepository;

    @Autowired
    private EncryptionService encryptionService;

    private User testUser;
    private static final String MISSING_PAGE_RESPONSE =
            "{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\"}}";

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userApiKeyRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User("enrichtest@example.com", "$2a$10$placeholderHash");
        testUser.setStatus(UserStatus.ACTIVE);
        testUser = userRepository.save(testUser);

        // Store encrypted TMDB key
        String encryptedTmdbKey = encryptionService.encrypt("fake-tmdb-key");
        userApiKeyRepository.save(new UserApiKey(testUser, ApiKeyProvider.TMDB, encryptedTmdbKey));

        // Stub all Wikipedia calls to return missing-page (skip wiki)
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MISSING_PAGE_RESPONSE)));
    }

    private String loadFixture(String path) throws IOException {
        return new String(
                getClass().getClassLoader().getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private UUID createPendingMovie() {
        Movie movie = new Movie(testUser, 27205);
        return movieRepository.save(movie).getId();
    }

    private Movie pollForCompletion(UUID movieId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Movie m = movieRepository.findById(movieId).orElseThrow();
            if (m.getStatus() != MovieStatus.PENDING) {
                return m;
            }
            Thread.sleep(100);
        }
        return movieRepository.findById(movieId).orElseThrow();
    }

    @Test
    void shouldPersistTmdbData_afterEnrichment() throws Exception {
        String tmdbDetailJson = loadFixture("fixtures/tmdb/inception-detail.json");

        wireMock.stubFor(get(urlPathEqualTo("/3/movie/27205"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tmdbDetailJson)));

        UUID movieId = createPendingMovie();
        enrichmentService.enrich(movieId);

        Movie enriched = pollForCompletion(movieId, 5000);

        assertThat(enriched.getTitle()).isEqualTo("Inception");
        assertThat(enriched.getRawTmdbJson()).isNotNull();
        assertThat(enriched.getReleaseDate()).isNotNull();
        assertThat(enriched.getRuntime()).isEqualTo(148);
        assertThat(enriched.getImdbId()).isEqualTo("tt1375666");
    }

    @Test
    void shouldSaveWithSuccess_whenWikipediaFails() throws Exception {
        String tmdbDetailJson = loadFixture("fixtures/tmdb/inception-detail.json");

        wireMock.stubFor(get(urlPathEqualTo("/3/movie/27205"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tmdbDetailJson)));

        // Wikipedia stubs already return error (missing page) — set up in @BeforeEach

        UUID movieId = createPendingMovie();
        enrichmentService.enrich(movieId);

        Movie enriched = pollForCompletion(movieId, 5000);

        assertThat(enriched.getStatus()).isEqualTo(MovieStatus.SUCCESS);
        assertThat(enriched.getWikiUrl()).isNull();
        assertThat(enriched.getWikiPlot()).isNull();
    }

    @Test
    void shouldTransitionToSuccess_afterEnrichment() throws Exception {
        String tmdbDetailJson = loadFixture("fixtures/tmdb/inception-detail.json");

        wireMock.stubFor(get(urlPathEqualTo("/3/movie/27205"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tmdbDetailJson)));

        UUID movieId = createPendingMovie();

        // Verify initial status is PENDING
        Movie before = movieRepository.findById(movieId).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(MovieStatus.PENDING);

        enrichmentService.enrich(movieId);

        Movie enriched = pollForCompletion(movieId, 5000);
        assertThat(enriched.getStatus()).isEqualTo(MovieStatus.SUCCESS);
    }
}
