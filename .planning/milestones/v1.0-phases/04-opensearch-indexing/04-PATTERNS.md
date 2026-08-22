# Phase 4: OpenSearch Indexing - Pattern Map

**Mapped:** 2026-05-17
**Files analyzed:** 9 (7 new, 2 modified)
**Analogs found:** 9 / 9

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/src/main/java/de/moviearchive/config/OpenSearchConfig.java` | config | request-response | `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` | role-match |
| `backend/src/main/java/de/moviearchive/indexing/IndexingService.java` | service | CRUD | `backend/src/main/java/de/moviearchive/settings/SettingsService.java` | role-match |
| `backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java` | utility | transform | `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` (field extraction pattern) | data-flow-match |
| `backend/src/main/java/de/moviearchive/admin/ReindexController.java` | controller | request-response | `backend/src/main/java/de/moviearchive/movie/MovieController.java` | exact |
| `backend/src/main/resources/opensearch/movies-index.json` | config | — | (no analog — first JSON resource of this type) | none |
| `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` | test | — | `backend/src/test/java/de/moviearchive/AbstractIntegrationTest.java` | exact |
| `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` | test | CRUD | `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java` | exact |
| `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` | test | request-response | `backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java` | exact |
| `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` *(modified)* | service | event-driven | itself | exact |
| `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` *(modified)* | repository | CRUD | itself | exact |

---

## Pattern Assignments

### `backend/src/main/java/de/moviearchive/config/OpenSearchConfig.java` (config, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/config/AsyncConfig.java`

**Imports pattern** (AsyncConfig.java lines 1–8):
```java
package de.moviearchive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// Add for OpenSearchConfig:
// import org.apache.hc.core5.http.HttpHost;
// import org.opensearch.client.opensearch.OpenSearchClient;
// import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
// import org.opensearch.client.json.jackson.JacksonJsonpMapper;
// import org.springframework.beans.factory.annotation.Value;
```

**Config bean pattern** (AsyncConfig.java lines 10–22 — copy the `@Configuration` + `@Bean` structure):
```java
@Configuration
public class AsyncConfig {

    @Bean(name = "enrichmentExecutor")
    public Executor enrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        // ... property setters from @Value fields
        executor.initialize();
        return executor;
    }
}
```

**`@Value` injection pattern** — see `backend/src/main/java/de/moviearchive/settings/SettingsService.java` lines 43–44 and `backend/src/main/java/de/moviearchive/settings/SettingsController.java` lines 41–42:
```java
@Value("${app.base-url}")
private String appBaseUrl;
```
For OpenSearchConfig use `@Value("${opensearch.host}")` and `@Value("${opensearch.port}")`. Both properties already exist in `application.properties` lines 18–19.

**`application.properties` property names** (lines 17–19):
```properties
# OpenSearch
opensearch.host=${OPENSEARCH_HOST:localhost}
opensearch.port=${OPENSEARCH_PORT:9200}
```

**Critical:** Use `ApacheHttpClient5TransportBuilder` (not deprecated `RestClientTransport`). Do NOT add a `client.ping()` call — the transport is lazy and the bean must not fail at startup when OpenSearch is unreachable.

---

### `backend/src/main/java/de/moviearchive/indexing/IndexingService.java` (service, CRUD)

**Analog:** `backend/src/main/java/de/moviearchive/settings/SettingsService.java`

**Imports + class declaration pattern** (SettingsService.java lines 1–30):
```java
package de.moviearchive.indexing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
// + opensearch-java client imports

@Service
@Slf4j
public class IndexingService {

    private final OpenSearchClient client;
    private final MovieRepository movieRepository;
    private final DocumentBuilder documentBuilder;

    public IndexingService(OpenSearchClient client,
                           MovieRepository movieRepository,
                           DocumentBuilder documentBuilder) {
        this.client = client;
        this.movieRepository = movieRepository;
        this.documentBuilder = documentBuilder;
    }
```

**Silent exception handling pattern** — copy from EnrichmentService.java lines 76–88 (OMDB graceful degradation):
```java
try {
    JsonNode omdbData = omdbClient.fetch(movie.getImdbId(), omdbKey);
    movie.setRawOmdbJson(omdbData);
    log.info("OMDB data fetched for movieId={}", movieId);
} catch (Exception e) {
    log.warn("OMDB enrichment failed for movieId={} — continuing without OMDB data: {}",
            movieId, e.getMessage());
}
```
Apply the same `try/catch(Exception e)` + `log.warn(...)` + no-rethrow pattern for the OpenSearch write (D-01: OS failure is silent).

**`@Transactional` placement** — `SettingsService` uses `@Transactional` at class level (line 22). `IndexingService` should NOT use `@Transactional` at class level — it operates outside a transaction boundary (called after `EnrichmentService` commits). Use `@Transactional` only on the `setIndexedAt` + save operations.

**Log pattern** — `@Slf4j` + `log.info(...)` / `log.warn(...)` with `movieId={}` context variable, consistent with EnrichmentService.java lines 57, 81, 103, 112, 116.

---

### `backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java` (utility, transform)

**Analog:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` (field extraction pattern)

**Field extraction pattern from JsonNode** (EnrichmentService.java lines 59–73):
```java
movie.setTitle(tmdbDetail.path("title").asText(null));
movie.setOriginalTitle(tmdbDetail.path("original_title").asText(null));
String releaseDate = tmdbDetail.path("release_date").asText(null);
if (releaseDate != null && !releaseDate.isBlank()) {
    movie.setReleaseDate(LocalDate.parse(releaseDate));
}
int runtimeVal = tmdbDetail.path("runtime").asInt(0);
movie.setRuntime(runtimeVal > 0 ? runtimeVal : null);
String imdbId = tmdbDetail.path("external_ids").path("imdb_id").asText(null);
if (imdbId != null && imdbId.isBlank()) {
    imdbId = null;
}
```

Use `JsonNode.path("field").asText(null)` for strings, `JsonNode.path("field").asInt(0)` for integers, `JsonNode.path("field").asDouble(0)` for doubles — same pattern as EnrichmentService.java. This is already established and prevents NPE on missing fields.

**Class pattern:**
```java
package de.moviearchive.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.movie.Movie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class DocumentBuilder {

    public Map<String, Object> build(Movie movie) {
        Map<String, Object> doc = new HashMap<>();
        JsonNode tmdb = movie.getRawTmdbJson();  // non-null after enrichment
        JsonNode omdb = movie.getRawOmdbJson();  // may be null
        // ... field extraction using .path("field").asText(null) pattern
        return doc;
    }
}
```

Use `@Component` (not `@Service`) — it is a pure transform with no transaction needs. Inject into `IndexingService` via constructor.

---

### `backend/src/main/java/de/moviearchive/admin/ReindexController.java` (controller, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/movie/MovieController.java`

**Class declaration + constructor injection pattern** (MovieController.java lines 18–31):
```java
@RestController
@RequestMapping("/movies")
@Slf4j
public class MovieController {

    private final MovieService movieService;
    private final EnrichmentService enrichmentService;

    public MovieController(MovieService movieService, EnrichmentService enrichmentService) {
        this.movieService = movieService;
        this.enrichmentService = enrichmentService;
    }
```

Apply the same structure for ReindexController:
```java
@RestController
@RequestMapping("/admin/reindex")
@Slf4j
public class ReindexController {

    private final IndexingService indexingService;

    public ReindexController(IndexingService indexingService) {
        this.indexingService = indexingService;
    }
```

**Authentication parameter pattern** — `Authentication auth` as last parameter, same as MovieController.java lines 35–36 and SettingsController.java lines 52–53:
```java
@PostMapping("/save")
public ResponseEntity<Map<String, String>> saveMovie(
        @Valid @RequestBody SaveMovieRequest req,
        Authentication auth) {
    MovieInitiateResult result = movieService.initiate(auth.getName(), req.tmdbId());
```

**`auth.getName()` returns userId UUID string** — verified in JwtAuthFilter.java line 37 (`loadUserByUsername(userId)`) and JwtService.java line 25 (`.subject(user.getId().toString())`). Therefore ownership check is:
```java
if (!auth.getName().equals(userId.toString())) {
    throw new AccessDeniedException("Access denied.");
}
```

**`AccessDeniedException` handler pattern** (MovieController.java lines 69–72):
```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
}
```
Copy this handler into ReindexController — same import and same response body format.

**Response body pattern** — use `Map.of(...)` for simple JSON responses, same as MovieController.java line 41 (`Map.of("id", result.id().toString())`) and SettingsController.java line 55 (`Map.of("message", "API key saved.")`). For reindex endpoints: `Map.of("indexed", count)`.

**`@PathVariable UUID` pattern** — Spring MVC auto-rejects malformed UUIDs (400 Bad Request). No manual validation needed, consistent with `UUID id` at MovieController.java line 60.

---

### `backend/src/main/resources/opensearch/movies-index.json` (config)

**No close analog in codebase** — first JSON classpath resource for index definition. Structure comes entirely from RESEARCH.md patterns and `.claude/data-model.md`.

**Resource loading pattern to match** — loaded via:
```java
InputStream mappingStream = getClass().getClassLoader()
        .getResourceAsStream("opensearch/movies-index.json");
```
Place file at `backend/src/main/resources/opensearch/movies-index.json` (resolves to `opensearch/movies-index.json` on classpath).

**Existing classpath resource structure for reference** — test fixtures are at `backend/src/test/resources/fixtures/tmdb/inception-detail.json` and loaded via `getClass().getClassLoader().getResourceAsStream(path)` in EnrichmentIntegrationTest.java lines 79–83:
```java
private String loadFixture(String path) throws IOException {
    return new String(
            getClass().getClassLoader().getResourceAsStream(path).readAllBytes(),
            StandardCharsets.UTF_8);
}
```
Same `getResourceAsStream` pattern — use it in IndexingService for the index definition.

---

### `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` (test base class)

**Analog:** `backend/src/test/java/de/moviearchive/AbstractIntegrationTest.java`

**Full pattern to copy** (AbstractIntegrationTest.java lines 1–29 — copy entire structure):
```java
package de.moviearchive;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

**`AbstractOpenSearchTest` extends `AbstractIntegrationTest`** — it adds an OpenSearch container alongside Postgres (same static-block-started singleton pattern). Do NOT extend `AbstractWireMockTest` — OpenSearch tests have no external HTTP mocks.

**Properties to override via `@DynamicPropertySource`:**
```java
registry.add("opensearch.host", opensearch::getHost);
registry.add("opensearch.port", () -> opensearch.getMappedPort(9200));
```
These match the property names in `application.properties` lines 18–19.

**Container image:** Use `"opensearchproject/opensearch:2.19.0"` — same image as `docker-compose.yml`. Use `GenericContainer<?>` (zero new dependency) with env vars:
- `discovery.type=single-node`
- `DISABLE_SECURITY_PLUGIN=true`

And wait strategy: `Wait.forHttp("/_cluster/health").forStatusCodeMatching(c -> c == 200 || c == 401)`.

---

### `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` (test, CRUD)

**Analog:** `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java`

**Class declaration pattern** (EnrichmentIntegrationTest.java lines 29–49):
```java
class EnrichmentIntegrationTest extends AbstractWireMockTest {

    @Autowired
    private EnrichmentService enrichmentService;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userRepository.deleteAll();
        // create testUser + save
    }
```

`IndexingIntegrationTest` extends `AbstractOpenSearchTest` (not `AbstractWireMockTest`). `@BeforeEach` should also delete the OpenSearch index for the test user to isolate tests. Autowire `IndexingService` and `OpenSearchClient` directly.

**`pollForCompletion` helper pattern** — not needed for indexing tests (IndexingService is synchronous). Call `indexingService.index(movie)` directly and assert immediately after.

**Test data creation pattern** (EnrichmentIntegrationTest.java lines 63–69):
```java
testUser = new User("enrichtest@example.com", "$2a$10$placeholderHash");
testUser.setStatus(UserStatus.ACTIVE);
testUser = userRepository.save(testUser);
```
Same pattern: create User with placeholder hash, set status to ACTIVE, save. No need for BCrypt encoding in indexing tests (authentication is not involved).

---

### `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` (test, request-response)

**Analog:** `backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java`

**Class declaration pattern** (MovieControllerTest.java lines 31–66):
```java
@AutoConfigureMockMvc
class MovieControllerTest extends AbstractWireMockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        movieRepository.deleteAll();
        userRepository.deleteAll();
        rateLimitService.resetAll();
    }
```

`ReindexControllerTest` extends `AbstractOpenSearchTest` (not `AbstractWireMockTest`). `@AutoConfigureMockMvc` is required for MockMvc injection. The `@BeforeEach` must also reset the OpenSearch index.

**Login + JWT token helper pattern** (MovieControllerTest.java lines 69–87):
```java
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
```
Copy this helper verbatim — identical pattern needed for reindex endpoint auth tests.

**403 ownership test pattern** (MovieControllerTest.java lines 276–297):
```java
@Test
void shouldReturn403_whenAccessingOtherUsersStatus() throws Exception {
    User userA = createActiveUser("usera@example.com");
    String tokenA = loginAndGetToken("usera@example.com");
    // ... create resource as userA

    createActiveUser("userb@example.com");
    String tokenB = loginAndGetToken("userb@example.com");

    mockMvc.perform(get("/movies/" + movieId + "/status")
                    .header("Authorization", tokenB))
            .andExpect(status().isForbidden());
}
```
Apply exact same two-user 403 pattern for `shouldReturn403_whenUserMismatch` test in `ReindexControllerTest`.

**MockMvc POST pattern** (MovieControllerTest.java lines 103–109):
```java
mockMvc.perform(post("/movies/save")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tmdbId\": 27205}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").exists());
```
For reindex endpoints (no request body): `mockMvc.perform(post("/admin/reindex/" + userId).header("Authorization", token))`.

---

### `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` *(modified)*

**Analog:** itself — add Step 5 after line 111.

**Injection point** (EnrichmentService.java lines 109–112 — add AFTER these lines):
```java
// === Step 4: Persist with SUCCESS ===
movie.setStatus(MovieStatus.SUCCESS);
movieRepository.save(movie);
log.info("Enrichment complete for movieId={} status=SUCCESS", movieId);
```

**Step 5 code to insert** — copy the silent-failure pattern from OMDB step (lines 76–88):
```java
// === Step 5: OpenSearch index (silent on failure — D-01) ===
try {
    indexingService.index(movie);
    movie.setIndexedAt(Instant.now());
    movieRepository.save(movie);
    log.info("OpenSearch indexed movieId={}", movieId);
} catch (Exception e) {
    log.warn("OpenSearch indexing failed for movieId={} — indexed_at stays null: {}",
            movieId, e.getMessage());
    // D-01: status stays SUCCESS, no rethrow
}
```

**Constructor injection** — add `IndexingService indexingService` as constructor parameter, following the existing constructor at lines 27–37. No new `@Autowired` annotation needed (constructor injection is already the pattern).

**`Instant` import** — already available in the file (Movie entity uses `Instant`; verify import exists or add `import java.time.Instant;`).

---

### `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` *(modified)*

**Analog:** itself — add two new query methods.

**Existing query method patterns** (MovieRepository.java lines 22–31):
```java
/**
 * Eagerly fetches the user association so EnrichmentService can access
 * movie.getUser().getEmail() without a transaction or lazy-load.
 */
@Query("SELECT m FROM Movie m JOIN FETCH m.user WHERE m.id = :id")
Optional<Movie> findByIdWithUser(@Param("id") UUID id);

/**
 * Returns all TMDB IDs saved by the given user.
 */
@Query("SELECT m.tmdbId FROM Movie m WHERE m.user.id = :userId")
List<Integer> findTmdbIdsByUserId(@Param("userId") UUID userId);
```

**Methods to add** — follow the explicit `@Query` pattern (not Spring Data JPA name derivation) per RESEARCH.md open question 2 recommendation:
```java
/**
 * Returns all movies for the given user. Used by full reindex.
 */
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId")
List<Movie> findAllByUserId(@Param("userId") UUID userId);

/**
 * Returns movies not yet indexed in OpenSearch. Used by partial reindex.
 */
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NULL")
List<Movie> findByUserIdAndIndexedAtIsNull(@Param("userId") UUID userId);
```

Note: `Movie.indexedAt` is confirmed present at `Movie.java` line 69. Both queries reference `m.user.id` (not `m.userId`) — consistent with the existing `findTmdbIdsByUserId` query at line 30.

---

## Shared Patterns

### Constructor Injection (all new files)

**Source:** Every existing service and controller in the codebase uses constructor injection without `@Autowired`.

**Apply to:** `OpenSearchConfig.java`, `IndexingService.java`, `DocumentBuilder.java`, `ReindexController.java`

Pattern (EnrichmentService.java lines 27–37):
```java
public EnrichmentService(MovieRepository movieRepository,
                         SettingsService settingsService,
                         TmdbClient tmdbClient,
                         OmdbClient omdbClient,
                         WikipediaClient wikipediaClient) {
    this.movieRepository = movieRepository;
    // ...
}
```

### `@Slf4j` Logging

**Source:** `EnrichmentService.java` line 8; `SettingsService.java` line 14; `MovieController.java` line 9

**Apply to:** `IndexingService.java`, `DocumentBuilder.java`, `ReindexController.java`

Pattern: `@Slf4j` class annotation, then `log.info(...)` / `log.warn(...)` / `log.error(...)` with structured context variables (`movieId={}`, `userId={}`).

### JWT Ownership Check (`auth.getName()` = userId UUID string)

**Source:** `JwtService.java` line 25 (`.subject(user.getId().toString())`), `JwtAuthFilter.java` line 37 (`loadUserByUsername(userId)`)

**Apply to:** `ReindexController.java` both endpoints

Pattern:
```java
if (!auth.getName().equals(userId.toString())) {
    throw new AccessDeniedException("Access denied.");
}
```
`auth.getName()` returns a UUID string, not an email. Do NOT call `userRepository.findByEmail(auth.getName())`.

### `AccessDeniedException` Handler

**Source:** `MovieController.java` lines 69–72

**Apply to:** `ReindexController.java`

```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
}
```

### `SecurityConfig` — `/admin/**` Route

**Source:** `SecurityConfig.java` lines 29–32

**Apply to:** `SecurityConfig.java` (no change needed) — `anyRequest().authenticated()` already covers `/admin/**`. JWT is required for all non-permitted routes.

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/auth/**", "/actuator/health", "/settings/confirm-email").permitAll()
        .anyRequest().authenticated()
)
```
No new `requestMatchers` entry needed for `/admin/**`.

### Testcontainers Static Singleton Pattern

**Source:** `AbstractIntegrationTest.java` lines 16–21

**Apply to:** `AbstractOpenSearchTest.java`

```java
static final PostgreSQLContainer<?> postgres;

static {
    postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    postgres.start();
}
```
Start the container once per JVM run in a static block. Do NOT use `@Testcontainers`/`@Container` annotations — they would stop the container between test classes and cause cold-start overhead.

### `@DynamicPropertySource` Override

**Source:** `AbstractIntegrationTest.java` lines 23–28

**Apply to:** `AbstractOpenSearchTest.java`

```java
@DynamicPropertySource
static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    // ...
}
```
For OpenSearch: override `opensearch.host` and `opensearch.port` with mapped container values.

### `@BeforeEach` DB Cleanup + test-profile properties

**Source:** `MovieControllerTest.java` lines 61–66; `application-test.properties` lines 1–23

**Apply to:** `IndexingIntegrationTest.java`, `ReindexControllerTest.java`

Add `opensearch.host` and `opensearch.port` stubs to `application-test.properties` with placeholder values (overridden by `@DynamicPropertySource`):
```properties
opensearch.host=localhost
opensearch.port=9200
```
These allow the Spring context to start before the dynamic property override fires.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `backend/src/main/resources/opensearch/movies-index.json` | config | — | First JSON classpath resource for OpenSearch index definition; no existing analog in codebase |

---

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/`, `backend/src/test/java/de/moviearchive/`, `backend/src/main/resources/`

**Files scanned:** 18 source files, 2 property files

**Pattern extraction date:** 2026-05-17
