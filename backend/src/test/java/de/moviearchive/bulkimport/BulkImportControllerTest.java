package de.moviearchive.bulkimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.movie.MovieRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BulkImportControllerTest extends AbstractWireMockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserApiKeyRepository userApiKeyRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private BulkImportLineRepository bulkImportLineRepository;

    @Autowired
    private EncryptionService encryptionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("wikipedia.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void cleanDb() {
        bulkImportLineRepository.deleteAll();
        movieRepository.deleteAll();
        userApiKeyRepository.deleteAll();
        userRepository.deleteAll();
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

    private String loadFixture(String path) throws IOException {
        return new String(
                getClass().getClassLoader().getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /** Polls bulk_import_line rows until at least one exists for this user, or times out. */
    private BulkImportLine pollForLine(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var lines = bulkImportLineRepository.findAll();
            if (!lines.isEmpty()) {
                return lines.get(0);
            }
            Thread.sleep(100);
        }
        var lines = bulkImportLineRepository.findAll();
        return lines.isEmpty() ? null : lines.get(0);
    }

    @Test
    void shouldSaveUniqueMatch_andPersistBulkImportLineRow() throws Exception {
        User user = createActiveUser("bulkimport@example.com");
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("bulkimport@example.com");

        String inceptionSearchJson = loadFixture("fixtures/tmdb/inception-search.json");
        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(inceptionSearchJson)));

        MockMultipartFile file = new MockMultipartFile(
                "file", "films.txt", "text/plain",
                "Inception;;2010".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(file)
                        .header("Authorization", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("started"));

        BulkImportLine line = pollForLine(5000);
        assertThat(line).isNotNull();
        assertThat(line.getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(line.getTmdbId()).isEqualTo(27205);
        assertThat(movieRepository.count()).isEqualTo(1);
    }
}
