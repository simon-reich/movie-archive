package de.moviearchive.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.auth.RateLimitService;
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
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /dashboard.
 * Uses real OpenSearch (AbstractOpenSearchTest) and real Postgres.
 * Covers DashboardController + DashboardService end-to-end.
 */
@AutoConfigureMockMvc
class DashboardControllerTest extends AbstractOpenSearchTest {

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

    @Autowired
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User testUser;
    private String bearerToken;
    private int tmdbIdSeq = 2000;

    @BeforeEach
    void setUp() throws Exception {
        rateLimitService.resetAll();
        movieRepository.deleteAll();
        userRepository.deleteAll();
        testUser = createActiveUser("dashboard-test@example.com");
        bearerToken = loginAndGetToken("dashboard-test@example.com");
        deleteIndexIfExists("movies-" + testUser.getId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User createActiveUser(String email) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = new User(email, encoder.encode("Password1!"));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

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

    private void refreshIndex(String indexName) throws Exception {
        try (var response = client.generic().execute(
                Requests.builder()
                        .method("POST")
                        .endpoint("/" + indexName + "/_refresh")
                        .build())) {
            // just need the refresh to complete
        }
    }

    private Movie indexMovieWithGenre(String title, String genre) throws Exception {
        int tmdbId = tmdbIdSeq++;
        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"%s\",\"original_title\":\"%s\"," +
                "\"overview\":\"Test.\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
                "\"poster_path\":\"/poster.jpg\",\"backdrop_path\":\"\"," +
                "\"genres\":[{\"id\":28,\"name\":\"%s\"}]," +
                "\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
                tmdbId, title, title, genre);

        Movie movie = new Movie(testUser, tmdbId);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setIndexedAt(Instant.now());
        movie = movieRepository.save(movie);
        indexingService.index(movie);
        return movie;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WithEmptyState_whenArchiveIsEmpty() throws Exception {
        // No movies indexed — DashboardService must handle totalFilms=0 without NPE
        mockMvc.perform(get("/dashboard")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFilms").value(0))
                .andExpect(jsonPath("$.movieOfTheDay").doesNotExist())
                .andExpect(jsonPath("$.recentlyAdded").isArray())
                .andExpect(jsonPath("$.topGenres").isArray())
                .andExpect(jsonPath("$.imdbHistogram").isArray());
    }

    @Test
    void shouldReturn403_whenNotAuthenticated() throws Exception {
        // Spring Security returns 403 (not 401) when no AuthenticationEntryPoint is configured
        // and the request has no credentials — STATELESS session, no form login.
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnTotalFilmsCount_whenMoviesIndexed() throws Exception {
        indexMovieWithGenre("Inception", "Thriller");
        indexMovieWithGenre("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/dashboard")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFilms").value(2));
    }

    @Test
    void shouldReturnTopGenres_whenMoviesIndexed() throws Exception {
        indexMovieWithGenre("Inception", "Thriller");
        indexMovieWithGenre("The Dark Knight", "Thriller");
        indexMovieWithGenre("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/dashboard")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topGenres").isArray())
                .andExpect(jsonPath("$.topGenres[0].name").value("Thriller"))
                .andExpect(jsonPath("$.topGenres[0].count").value(2));
    }

    @Test
    void shouldReturnMovieOfTheDay_whenMoviesIndexed() throws Exception {
        indexMovieWithGenre("Amelie", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/dashboard")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieOfTheDay").exists())
                .andExpect(jsonPath("$.movieOfTheDay.title").value("Amelie"));
    }

    @Test
    void shouldReturnRecentlyAdded_whenMoviesIndexed() throws Exception {
        indexMovieWithGenre("RecentFilm", "Drama");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/dashboard")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentlyAdded").isArray())
                .andExpect(jsonPath("$.recentlyAdded[0].title").value("RecentFilm"));
    }

    @Test
    void shouldReturnImdbHistogram_whenMoviesWithRatingIndexed() throws Exception {
        // Index a movie with an OMDB imdb_rating so the histogram has data
        int tmdbId = tmdbIdSeq++;
        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"RatedFilm\",\"original_title\":\"RatedFilm\"," +
                "\"overview\":\"Test.\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":120,\"vote_average\":8.0,\"vote_count\":1000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}", tmdbId);
        String omdbJson = "{\"imdbRating\":\"8.5\",\"imdbVotes\":\"100,000\",\"Rated\":\"PG-13\"," +
                "\"BoxOffice\":\"N/A\",\"Actors\":\"N/A\",\"Ratings\":[]}";

        Movie movie = new Movie(testUser, tmdbId);
        movie.setTitle("RatedFilm");
        movie.setOriginalTitle("RatedFilm");
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setRawOmdbJson(objectMapper.readTree(omdbJson));
        movie.setIndexedAt(Instant.now());
        movie = movieRepository.save(movie);
        indexingService.index(movie);
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/dashboard")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imdbHistogram").isArray())
                .andExpect(jsonPath("$.totalFilms").value(1));
    }
}
