package de.moviearchive.movie;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.auth.RateLimitService;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the movie detail endpoints (Phase 6).
 *
 * GET tests (DETAIL-01, DETAIL-02) are enabled here (plan 06-01).
 * PATCH and DELETE tests remain @Disabled — implemented in plan 06-02.
 */
@AutoConfigureMockMvc
class MovieDetailControllerTest extends AbstractOpenSearchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private OpenSearchClient osClient;

    @Autowired
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userRepository.deleteAll();
        rateLimitService.resetAll();
    }

    /** Creates an ACTIVE user with a bcrypt-hashed password and returns the saved entity. */
    private User createActiveUser(String email) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = new User(email, encoder.encode("Password1!"));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** Logs in as the given email and returns the "Bearer <token>" header value. */
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

    /**
     * Saves a Movie entity directly to Postgres with a rich TMDB fixture that includes
     * all fields the detail endpoint should return.
     */
    private Movie saveMovieForUser(User user, int tmdbId) throws Exception {
        String tmdbJson =
            "{\"id\":" + tmdbId + "," +
            "\"title\":\"Inception\"," +
            "\"original_title\":\"Inception\"," +
            "\"tagline\":\"Your mind is the scene of the crime.\"," +
            "\"overview\":\"A thief who steals corporate secrets through dream-sharing.\"," +
            "\"release_date\":\"2010-07-16\"," +
            "\"runtime\":148," +
            "\"vote_average\":8.4," +
            "\"vote_count\":35000," +
            "\"poster_path\":\"/poster.jpg\"," +
            "\"backdrop_path\":\"/backdrop.jpg\"," +
            "\"genres\":[{\"id\":28,\"name\":\"Action\"},{\"id\":878,\"name\":\"Science Fiction\"}]," +
            "\"production_countries\":[{\"iso_3166_1\":\"US\",\"name\":\"United States\"}]," +
            "\"spoken_languages\":[{\"iso_639_1\":\"en\",\"name\":\"English\"}]," +
            "\"credits\":{" +
            "  \"cast\":[{\"name\":\"Leonardo DiCaprio\",\"character\":\"Cobb\",\"order\":0,\"profile_path\":\"/leo.jpg\"}]," +
            "  \"crew\":[{\"name\":\"Christopher Nolan\",\"job\":\"Director\",\"department\":\"Directing\",\"profile_path\":null}," +
            "           {\"name\":\"Christopher Nolan\",\"job\":\"Screenplay\",\"department\":\"Writing\",\"profile_path\":null}]" +
            "}," +
            "\"videos\":{\"results\":[{\"key\":\"YoHD9XEInc0\",\"type\":\"Trailer\",\"site\":\"YouTube\"}]}}";

        Movie movie = new Movie(user, tmdbId);
        movie.setTitle("Inception");
        movie.setOriginalTitle("Inception");
        movie.setImdbId("tt1375666");
        movie.setReleaseDate(LocalDate.of(2010, 7, 16));
        movie.setRuntime(148);
        movie.setStatus(MovieStatus.SUCCESS);
        movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
        movie.setWikiPlot("Dom Cobb is a skilled thief, the absolute best in the dangerous art of extraction.");
        movie.setWikiCritics("Critics praised the film's ambitious scope and visual effects.");
        return movieRepository.save(movie);
    }

    // -------------------------------------------------------------------------
    // DETAIL-01: GET /movies/{id}
    // -------------------------------------------------------------------------

    @Test
    void getMovieDetail_returnsAllFields() throws Exception {
        User user = createActiveUser("detail@example.com");
        String token = loginAndGetToken("detail@example.com");
        Movie movie = saveMovieForUser(user, 27205);

        mockMvc.perform(get("/movies/" + movie.getId())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(movie.getId().toString()))
                .andExpect(jsonPath("$.tmdbId").value(27205))
                .andExpect(jsonPath("$.imdbId").value("tt1375666"))
                .andExpect(jsonPath("$.title").value("Inception"))
                .andExpect(jsonPath("$.tagline").value("Your mind is the scene of the crime."))
                .andExpect(jsonPath("$.overview").isNotEmpty())
                .andExpect(jsonPath("$.releaseDate").value("2010-07-16"))
                .andExpect(jsonPath("$.year").value(2010))
                .andExpect(jsonPath("$.runtime").value(148))
                .andExpect(jsonPath("$.posterPath").value("/poster.jpg"))
                .andExpect(jsonPath("$.backdropPath").value("/backdrop.jpg"))
                .andExpect(jsonPath("$.voteAverage").value(8.4))
                .andExpect(jsonPath("$.trailerKey").value("YoHD9XEInc0"))
                .andExpect(jsonPath("$.genreList[0]").value("Action"))
                .andExpect(jsonPath("$.directorList[0]").value("Christopher Nolan"))
                .andExpect(jsonPath("$.fullCast[0].name").value("Leonardo DiCaprio"))
                .andExpect(jsonPath("$.fullCast[0].character").value("Cobb"))
                .andExpect(jsonPath("$.imdbLink").value("https://www.imdb.com/title/tt1375666"))
                .andExpect(jsonPath("$.wikipediaPlot").isNotEmpty())
                .andExpect(jsonPath("$.wikipediaCritics").isNotEmpty())
                .andExpect(jsonPath("$.watched").value(false))
                .andExpect(jsonPath("$.personalRating").doesNotExist())
                .andExpect(jsonPath("$.personalNotes").doesNotExist());
    }

    @Test
    void getMovieDetail_omdbFieldsNullWhenNoOmdbData() throws Exception {
        User user = createActiveUser("omdb-null@example.com");
        String token = loginAndGetToken("omdb-null@example.com");
        Movie movie = saveMovieForUser(user, 27206);
        // rawOmdbJson is null (not set in saveMovieForUser)

        mockMvc.perform(get("/movies/" + movie.getId())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imdbRating").doesNotExist())
                .andExpect(jsonPath("$.imdbVotes").doesNotExist())
                .andExpect(jsonPath("$.contentRating").doesNotExist())
                .andExpect(jsonPath("$.boxOffice").doesNotExist())
                .andExpect(jsonPath("$.ratingList").doesNotExist())
                .andExpect(jsonPath("$.mainCast").doesNotExist());
    }

    @Test
    void getMovieDetail_returns404WhenMovieNotOwnedByUser() throws Exception {
        // Create owner user and save a movie
        User owner = createActiveUser("owner@example.com");
        Movie movie = saveMovieForUser(owner, 27207);

        // Create a different user and log in as them
        createActiveUser("other@example.com");
        String otherToken = loginAndGetToken("other@example.com");

        // Other user attempts to access owner's movie — must get 404 (IDOR protection)
        mockMvc.perform(get("/movies/" + movie.getId())
                        .header("Authorization", otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMovieDetail_returns404WhenMovieDoesNotExist() throws Exception {
        createActiveUser("notfound@example.com");
        String token = loginAndGetToken("notfound@example.com");

        mockMvc.perform(get("/movies/" + UUID.randomUUID())
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    // ── OS helpers ────────────────────────────────────────────────────────────

    /** Indexes a movie into OS and marks it as indexed. */
    private Movie indexMovie(Movie movie) throws Exception {
        indexingService.index(movie);
        movie.setIndexedAt(Instant.now());
        return movieRepository.save(movie);
    }

    /** Forces an index refresh so documents are immediately visible. */
    private void refreshIndex(String indexName) throws Exception {
        try (var response = osClient.generic().execute(
                Requests.builder()
                        .method("POST")
                        .endpoint("/" + indexName + "/_refresh")
                        .build())) {
            // ignore body — just need the refresh to complete
        }
    }

    /** Returns the OS document for the given movieId, or throws if not found. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getOsDocument(String indexName, UUID movieId) throws Exception {
        refreshIndex(indexName);
        GetResponse<Map> response = osClient.get(
                GetRequest.of(r -> r.index(indexName).id(movieId.toString())),
                Map.class);
        assertThat(response.found()).isTrue();
        return response.source();
    }

    // -------------------------------------------------------------------------
    // DETAIL-03: PATCH /movies/{id}/personal
    // -------------------------------------------------------------------------

    @Test
    void updatePersonal_updatesWatchedInPostgres() throws Exception {
        User user = createActiveUser("patch-watched@example.com");
        String token = loginAndGetToken("patch-watched@example.com");
        Movie movie = saveMovieForUser(user, 91001);

        mockMvc.perform(patch("/movies/" + movie.getId() + "/personal")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watched\": true}"))
                .andExpect(status().isNoContent());

        Movie updated = movieRepository.findById(movie.getId()).orElseThrow();
        assertThat(updated.getWatched()).isTrue();
    }

    @Test
    void updatePersonal_updatesPersonalRatingInPostgres() throws Exception {
        User user = createActiveUser("patch-rating@example.com");
        String token = loginAndGetToken("patch-rating@example.com");
        Movie movie = saveMovieForUser(user, 91002);

        mockMvc.perform(patch("/movies/" + movie.getId() + "/personal")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personalRating\": 8}"))
                .andExpect(status().isNoContent());

        Movie updated = movieRepository.findById(movie.getId()).orElseThrow();
        assertThat(updated.getPersonalRating()).isEqualTo((short) 8);
    }

    @Test
    void updatePersonal_clearsPersonalRatingWhenNull() throws Exception {
        User user = createActiveUser("patch-clear-rating@example.com");
        String token = loginAndGetToken("patch-clear-rating@example.com");
        Movie movie = saveMovieForUser(user, 91003);
        // First set a rating
        movie.setPersonalRating((short) 8);
        movieRepository.save(movie);

        // Then clear it with null
        mockMvc.perform(patch("/movies/" + movie.getId() + "/personal")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personalRating\": null}"))
                .andExpect(status().isNoContent());

        Movie updated = movieRepository.findById(movie.getId()).orElseThrow();
        assertThat(updated.getPersonalRating()).isNull();
    }

    @Test
    void updatePersonal_updatesPersonalNotesInPostgres() throws Exception {
        User user = createActiveUser("patch-notes@example.com");
        String token = loginAndGetToken("patch-notes@example.com");
        Movie movie = saveMovieForUser(user, 91004);

        mockMvc.perform(patch("/movies/" + movie.getId() + "/personal")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personalNotes\": \"Excellent film\"}"))
                .andExpect(status().isNoContent());

        Movie updated = movieRepository.findById(movie.getId()).orElseThrow();
        assertThat(updated.getPersonalNotes()).isEqualTo("Excellent film");
    }

    @Test
    void updatePersonal_syncsToOpenSearch() throws Exception {
        User user = createActiveUser("patch-os-sync@example.com");
        String token = loginAndGetToken("patch-os-sync@example.com");
        Movie movie = saveMovieForUser(user, 91005);
        // Index the movie so indexedAt != null
        movie = indexMovie(movie);
        String indexName = "movies-" + user.getId();

        mockMvc.perform(patch("/movies/" + movie.getId() + "/personal")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watched\": true}"))
                .andExpect(status().isNoContent());

        // Verify OS document was updated
        Map<String, Object> doc = getOsDocument(indexName, movie.getId());
        assertThat(doc.get("watched")).isEqualTo(true);
    }

    @Test
    void updatePersonal_returns404WhenMovieNotOwnedByUser() throws Exception {
        User owner = createActiveUser("patch-owner@example.com");
        Movie movie = saveMovieForUser(owner, 91006);

        createActiveUser("patch-other@example.com");
        String otherToken = loginAndGetToken("patch-other@example.com");

        mockMvc.perform(patch("/movies/" + movie.getId() + "/personal")
                        .header("Authorization", otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watched\": true}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /movies/{id}
    // -------------------------------------------------------------------------

    @Test
    void deleteMovie_removesFromPostgresAndOpenSearch() throws Exception {
        User user = createActiveUser("delete-movie@example.com");
        String token = loginAndGetToken("delete-movie@example.com");
        Movie movie = saveMovieForUser(user, 92001);
        // Index so the OS document exists
        movie = indexMovie(movie);
        String indexName = "movies-" + user.getId();
        UUID movieId = movie.getId();

        mockMvc.perform(delete("/movies/" + movieId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // Postgres row must be gone
        assertThat(movieRepository.findById(movieId)).isEmpty();

        // OS document must be gone
        refreshIndex(indexName);
        GetResponse<Map> osResponse = osClient.get(
                GetRequest.of(r -> r.index(indexName).id(movieId.toString())),
                Map.class);
        assertThat(osResponse.found()).isFalse();
    }

    @Test
    void deleteMovie_returns404WhenMovieNotOwnedByUser() throws Exception {
        User owner = createActiveUser("delete-owner@example.com");
        Movie movie = saveMovieForUser(owner, 92002);

        createActiveUser("delete-other@example.com");
        String otherToken = loginAndGetToken("delete-other@example.com");

        mockMvc.perform(delete("/movies/" + movie.getId())
                        .header("Authorization", otherToken))
                .andExpect(status().isNotFound());

        // Movie must still exist in Postgres
        assertThat(movieRepository.findById(movie.getId())).isPresent();
    }

    @Test
    void deleteMovie_returns404WhenMovieDoesNotExist() throws Exception {
        createActiveUser("delete-notfound@example.com");
        String token = loginAndGetToken("delete-notfound@example.com");

        mockMvc.perform(delete("/movies/" + UUID.randomUUID())
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
    }
}
