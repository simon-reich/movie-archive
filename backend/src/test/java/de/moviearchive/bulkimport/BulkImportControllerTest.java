package de.moviearchive.bulkimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.auth.RateLimitService;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.settings.ApiKeyProvider;
import de.moviearchive.settings.EncryptionService;
import de.moviearchive.settings.UserApiKey;
import de.moviearchive.settings.UserApiKeyRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
    private BulkImportBatchRepository bulkImportBatchRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    @Qualifier("bulkImportExecutor")
    private ThreadPoolTaskExecutor bulkImportExecutor;

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
    void cleanDb() throws InterruptedException {
        // Defensive: ensure no residual async task from a previous test class sharing
        // this singleton executor is still writing to bulk_import_line/movies/users
        // before we delete rows here (avoids a racy FK-constraint violation).
        drainBulkImportExecutor(10000);
        // bulk_import_line.batch_id FK -> bulk_import_batch, and bulk_import_batch.user_id FK
        // -> users — both must be cleared before users are deleted (new in this plan, D-02).
        bulkImportLineRepository.deleteAll();
        bulkImportBatchRepository.deleteAll();
        movieRepository.deleteAll();
        userApiKeyRepository.deleteAll();
        userRepository.deleteAll();
        // Same per-IP rate-limit-bucket reset pattern as MovieControllerTest/SearchControllerTest
        // etc. — all MockMvc requests in this class share one client IP, so /auth/login's
        // Bucket4j bucket (10/min) accumulates across every test's login() calls unless reset
        // here. This plan added 2 more login() calls (progress-owner/progress-owner2/
        // progress-intruder), pushing the class over the limit without this reset.
        rateLimitService.resetAll();
    }

    /**
     * Every test in this class dispatches at least one async bulk-import run against the
     * shared, singleton bulkImportExecutor bean. Draining after each test — not just the
     * queue-capacity test — guarantees the executor is fully idle before the next test's
     * @BeforeEach cleanDb() deletes users/movies/lines, avoiding a race where a still
     * in-flight async task inserts a row referencing an about-to-be-deleted user.
     */
    @AfterEach
    void drainAfterEach() throws InterruptedException {
        drainBulkImportExecutor(10000);
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

    /** Polls until a bulk_import_line row with the given (case-insensitive) title exists, or times out. */
    private BulkImportLine pollForLineByTitle(String title, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var match = findLineByTitle(title);
            if (match != null) {
                return match;
            }
            Thread.sleep(100);
        }
        return findLineByTitle(title);
    }

    private BulkImportLine findLineByTitle(String title) {
        List<BulkImportLine> lines = bulkImportLineRepository.findAll();
        return lines.stream()
                .filter(l -> title.equalsIgnoreCase(l.getTitle()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Polls until bulkImportExecutor has no active thread and an empty queue, or the
     * timeout elapses — drains the single shared executor (running + queued task) before
     * the test method returns, mirroring WikiReloadControllerTest's drain-before-returning
     * discipline so the next test's @BeforeEach cleanDb() doesn't race a still-in-flight
     * task, and so the next test doesn't itself get spuriously rejected by a still-full queue.
     */
    private void drainBulkImportExecutor(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean idle = bulkImportExecutor.getActiveCount() == 0
                    && bulkImportExecutor.getThreadPoolExecutor().getQueue().isEmpty();
            if (idle) {
                return;
            }
            Thread.sleep(100);
        }
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

        String responseBody = mockMvc.perform(multipart("/movies/bulk-import")
                        .file(file)
                        .header("Authorization", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("started"))
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String batchId = objectMapper.readTree(responseBody).get("batchId").asText();

        BulkImportLine line = pollForLine(5000);
        assertThat(line).isNotNull();
        assertThat(line.getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(line.getTmdbId()).isEqualTo(27205);
        assertThat(movieRepository.count()).isEqualTo(1);
        // D-02: the persisted line is tagged with the batch returned in the 202 response.
        assertThat(line.getBatch()).isNotNull();
        assertThat(line.getBatch().getId().toString()).isEqualTo(batchId);
        // D-04: poster_path is captured at save time, zero extra TMDB calls.
        assertThat(line.getPosterPath()).isEqualTo("/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg");
    }

    @Test
    void shouldReturnEventStream_whenOwnerRequestsProgress() throws Exception {
        User user = createActiveUser("progress-owner@example.com");
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("progress-owner@example.com");

        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("fixtures/tmdb/inception-search.json"))));

        String responseBody = mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain",
                                "Inception;;2010".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String batchId = objectMapper.readTree(responseBody).get("batchId").asText();

        // Let the (1ms-paced in test config) async job drain before opening the SSE connection —
        // register() must still work correctly whether the batch is in-flight or already
        // finished, since either way it's a valid owner request.
        drainBulkImportExecutor(10000);

        // This SSE endpoint intentionally never completes its own SseEmitter server-side for the
        // synthetic-complete case exercised here (the batch already finished before this GET, so
        // register() sends an immediate "complete" event but does not call emitter.complete() —
        // the client is expected to close its own connection after reading that event, per the
        // frontend's AbortController pattern). MockMvc's asyncDispatch() blocks on a
        // CountDownLatch until the async context actually completes, which never happens here —
        // so this test deliberately does NOT call asyncDispatch(), and instead asserts directly
        // on the un-dispatched MvcResult once the async request has started.
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/movies/bulk-import/{batchId}/progress", batchId)
                        .header("Authorization", token))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void shouldReturn403_whenDifferentUserRequestsProgress() throws Exception {
        User owner = createActiveUser("progress-owner2@example.com");
        saveTmdbKey(owner, "valid-tmdb-key");
        String ownerToken = loginAndGetToken("progress-owner2@example.com");

        createActiveUser("progress-intruder@example.com");
        String intruderToken = loginAndGetToken("progress-intruder@example.com");

        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("fixtures/tmdb/inception-search.json"))));

        String responseBody = mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain",
                                "Inception;;2010".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", ownerToken))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String batchId = objectMapper.readTree(responseBody).get("batchId").asText();

        drainBulkImportExecutor(10000);

        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/movies/bulk-import/{batchId}/progress", batchId)
                        .header("Authorization", intruderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));
    }

    @Test
    void shouldSkipReupload_whenLineAlreadySaved() throws Exception {
        createActiveUser("reupload@example.com");
        User user = userRepository.findByEmail("reupload@example.com").orElseThrow();
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("reupload@example.com");

        String inceptionSearchJson = loadFixture("fixtures/tmdb/inception-search.json");
        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(inceptionSearchJson)));

        byte[] content = "Inception;;2010".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain", content))
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        BulkImportLine firstLine = pollForLineByTitle("Inception", 5000);
        assertThat(firstLine).isNotNull();
        assertThat(firstLine.getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        long countAfterFirst = movieRepository.count();

        // Re-upload the byte-identical content
        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain", content))
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        // Nothing new expected — brief settle window, not a full 5s poll for a new row
        Thread.sleep(1000);

        assertThat(movieRepository.count()).isEqualTo(countAfterFirst);
        wireMock.verify(1, getRequestedFor(urlPathMatching("/3/search/movie")));
    }

    @Test
    void shouldMarkAmbiguous_whenMultipleYearMatchesNoOriginalTitle() throws Exception {
        createActiveUser("ambiguous@example.com");
        User user = userRepository.findByEmail("ambiguous@example.com").orElseThrow();
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("ambiguous@example.com");

        String robinHoodJson = loadFixture("fixtures/tmdb/robin-hood-ambiguous-search.json");
        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(robinHoodJson)));

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain",
                                "Robin Hood;;2010".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        BulkImportLine line = pollForLineByTitle("Robin Hood", 5000);
        assertThat(line).isNotNull();
        assertThat(line.getStatus()).isEqualTo(BulkImportLineStatus.AMBIGUOUS);
        assertThat(line.getTmdbId()).isNull();
        assertThat(movieRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldNarrowToUnique_whenOriginalTitleMatches() throws Exception {
        createActiveUser("narrow@example.com");
        User user = userRepository.findByEmail("narrow@example.com").orElseThrow();
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("narrow@example.com");

        String robinHoodJson = loadFixture("fixtures/tmdb/robin-hood-ambiguous-search.json");
        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(robinHoodJson)));

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain",
                                "Robin Hood;Robin des Bois;2010".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        BulkImportLine line = pollForLineByTitle("Robin Hood", 5000);
        assertThat(line).isNotNull();
        assertThat(line.getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(line.getTmdbId()).isEqualTo(1002);
    }

    @Test
    void shouldReturn422_whenNoTmdbKeyConfigured() throws Exception {
        createActiveUser("nokey-bulk@example.com");
        String token = loginAndGetToken("nokey-bulk@example.com");

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain",
                                "Inception;;2010".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("No TMDB key configured. Add your key in Settings."));

        wireMock.verify(0, getRequestedFor(urlPathMatching("/3/search/movie")));
    }

    @Test
    void shouldReturn400_notCrash_forNonUtf8Bytes() throws Exception {
        createActiveUser("nonutf8@example.com");
        User user = userRepository.findByEmail("nonutf8@example.com").orElseThrow();
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("nonutf8@example.com");

        byte[] nonUtf8Bytes = new byte[]{(byte) 0xFF, (byte) 0xFE};

        // The garbled non-UTF-8 bytes decode (lenient UTF-8 replacement-character
        // substitution) into a single line that can never split into 3 valid fields, so
        // this upload now correctly hits the pre-flight "no lines parseable" block instead
        // of silently starting a no-op async job — TMDB is never reached.
        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "garbled.txt", "text/plain", nonUtf8Bytes))
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void shouldReturn400_whenAllLinesFailToParse() throws Exception {
        createActiveUser("allfail@example.com");
        User user = userRepository.findByEmail("allfail@example.com").orElseThrow();
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("allfail@example.com");

        byte[] plainTitles = "The Matrix\nInception\nGoodfellas".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain", plainTitles))
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("No lines could be parsed")));

        assertThat(bulkImportLineRepository.count()).isEqualTo(0);
        wireMock.verify(0, getRequestedFor(urlPathMatching("/3/search/movie")));
    }

    @Test
    void shouldReject_whenThirdImportExceedsQueueCapacity() throws Exception {
        createActiveUser("queuecap@example.com");
        User user = userRepository.findByEmail("queuecap@example.com").orElseThrow();
        saveTmdbKey(user, "valid-tmdb-key");
        String token = loginAndGetToken("queuecap@example.com");

        wireMock.stubFor(get(urlPathMatching("/3/search/movie"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("fixtures/tmdb/inception-search.json"))));

        byte[] twoLines = "Inception;;2010\nInception;;2010".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain", twoLines))
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        // Queued (not rejected) — queueCapacity=1 accepts it while the first run is busy.
        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain", twoLines))
                        .header("Authorization", token))
                .andExpect(status().isAccepted());

        // 1 running + 1 queued is now the max in flight — a third submission is rejected.
        mockMvc.perform(multipart("/movies/bulk-import")
                        .file(new MockMultipartFile("file", "films.txt", "text/plain", twoLines))
                        .header("Authorization", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").isNotEmpty());

        drainBulkImportExecutor(10000);
    }

    /**
     * Overrides the suite's global 1ms bulk-import.pacing-delay-ms default up to 2000ms for
     * this class only — a tight delay would make the queued/rejected window in
     * shouldReject_whenThirdImportExceedsQueueCapacity too small to hit reliably from
     * sequential MockMvc calls (mirrors WikiReloadControllerTest's exact rationale).
     */
    @DynamicPropertySource
    static void overridePacingDelay(DynamicPropertyRegistry registry) {
        registry.add("bulk-import.pacing-delay-ms", () -> "2000");
    }
}
