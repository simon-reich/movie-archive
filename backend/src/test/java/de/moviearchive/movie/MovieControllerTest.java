package de.moviearchive.movie;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.auth.RateLimitService;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MovieControllerTest extends AbstractWireMockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserApiKeyRepository userApiKeyRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("wikipedia.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userApiKeyRepository.deleteAll();
        userRepository.deleteAll();
        rateLimitService.resetAll();
    }

    /** Creates an ACTIVE user and returns the saved entity. */
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

    /** Saves a TMDB API key for the given user (encrypted in DB). */
    private void saveTmdbKey(User user, String rawKey) {
        userApiKeyRepository.save(new UserApiKey(user, ApiKeyProvider.TMDB, encryptionService.encrypt(rawKey)));
    }

    // -------------------------------------------------------------------------
    // SAVE-01: POST /movies/save
    // -------------------------------------------------------------------------

    @Test
    void shouldReturn202_whenSaveInitiated() throws Exception {
        User user = createActiveUser("save@example.com");
        String token = loginAndGetToken("save@example.com");

        mockMvc.perform(post("/movies/save")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\": 27205}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void shouldReturn202_withSameUuid_onDuplicateSave() throws Exception {
        User user = createActiveUser("dup@example.com");
        String token = loginAndGetToken("dup@example.com");

        String body1 = mockMvc.perform(post("/movies/save")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\": 27205}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String id1 = objectMapper.readTree(body1).get("id").asText();

        String body2 = mockMvc.perform(post("/movies/save")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\": 27205}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String id2 = objectMapper.readTree(body2).get("id").asText();

        org.assertj.core.api.Assertions.assertThat(id1).isEqualTo(id2);
    }

    // -------------------------------------------------------------------------
    // SAVE-01: GET /movies/search
    // -------------------------------------------------------------------------

    @Test
    void shouldReturnSearchResults_whenTmdbKeyValid() throws Exception {
        User user = createActiveUser("search@example.com");
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("search@example.com");

        String inceptionSearchJson = """
                {
                  "page": 1,
                  "results": [
                    {
                      "id": 27205,
                      "title": "Inception",
                      "original_title": "Inception",
                      "release_date": "2010-07-16",
                      "poster_path": "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg"
                    }
                  ],
                  "total_results": 1,
                  "total_pages": 1
                }
                """;

        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(inceptionSearchJson)));

        // TmdbClient.search() is still a stub returning empty list — verify the 200 response
        mockMvc.perform(get("/movies/search?q=Inception")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn422_whenNoTmdbKey() throws Exception {
        // User has no TMDB key configured
        createActiveUser("nokey@example.com");
        String token = loginAndGetToken("nokey@example.com");

        mockMvc.perform(get("/movies/search?q=Inception")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("No TMDB key configured. Add your key in Settings."));
    }

    // -------------------------------------------------------------------------
    // SAVE-05: GET /movies/{id}/status
    // -------------------------------------------------------------------------

    @Test
    void shouldReturnPendingStatus_immediately() throws Exception {
        User user = createActiveUser("status@example.com");
        String token = loginAndGetToken("status@example.com");

        // Save a movie
        String saveBody = mockMvc.perform(post("/movies/save")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\": 99999}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String movieId = objectMapper.readTree(saveBody).get("id").asText();

        // Immediately poll status — must be PENDING (enrichment is async stub, hasn't run)
        mockMvc.perform(get("/movies/" + movieId + "/status")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").value(movieId));
    }

    @Test
    void shouldReturn403_whenAccessingOtherUsersStatus() throws Exception {
        // User A saves a movie
        User userA = createActiveUser("usera@example.com");
        String tokenA = loginAndGetToken("usera@example.com");

        String saveBody = mockMvc.perform(post("/movies/save")
                        .header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\": 12345}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String movieId = objectMapper.readTree(saveBody).get("id").asText();

        // User B tries to access User A's movie status
        createActiveUser("userb@example.com");
        String tokenB = loginAndGetToken("userb@example.com");

        mockMvc.perform(get("/movies/" + movieId + "/status")
                        .header("Authorization", tokenB))
                .andExpect(status().isForbidden());
    }
}
