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
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Autowired
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User testUser;
    private String bearerToken;

    // ── Setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void cleanDb() throws Exception {
        // Reset rate-limit buckets before each test — 14 tests each log in once,
        // which exceeds the 10-per-minute limit without a reset.
        rateLimitService.resetAll();
        movieRepository.deleteAll();
        userRepository.deleteAll();
        testUser = createActiveUser("search-test@example.com");
        bearerToken = loginAndGetToken("search-test@example.com");
        deleteIndexIfExists("movies-" + testUser.getId());
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
     * Genres are encoded as TMDB genres array.
     */
    private Movie persistMovie(User user, String title, Instant indexedAt,
                               String... genres) throws Exception {
        int tmdbId = tmdbIdSeq++;

        // Build genres JSON array
        StringBuilder genresJson = new StringBuilder("[");
        for (int i = 0; i < genres.length; i++) {
            genresJson.append("{\"id\":").append(100 + i).append(",\"name\":\"").append(genres[i]).append("\"}");
            if (i < genres.length - 1) genresJson.append(",");
        }
        genresJson.append("]");

        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"%s\",\"original_title\":\"%s\"," +
                "\"overview\":\"A test movie.\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":%s,\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
                tmdbId, title, title, genresJson);

        Movie movie = new Movie(user, tmdbId);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setIndexedAt(indexedAt);
        return movieRepository.save(movie);
    }

    /**
     * Convenience: persist a movie with genres and index it into OpenSearch.
     * MANDATORY: call refreshIndex after all indexTestMovie calls before asserting
     * (OpenSearch is near-real-time — 05-RESEARCH.md Pitfall 1).
     */
    private Movie indexTestMovie(String title, String... genres) throws Exception {
        Movie movie = persistMovie(testUser, title, Instant.now(), genres);
        indexingService.index(movie);
        return movie;
    }

    /**
     * Persist a movie with a custom rawTmdbJson (for director/crew tests).
     * Indexes the movie into OpenSearch.
     */
    private Movie indexTestMovieWithCrew(String title, String directorName,
                                         String... genres) throws Exception {
        int tmdbId = tmdbIdSeq++;

        StringBuilder genresJson = new StringBuilder("[");
        for (int i = 0; i < genres.length; i++) {
            genresJson.append("{\"id\":").append(100 + i).append(",\"name\":\"").append(genres[i]).append("\"}");
            if (i < genres.length - 1) genresJson.append(",");
        }
        genresJson.append("]");

        String crewJson = "[{\"name\":\"" + directorName + "\",\"job\":\"Director\","
                + "\"department\":\"Directing\",\"profile_path\":null}]";

        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"%s\",\"original_title\":\"%s\"," +
                "\"overview\":\"A test movie.\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":%s,\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":%s}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
                tmdbId, title, title, genresJson, crewJson);

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

    /**
     * Persist a movie with a specific release year.
     */
    private Movie indexTestMovieWithYear(String title, int year) throws Exception {
        int tmdbId = tmdbIdSeq++;
        String releaseDate = year + "-06-15";
        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"%s\",\"original_title\":\"%s\"," +
                "\"overview\":\"A test movie.\",\"tagline\":\"\",\"release_date\":\"%s\"," +
                "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
                tmdbId, title, title, releaseDate);

        Movie movie = new Movie(testUser, tmdbId);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        // DocumentBuilder derives year from movie.getReleaseDate() — must be set on entity
        movie.setReleaseDate(LocalDate.of(year, 6, 15));
        movie.setIndexedAt(Instant.now());
        movie = movieRepository.save(movie);
        indexingService.index(movie);
        return movie;
    }

    /**
     * Persist and index a movie with OMDB-sourced imdb_rating.
     */
    private Movie indexTestMovieWithImdbRating(String title, double imdbRating) throws Exception {
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
        // Use Locale.US to ensure decimal point (not comma) — default JVM locale may be de_DE
        String omdbJson = String.format(java.util.Locale.US,
                "{\"imdbRating\":\"%.1f\",\"imdbVotes\":\"100,000\",\"Rated\":\"PG-13\"," +
                "\"BoxOffice\":\"N/A\",\"Actors\":\"N/A\",\"Ratings\":[]}",
                imdbRating);

        Movie movie = new Movie(testUser, tmdbId);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setRawOmdbJson(objectMapper.readTree(omdbJson));
        movie.setIndexedAt(Instant.now());
        movie = movieRepository.save(movie);
        indexingService.index(movie);
        return movie;
    }

    /** Deletes the OpenSearch index for the given name if it exists; ignores not-found. */
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

    /**
     * Forces an index refresh so documents are immediately visible to search queries.
     * MANDATORY before every search assertion — OpenSearch is near-real-time (Pitfall 1).
     */
    private void refreshIndex(String indexName) throws Exception {
        try (var response = client.generic().execute(
                Requests.builder()
                        .method("POST")
                        .endpoint("/" + indexName + "/_refresh")
                        .build())) {
            // Ignore response body — we just need the refresh to complete
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnAllFilms_whenQueryIsEmpty() throws Exception {
        indexTestMovie("Zorro", "Action");
        indexTestMovie("Amelie", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"sort\":\"title_asc\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("Amelie"))
                .andExpect(jsonPath("$.results[1].title").value("Zorro"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void shouldFindFilmByTitle() throws Exception {
        indexTestMovie("Inception", "Thriller");
        indexTestMovie("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Inception\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Inception"));
    }

    @Test
    void shouldFindFilmByOverview() throws Exception {
        int tmdbId = tmdbIdSeq++;
        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"TestFilm\",\"original_title\":\"TestFilm\"," +
                "\"overview\":\"A story about a submarine adventure underwater\"," +
                "\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
                "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}", tmdbId);

        Movie movie = new Movie(testUser, tmdbId);
        movie.setTitle("TestFilm");
        movie.setOriginalTitle("TestFilm");
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setIndexedAt(Instant.now());
        movie = movieRepository.save(movie);
        indexingService.index(movie);
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"submarine\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("TestFilm"));
    }

    @Test
    void shouldNormalizeAccentsInSearch() throws Exception {
        // custom_english_analyzer applies asciifolding: "Amelie" matches "Amélie"
        int tmdbId = tmdbIdSeq++;
        String tmdbJson = String.format(
                "{\"id\":%d,\"title\":\"Am\\u00e9lie\",\"original_title\":\"Am\\u00e9lie\"," +
                "\"overview\":\"A test.\",\"tagline\":\"\",\"release_date\":\"2001-05-16\"," +
                "\"runtime\":122,\"vote_average\":8.3,\"vote_count\":500000," +
                "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
                "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
                "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
                "\"credits\":{\"cast\":[],\"crew\":[]}," +
                "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}", tmdbId);
        Movie movie = new Movie(testUser, tmdbId);
        movie.setTitle("Amélie");
        movie.setOriginalTitle("Amélie");
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setIndexedAt(Instant.now());
        movie = movieRepository.save(movie);
        indexingService.index(movie);
        refreshIndex("movies-" + testUser.getId());

        // Query without accent should still match — asciifolding strips the accent at index and query time
        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Amelie\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Amélie"));
    }

    @Test
    void shouldFilterBySingleGenre() throws Exception {
        indexTestMovie("Inception", "Thriller");
        indexTestMovie("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"genres\":[\"Thriller\"]},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Inception"));
    }

    @Test
    void shouldFilterByMultipleGenresOR() throws Exception {
        // Genre filter: OR within group (D-11)
        indexTestMovie("Inception", "Thriller");
        indexTestMovie("Avatar", "Sci-Fi");
        indexTestMovie("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"genres\":[\"Thriller\",\"Sci-Fi\"]},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void shouldFilterByDirector() throws Exception {
        indexTestMovieWithCrew("Memento", "Christopher Nolan");
        indexTestMovieWithCrew("Titanic", "James Cameron");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"director\":\"Nolan\"},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Memento"));
    }

    @Test
    void shouldFilterByYearRange() throws Exception {
        indexTestMovieWithYear("OldFilm", 1995);
        indexTestMovieWithYear("NinetyNine", 1999);
        indexTestMovieWithYear("NewFilm", 2010);
        refreshIndex("movies-" + testUser.getId());

        // Only films in [1998..2000] should match
        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"yearFrom\":1998,\"yearTo\":2000},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("NinetyNine"));
    }

    @Test
    void shouldFilterByImdbRating() throws Exception {
        indexTestMovieWithImdbRating("LowRated", 5.0);
        indexTestMovieWithImdbRating("HighRated", 8.5);
        refreshIndex("movies-" + testUser.getId());

        // Only films with imdb_rating >= 8.0 should match
        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"imdbRatingFrom\":8.0},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("HighRated"));
    }

    @Test
    void shouldCombineGenreAndDirectorFilters() throws Exception {
        // Genre AND director combined — AND logic across filter groups (D-11)
        indexTestMovieWithCrew("Inception", "Christopher Nolan", "Thriller");
        indexTestMovieWithCrew("Titanic", "James Cameron", "Thriller");
        indexTestMovieWithCrew("Memento", "Christopher Nolan");  // no Thriller genre
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"genres\":[\"Thriller\"],\"director\":\"Nolan\"},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Inception"));
    }

    @Test
    void shouldReturnEmpty_whenWatchedFilterApplied() throws Exception {
        // Phase 6 (Plan 06-01): DocumentBuilder now indexes watched=false (not null).
        // term(watched=false) matches documents with watched=false — returns 1 result.
        // A newly saved movie has watched=false (D-09 default), so notYetWatched filter finds it.
        indexTestMovie("SomeFilm");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"notYetWatched\":true},\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void shouldSortByTitleAscending() throws Exception {
        indexTestMovie("Zorro");
        indexTestMovie("Amelie");
        indexTestMovie("Batman");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"sort\":\"title_asc\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("Amelie"))
                .andExpect(jsonPath("$.results[1].title").value("Batman"))
                .andExpect(jsonPath("$.results[2].title").value("Zorro"));
    }

    @Test
    void shouldSortByImdbRatingDescending() throws Exception {
        indexTestMovieWithImdbRating("LowRated", 4.0);
        indexTestMovieWithImdbRating("HighRated", 9.0);
        indexTestMovieWithImdbRating("MidRated", 6.5);
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"sort\":\"imdb_desc\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("HighRated"))
                .andExpect(jsonPath("$.results[1].title").value("MidRated"))
                .andExpect(jsonPath("$.results[2].title").value("LowRated"));
    }

    @Test
    void shouldSortByPersonalRating_nullsLast() throws Exception {
        // personal_rating is null for all Phase 4/5 docs — sort=rating_desc must not crash.
        // missing=_last ensures null docs still appear (just at end) rather than throwing.
        indexTestMovie("Film1");
        indexTestMovie("Film2");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"sort\":\"rating_desc\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));  // both appear, nulls last (not missing)
    }

    @Test
    void shouldRejectSearchRequest_whenPageExceedsMaxDepth() throws Exception {
        // page=501 → from = 501 * 20 = 10020 > 10000 cap → 400
        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"page\":501}"))
                .andExpect(status().isBadRequest());
    }

    // ── Facets tests ──────────────────────────────────────────────────────────

    @Test
    void shouldReturnFacets_whenMoviesIndexed() throws Exception {
        indexTestMovie("Inception", "Thriller");
        indexTestMovie("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/search/facets")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres").isArray())
                .andExpect(jsonPath("$.contentRatings").isArray())
                .andExpect(jsonPath("$.languages").isArray())
                .andExpect(jsonPath("$.countries").isArray());
    }

    @Test
    void shouldReturnFacetsContainingIndexedGenres() throws Exception {
        indexTestMovie("Inception", "Thriller");
        indexTestMovie("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/search/facets")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres[?(@ == 'Thriller')]").exists())
                .andExpect(jsonPath("$.genres[?(@ == 'Romance')]").exists());
    }

    @Test
    void shouldReturn403ForFacets_whenNotAuthenticated() throws Exception {
        // Spring Security returns 403 (not 401) when no AuthenticationEntryPoint is configured
        mockMvc.perform(get("/search/facets"))
                .andExpect(status().isForbidden());
    }

    // ── Autocomplete tests ────────────────────────────────────────────────────

    @Test
    void shouldReturnDirectorSuggestions_forAutocomplete() throws Exception {
        indexTestMovieWithCrew("Memento", "Christopher Nolan");
        indexTestMovieWithCrew("Inception", "Christopher Nolan");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(get("/search/autocomplete")
                        .header("Authorization", bearerToken)
                        .param("field", "director")
                        .param("prefix", "Chr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions[0]").value("Christopher Nolan"));
    }

    @Test
    void shouldReturn400_whenAutocompleteFieldIsInvalid() throws Exception {
        mockMvc.perform(get("/search/autocomplete")
                        .header("Authorization", bearerToken)
                        .param("field", "invalid_field")
                        .param("prefix", "test"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn403ForAutocomplete_whenNotAuthenticated() throws Exception {
        // Spring Security returns 403 (not 401) when no AuthenticationEntryPoint is configured
        mockMvc.perform(get("/search/autocomplete")
                        .param("field", "director")
                        .param("prefix", "Chr"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldSortByYearDescending() throws Exception {
        indexTestMovieWithYear("OldFilm", 1990);
        indexTestMovieWithYear("NewFilm", 2020);
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"sort\":\"year_desc\",\"page\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("NewFilm"))
                .andExpect(jsonPath("$.results[1].title").value("OldFilm"));
    }
}
