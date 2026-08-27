package de.moviearchive.movie;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for ENRICH-02's cooldown-window eligibility contract and
 * ENRICH-03's pacing contract — both are timing/state-based behaviors that only a real
 * Postgres + WireMock + the actual wikiReloadExecutor bean can prove, not a Mockito unit
 * test. Needs BOTH OpenSearch (via AbstractOpenSearchTest) AND WireMock (Wikipedia
 * stubbing); WireMockExtension registration is duplicated locally (same pattern as
 * WikiReloadControllerTest) since Java forbids extending two base test classes.
 *
 * The whole class overrides wiki.retry.pacing-delay-ms (the global test-suite default from
 * application-test.properties is 1ms, too fast to observe timing) — RESEARCH.md Pitfall 5.
 */
class WikiReloadServiceIntegrationTest extends AbstractOpenSearchTest {

    @RegisterExtension
    protected static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void overrideWikipediaBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("wikipedia.base-url", wireMock::baseUrl);
        // Defensive: today's movies in this test class never have imdbId set, so
        // resolveViaWikidataSparql()'s empty-list guard means zero network calls fire
        // regardless — but registering this override keeps this class safe against reaching
        // the real query.wikidata.org endpoint if a future test adds a movie with imdbId set.
        registry.add("wikidata.sparql-base-url", wireMock::baseUrl);
    }

    // 2500ms, not the 500ms originally sketched — this Testcontainers environment's real
    // per-movie floor (WireMock round trips + Postgres saves + OpenSearch index-with-refresh)
    // was empirically observed at 988-1614ms with NO pacing involved at all. A pacing delay
    // close to that floor makes "did a sleep happen" indistinguishable from ordinary
    // infrastructure jitter. 2500ms gives a clear, non-flaky margin above the observed floor
    // for the boundary assertions below.
    @DynamicPropertySource
    static void overridePacingDelay(DynamicPropertyRegistry registry) {
        registry.add("wiki.retry.pacing-delay-ms", () -> "2500");
    }

    @Autowired
    private WikiReloadService wikiReloadService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private OpenSearchClient client;

    @Autowired
    private IndexingService indexingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * Cleans up after each test too, not just before — @BeforeEach alone only guarantees a
     * clean slate for the NEXT run of THIS class. Other test classes sharing the same
     * singleton Postgres container (e.g. AuthControllerTest, which only deletes users, not
     * movies, in its own @BeforeEach) can run immediately after this class in the same JVM;
     * leaving no residue here prevents this class's users/movies from tripping an unrelated
     * class's FK-constrained deleteAll(), regardless of JUnit's test class execution order.
     */
    @AfterEach
    void cleanDbAfter() {
        movieRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User createUser(String email) {
        User user = new User(email, "hash");
        return userRepository.save(user);
    }

    private int tmdbIdSeq = 3000;

    /**
     * Persists a Movie titled "Inception" (matches the shared WireMock stub) for the given
     * user, status=SUCCESS, wikiUrl=null (missing wiki data), with the given
     * wikiLastAttemptedAt (null = never attempted). Uses a unique tmdbId per call to avoid
     * the (user_id, tmdb_id) unique constraint.
     */
    private Movie persistEligibleMovie(User user, Instant wikiLastAttemptedAt) throws Exception {
        int tmdbId = tmdbIdSeq++;
        String title = "Inception";
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
        movie.setWikiUrl(null);
        movie.setWikiLastAttemptedAt(wikiLastAttemptedAt);
        return movieRepository.save(movie);
    }

    /**
     * Regression helper for the "has a Wikipedia page but no content" state: wikiUrl IS
     * set (the original save-flow lookup resolved a page) but wikiPlot/wikiCritics are
     * both null (the page had no matching Plot/Critical-response section). Never attempted.
     */
    private Movie persistMovieWithUrlButNoContent(User user) throws Exception {
        Movie movie = persistEligibleMovie(user, null);
        movie.setWikiUrl("https://en.wikipedia.org/wiki/Some_Existing_Page");
        return movieRepository.save(movie);
    }

    /** Stubs WireMock to return a found Wikipedia page for the "Inception" title/year. */
    private void stubWikipediaFound() throws IOException {
        String sectionsJson = loadFixture("fixtures/wikipedia/inception-sections.json");
        String plotSectionJson = loadFixture("fixtures/wikipedia/inception-plot-section.json");
        String criticsSectionJson = loadFixture("fixtures/wikipedia/inception-critics-section.json");
        String summaryJson = loadFixture("fixtures/wikipedia/inception-plot.json");

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("sections"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(sectionsJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("0"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(summaryJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(plotSectionJson)));

        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", containing("parse"))
                .withQueryParam("page", containing("Inception_(2010_film)"))
                .withQueryParam("prop", containing("wikitext"))
                .withQueryParam("section", containing("7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(criticsSectionJson)));
    }

    /** Deletes the OpenSearch index for the given userId if it exists; ignores not-found. */
    private void deleteIndexIfExists(String indexName) throws Exception {
        try {
            client.indices().delete(
                    org.opensearch.client.opensearch.indices.DeleteIndexRequest.of(r -> r.index(indexName)));
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

    /** Polls until every given movie has a non-null indexedAt (full pipeline done), or times out. */
    private void pollUntilIndexed(long timeoutMs, UUID... movieIds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allIndexed = true;
            for (UUID id : movieIds) {
                Movie m = movieRepository.findById(id).orElseThrow();
                if (m.getIndexedAt() == null) {
                    allIndexed = false;
                    break;
                }
            }
            if (allIndexed) {
                return;
            }
            Thread.sleep(50);
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * ENRICH-02: a film last attempted inside the 30-day cooldown window is skipped; a
     * never-attempted film and one attempted more than 30 days ago are both retried.
     */
    @Test
    void shouldRespectCooldownWindow_excludingRecentAttempts() throws Exception {
        User user = createUser("wiki-cooldown@example.com");
        String indexName = "movies-" + user.getId();
        deleteIndexIfExists(indexName);
        stubWikipediaFound();

        Movie neverAttempted = persistEligibleMovie(user, null);
        Instant withinCooldownAttempt = Instant.now().minus(29, ChronoUnit.DAYS);
        Movie withinCooldown = persistEligibleMovie(user, withinCooldownAttempt);
        Movie outsideCooldown = persistEligibleMovie(user, Instant.now().minus(31, ChronoUnit.DAYS));

        // Capture the DB round-tripped value (not the in-memory Instant.now() with full
        // nanosecond precision) so the "unchanged" comparison below isn't a precision mismatch.
        Instant withinCooldownOriginalAttempt =
                movieRepository.findById(withinCooldown.getId()).orElseThrow().getWikiLastAttemptedAt();

        wikiReloadService.batchReload(user.getId());

        // Poll on indexedAt (full pipeline completion), not just wikiUrl — wikiUrl is set
        // by retryWikipedia()'s FIRST save(), well before the re-index step's second save()
        // sets indexedAt. Returning as soon as wikiUrl is set would let this test finish
        // while the async re-index save is still in flight, and the next test's @BeforeEach
        // cleanDb() could delete the row mid-write.
        pollUntilIndexed(5000, neverAttempted.getId(), outsideCooldown.getId());

        Movie neverAttemptedReloaded = movieRepository.findById(neverAttempted.getId()).orElseThrow();
        Movie outsideCooldownReloaded = movieRepository.findById(outsideCooldown.getId()).orElseThrow();
        Movie withinCooldownReloaded = movieRepository.findById(withinCooldown.getId()).orElseThrow();

        assertThat(neverAttemptedReloaded.getWikiUrl()).isNotNull();
        assertThat(outsideCooldownReloaded.getWikiUrl()).isNotNull();

        // The 29-day movie was skipped entirely — its attempt timestamp is untouched.
        assertThat(withinCooldownReloaded.getWikiLastAttemptedAt()).isEqualTo(withinCooldownOriginalAttempt);
        assertThat(withinCooldownReloaded.getWikiUrl()).isNull();
    }

    /**
     * Regression test: a movie with wikiUrl already set (a page was resolved during the
     * original save) but wikiPlot/wikiCritics both still null (no matching content
     * section on that page) MUST be picked up by batch-reload. Eligibility was previously
     * keyed on "wikiUrl IS NULL" alone, which permanently excluded this exact state —
     * the movie could never be retried by batch-reload no matter how many times it ran,
     * even though the movie detail page's own v-if="wikipediaPlot || wikipediaCritics"
     * treats it identically to a fully-missing film.
     */
    @Test
    void shouldRetryMovies_withUrlSetButNoContent() throws Exception {
        User user = createUser("wiki-url-no-content@example.com");
        String indexName = "movies-" + user.getId();
        deleteIndexIfExists(indexName);
        stubWikipediaFound();

        Movie trapped = persistMovieWithUrlButNoContent(user);
        assertThat(trapped.getWikiUrl()).isNotNull();
        assertThat(trapped.getWikiPlot()).isNull();
        assertThat(trapped.getWikiCritics()).isNull();

        wikiReloadService.batchReload(user.getId());

        pollUntilIndexed(5000, trapped.getId());

        Movie reloaded = movieRepository.findById(trapped.getId()).orElseThrow();
        assertThat(reloaded.getWikiLastAttemptedAt()).isNotNull();
        assertThat(reloaded.getWikiPlot()).isNotNull();
    }

    /**
     * ENRICH-03: a 3-movie batch (2 sleeps x 500ms pacing) takes at least 1000ms wall-clock,
     * proving pacing occurred between consecutive Wikipedia calls.
     */
    @Test
    void shouldPaceRequestsBetweenEligibleMovies() throws Exception {
        User user = createUser("wiki-pacing@example.com");
        String indexName = "movies-" + user.getId();
        deleteIndexIfExists(indexName);
        indexingService.ensureIndexExists(indexName);
        stubWikipediaFound();

        Movie m1 = persistEligibleMovie(user, null);
        Movie m2 = persistEligibleMovie(user, null);
        Movie m3 = persistEligibleMovie(user, null);

        Instant start = Instant.now();
        wikiReloadService.batchReload(user.getId());
        pollUntilIndexed(20000, m1.getId(), m2.getId(), m3.getId());
        Instant end = Instant.now();

        // 2 sleeps x 2500ms pacing-delay = 5000ms mandatory floor from sleeps ALONE, on top
        // of real per-movie processing time. The unpaced 3-movie ceiling observed in this
        // environment tops out well under 5000ms (988-1614ms per movie), so 5000ms cleanly
        // separates "pacing occurred" from "just slow infrastructure".
        assertThat(Duration.between(start, end).toMillis()).isGreaterThanOrEqualTo(5000);
    }

    /**
     * ENRICH-03 boundary: a single eligible movie triggers zero pacing sleeps — the batch
     * completes well under one pacing-delay interval (2500ms).
     *
     * OpenSearch index CREATION (first write for a brand-new per-user index) has its own
     * one-time real-infrastructure latency (~1s in this Testcontainers environment) that
     * would otherwise dominate the timed window and make the assertion meaningless — it
     * has nothing to do with pacing. ensureIndexExists() pays that cost up front, outside
     * the timer, so the timed window isolates the actual "did a sleep happen" question.
     */
    @Test
    void shouldNotPace_whenOnlyOneMovieEligible() throws Exception {
        User user = createUser("wiki-pacing-single@example.com");
        String indexName = "movies-" + user.getId();
        deleteIndexIfExists(indexName);
        indexingService.ensureIndexExists(indexName);
        stubWikipediaFound();

        Movie only = persistEligibleMovie(user, null);

        Instant start = Instant.now();
        wikiReloadService.batchReload(user.getId());
        pollUntilIndexed(5000, only.getId());
        Instant end = Instant.now();

        assertThat(Duration.between(start, end).toMillis()).isLessThan(2500);
    }
}
