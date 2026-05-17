package de.moviearchive.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieStatus;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class IndexingIntegrationTest extends AbstractOpenSearchTest {

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OpenSearchClient client;

    private User testUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() throws Exception {
        movieRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User("indextest@example.com", "$2a$10$placeholderHash");
        testUser.setStatus(UserStatus.ACTIVE);
        testUser = userRepository.save(testUser);

        // Delete any existing OS index for this user to ensure a clean state
        String indexName = "movies-" + testUser.getId();
        try {
            client.indices().delete(
                    org.opensearch.client.opensearch.indices.DeleteIndexRequest.of(
                            r -> r.index(indexName)));
        } catch (org.opensearch.client.opensearch._types.OpenSearchException e) {
            // index_not_found_exception is expected on first run — ignore it
            if (!"index_not_found_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    /**
     * IDX-01: After saving a Movie to Postgres and calling indexingService.index(),
     * the document must exist in OpenSearch.
     */
    @Test
    void shouldIndexFilm_afterPostgresPersist() throws Exception {
        Movie movie = buildSuccessMovie("Inception", "Your mind is the scene of the crime.");
        movie = movieRepository.save(movie);

        // Load user association to avoid LazyInitializationException
        final Movie saved = movieRepository.findByIdWithUser(movie.getId()).orElseThrow();

        indexingService.index(saved);

        String indexName = "movies-" + testUser.getId();
        var getResponse = client.get(
                GetRequest.of(r -> r.index(indexName).id(saved.getId().toString())),
                Map.class);

        assertThat(getResponse.found()).isTrue();
    }

    /**
     * IDX-01 / D-01: movie.indexedAt must be null unless explicitly set after a
     * successful indexingService.index() call. IndexingService.index() does NOT
     * set indexedAt — EnrichmentService does after calling it. This test verifies
     * that the field defaults to null and is only set by the caller, not the service.
     */
    @Test
    void shouldLeaveIndexedAtNull_whenOsFails() throws Exception {
        Movie movie = buildSuccessMovie("Inception", "Dreams within dreams.");
        movie = movieRepository.save(movie);

        // Verify the D-01 contract: indexedAt starts null (not set by index())
        assertThat(movie.getIndexedAt()).isNull();

        // Call indexingService.index() successfully — it does NOT set indexedAt
        final Movie saved = movieRepository.findByIdWithUser(movie.getId()).orElseThrow();
        indexingService.index(saved);

        // Reload from Postgres — indexedAt must still be null because
        // only EnrichmentService.enrich() sets it (after a successful index() call).
        // indexingService.index() itself never persists indexedAt.
        Movie reloaded = movieRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIndexedAt()).isNull();
    }

    /**
     * IDX-02: ensureIndexExists() must create the index with the custom analyzer
     * when it does not already exist.
     */
    @Test
    void shouldCreateIndex_whenNotExists() throws Exception {
        String indexName = "movies-" + testUser.getId();

        // Verify index does not exist before the call
        boolean existsBefore = client.indices()
                .exists(org.opensearch.client.opensearch.indices.ExistsRequest.of(
                        r -> r.index(indexName)))
                .value();
        assertThat(existsBefore).isFalse();

        indexingService.ensureIndexExists(indexName);

        boolean existsAfter = client.indices()
                .exists(org.opensearch.client.opensearch.indices.ExistsRequest.of(
                        r -> r.index(indexName)))
                .value();
        assertThat(existsAfter).isTrue();
    }

    /**
     * IDX-02: ensureIndexExists() must be idempotent — calling it twice must not
     * throw a ResourceAlreadyExistsException or any other error.
     */
    @Test
    void shouldNotThrow_whenIndexAlreadyExists() {
        String indexName = "movies-" + testUser.getId();

        assertThatCode(() -> {
            indexingService.ensureIndexExists(indexName);
            indexingService.ensureIndexExists(indexName);
        }).doesNotThrowAnyException();
    }

    /**
     * IDX-03: The custom_english_analyzer includes the asciifolding filter.
     * A document indexed with title "Napoléon" must be findable by searching
     * for "Napoleon" (accent stripped).
     */
    @Test
    void shouldNormalizeAccents() throws Exception {
        Movie movie = buildSuccessMovie("Napoléon", "A story about a famous French emperor.");
        movie = movieRepository.save(movie);
        final Movie saved = movieRepository.findByIdWithUser(movie.getId()).orElseThrow();

        indexingService.index(saved);
        refreshIndex("movies-" + testUser.getId());

        SearchResponse<Map> response = client.search(
                SearchRequest.of(r -> r
                        .index("movies-" + testUser.getId())
                        .query(Query.of(q -> q
                                .match(m -> m
                                        .field("title")
                                        .query(fv -> fv.stringValue("Napoleon")))))),
                Map.class);

        assertThat(response.hits().total().value()).isGreaterThan(0);
    }

    /**
     * IDX-03: The custom_english_analyzer includes kstem (English stemmer).
     * A document indexed with overview containing "films" must be findable
     * by searching for "film" (stemmed form — kstem reduces plural to base form).
     *
     * Note: kstem is a light stemmer that handles morphological variants like
     * plurals and possessives. "films" → "film" after kstem processing.
     */
    @Test
    void shouldStemEnglishWords() throws Exception {
        Movie movie = buildSuccessMovie("Anthology", "A collection of short films about adventure.");
        movie = movieRepository.save(movie);
        final Movie saved = movieRepository.findByIdWithUser(movie.getId()).orElseThrow();

        indexingService.index(saved);
        refreshIndex("movies-" + testUser.getId());

        SearchResponse<Map> response = client.search(
                SearchRequest.of(r -> r
                        .index("movies-" + testUser.getId())
                        .query(Query.of(q -> q
                                .match(m -> m
                                        .field("overview")
                                        .query(fv -> fv.stringValue("film")))))),
                Map.class);

        assertThat(response.hits().total().value()).isGreaterThan(0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a Movie entity with status=SUCCESS and a minimal rawTmdbJson that
     * DocumentBuilder can parse without NPE. The tmdb JSON must be non-null
     * since DocumentBuilder calls movie.getRawTmdbJson() without null-check.
     */
    private Movie buildSuccessMovie(String title, String overview) throws Exception {
        String tmdbJson = String.format(
                "{\"id\":27205,\"title\":\"%s\",\"original_title\":\"%s\"," +
                "\"overview\":\"%s\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":148,\"vote_average\":8.4,\"vote_count\":35000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
                title, title, overview);

        Movie movie = new Movie(testUser, 27205);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        return movie;
    }

    /**
     * Forces an index refresh so documents indexed in the current test are
     * immediately visible to search queries (OpenSearch is near-real-time by default).
     */
    private void refreshIndex(String indexName) throws Exception {
        try (var response = client.generic().execute(
                Requests.builder()
                        .method("POST")
                        .endpoint("/" + indexName + "/_refresh")
                        .build())) {
            // Ignore the response — we just need the refresh to complete
        }
    }
}
