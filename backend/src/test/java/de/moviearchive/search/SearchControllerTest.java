package de.moviearchive.search;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SearchControllerTest extends AbstractOpenSearchTest {

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

    // ── Setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void cleanDb() throws Exception {
        movieRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    // ── Disabled stubs — Wave 1: SearchController not yet implemented ─────────

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldReturnAllFilms_whenQueryIsEmpty() throws Exception {
        // Wave 1 implements: empty query returns all films in the user's index, sorted by default
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFindFilmByTitle() throws Exception {
        // Wave 1 implements: full-text query matches title field via custom_english_analyzer
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFindFilmByOverview() throws Exception {
        // Wave 1 implements: full-text query matches overview field via custom_english_analyzer
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldNormalizeAccentsInSearch() throws Exception {
        // Wave 1 implements: accent-folded query (e.g. "Amelie" matches "Amélie") via asciifolding filter
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFilterBySingleGenre() throws Exception {
        // Wave 1 implements: genre filter returns only films with matching genre
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFilterByMultipleGenresOR() throws Exception {
        // Wave 1 implements: multiple genre values in filter are combined with OR logic
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFilterByDirector() throws Exception {
        // Wave 1 implements: director filter returns only films with matching director name
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFilterByYearRange() throws Exception {
        // Wave 1 implements: yearFrom/yearTo filter restricts results to that year range
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldFilterByImdbRating() throws Exception {
        // Wave 1 implements: imdbRatingMin filter returns only films at or above the threshold
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldCombineGenreAndDirectorFilters() throws Exception {
        // Wave 1 implements: genre AND director filters combined with AND logic across filter groups
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldReturnEmpty_whenWatchedFilterApplied() throws Exception {
        // Wave 1 implements: watched=false filter returns empty until personal fields are added in Phase 6
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldSortByTitleAscending() throws Exception {
        // Wave 1 implements: sort=title_asc returns results ordered by title.keyword A-Z
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldSortByImdbRatingDescending() throws Exception {
        // Wave 1 implements: sort=imdb_rating_desc returns results ordered by imdb_rating descending
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    @Test
    @Disabled("Wave 1: SearchController not yet implemented")
    void shouldSortByPersonalRating_nullsLast() throws Exception {
        // Wave 1 implements: sort=personal_rating_desc orders films by personal rating with nulls last
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
