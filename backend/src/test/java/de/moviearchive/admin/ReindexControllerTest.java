package de.moviearchive.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieStatus;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReindexControllerTest extends AbstractOpenSearchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private OpenSearchClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() throws Exception {
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
    private int tmdbIdSeq = 1000;

    /**
     * Persists a Movie for the given user with rawTmdbJson set (required by DocumentBuilder)
     * and the given indexedAt value (null = pending, non-null = already indexed).
     * Uses a unique tmdbId per call to avoid the (user_id, tmdb_id) unique constraint.
     */
    private Movie persistMovie(User user, String title, Instant indexedAt) throws Exception {
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
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setIndexedAt(indexedAt);
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

    /** Forces an index refresh so documents are immediately visible to search queries. */
    private void refreshIndex(String indexName) throws Exception {
        try (var response = client.generic().execute(
                Requests.builder()
                        .method("POST")
                        .endpoint("/" + indexName + "/_refresh")
                        .build())) {
            // Ignore response body — we just need the refresh to complete
        }
    }

    /** Returns the document count in the given OpenSearch index. */
    private long countDocsInIndex(String indexName) throws Exception {
        refreshIndex(indexName);
        SearchResponse<Map> response = client.search(
                SearchRequest.of(r -> r.index(indexName).query(q -> q.matchAll(m -> m))),
                Map.class);
        return response.hits().total().value();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * IDX-04 / T-04-03-01: POST /admin/reindex/{userId} must return 403 when the
     * JWT subject does not match the path userId (IDOR protection, D-03).
     * Both /{userId} and /{userId}/pending endpoints are covered.
     */
    @Test
    void shouldReturn403_whenUserMismatch() throws Exception {
        User userA = createActiveUser("reindex-a@example.com");
        User userB = createActiveUser("reindex-b@example.com");

        String tokenB = loginAndGetToken("reindex-b@example.com");

        // userB's token used to call userA's reindex endpoint -> 403
        mockMvc.perform(post("/admin/reindex/" + userA.getId())
                        .header("Authorization", tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        // Same for /pending variant
        mockMvc.perform(post("/admin/reindex/" + userA.getId() + "/pending")
                        .header("Authorization", tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));
    }

    /**
     * IDX-04: POST /admin/reindex/{userId} must delete and recreate the index,
     * reindex all movies, return {indexed: N}, and set indexed_at on every movie.
     */
    @Test
    void shouldFullReindex() throws Exception {
        User user = createActiveUser("full-reindex@example.com");
        String token = loginAndGetToken("full-reindex@example.com");
        String indexName = "movies-" + user.getId();

        // Clean up any leftover index from a previous test run
        deleteIndexIfExists(indexName);

        // Persist 2 movies — mix of indexed_at set and null
        Movie m1 = persistMovie(user, "Movie One", Instant.now());
        Movie m2 = persistMovie(user, "Movie Two", null);

        // Full reindex
        mockMvc.perform(post("/admin/reindex/" + user.getId())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(2));

        // Both documents must exist in OpenSearch
        long docCount = countDocsInIndex(indexName);
        assertThat(docCount).isEqualTo(2);

        // Pitfall 5: every movie must have indexed_at set after full reindex
        Movie r1 = movieRepository.findById(m1.getId()).orElseThrow();
        Movie r2 = movieRepository.findById(m2.getId()).orElseThrow();
        assertThat(r1.getIndexedAt()).isNotNull();
        assertThat(r2.getIndexedAt()).isNotNull();
    }

    /**
     * IDX-04: POST /admin/reindex/{userId}/pending must index only movies with
     * indexed_at IS NULL, return {indexed: P}, and leave already-indexed docs untouched.
     */
    @Test
    void shouldIndexOnlyPending() throws Exception {
        User user = createActiveUser("pending-reindex@example.com");
        String token = loginAndGetToken("pending-reindex@example.com");
        String indexName = "movies-" + user.getId();

        // Clean up any leftover index
        deleteIndexIfExists(indexName);

        // Ensure the index exists so we can pre-index the "already indexed" movies
        indexingService.ensureIndexExists(indexName);

        // Create 2 already-indexed movies: persist in Postgres + index in OpenSearch
        Movie alreadyIndexed1 = persistMovie(user, "Already Indexed One", Instant.now());
        Movie alreadyIndexed2 = persistMovie(user, "Already Indexed Two", Instant.now());
        // We need the user association eagerly loaded for indexing
        alreadyIndexed1 = movieRepository.findByIdWithUser(alreadyIndexed1.getId()).orElseThrow();
        alreadyIndexed2 = movieRepository.findByIdWithUser(alreadyIndexed2.getId()).orElseThrow();
        indexingService.index(alreadyIndexed1);
        indexingService.index(alreadyIndexed2);

        // Create 2 pending movies (indexed_at null)
        persistMovie(user, "Pending One", null);
        persistMovie(user, "Pending Two", null);

        // Pending reindex — should only process the 2 pending movies
        mockMvc.perform(post("/admin/reindex/" + user.getId() + "/pending")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(2));

        // All 4 documents must now exist in OpenSearch
        long docCount = countDocsInIndex(indexName);
        assertThat(docCount).isEqualTo(4);

        // The 2 pending movies must now have indexed_at set
        long pendingCount = movieRepository.findByUserIdAndIndexedAtIsNull(user.getId()).size();
        assertThat(pendingCount).isZero();
    }

    /**
     * IDX-04: The response body JSON "indexed" field must equal the number of movies
     * for the user processed during the reindex.
     */
    @Test
    void shouldReturnIndexedCount() throws Exception {
        User user = createActiveUser("count-reindex@example.com");
        String token = loginAndGetToken("count-reindex@example.com");
        String indexName = "movies-" + user.getId();

        deleteIndexIfExists(indexName);

        // Create 3 movies with null indexedAt
        persistMovie(user, "Count Movie One", null);
        persistMovie(user, "Count Movie Two", null);
        persistMovie(user, "Count Movie Three", null);

        String responseBody = mockMvc.perform(post("/admin/reindex/" + user.getId())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int indexed = objectMapper.readTree(responseBody).get("indexed").asInt();
        assertThat(indexed).isEqualTo(3);
    }
}
