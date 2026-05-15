# Stack Research

**Domain:** Personal film archive web app (brownfield — stack locked)
**Researched:** 2026-05-15
**Confidence:** HIGH (all versions confirmed against build.gradle.kts; patterns verified against official docs and JJWT/OpenSearch source)

## Scope

Stack is locked. This document covers **library choices and patterns** within the existing stack for the remaining phases:
- Phase 1: JWT auth + BCrypt password hashing
- Phase 2: AES-256-GCM encryption for API keys
- Phases 3–4: async enrichment pipeline + OpenSearch indexing with custom analyzer
- Phases 5–6: search queries against OpenSearch

---

## Locked Core Stack (Reference)

| Layer | Technology | Version in build.gradle.kts |
|-------|------------|------------------------------|
| Backend framework | Spring Boot | 3.5.0 |
| Language | Java | 25 (toolchain) |
| Security | Spring Security | managed by Spring Boot BOM |
| Database | PostgreSQL | 16 (via Testcontainers postgres:16-alpine) |
| Search | OpenSearch | 2.x (opensearch-java 2.19.0) |
| HTTP transport | Apache HttpClient 5 | 5.4.4 |
| Build | Gradle Kotlin DSL | — |
| Migrations | Flyway | managed by Spring Boot BOM |

---

## JWT Authentication — JJWT 0.12.6

**Version in build.gradle.kts:** `io.jsonwebtoken:jjwt-api:0.12.6` (+ `jjwt-impl` + `jjwt-jackson` at runtime)

### Why this version

0.12.x is the current stable line as of 2026. The 0.11.x API (`.setSubject()`, `.setExpiration()`, `.parseClaimsJws()`) is fully removed in 0.12.x — do not use those names anywhere.

### Correct 0.12.x API

**Key derivation from config property:**
```java
// JWT_SECRET must be >= 32 chars (256 bits) for HS256
SecretKey signingKey = Keys.hmacShaKeyFor(
    Decoders.BASE64.decode(jwtSecret) // if base64-encoded
    // or: jwtSecret.getBytes(StandardCharsets.UTF_8) for raw string >= 32 chars
);
```

**Token creation:**
```java
Jwts.builder()
    .subject(userId.toString())
    .issuedAt(Date.from(now))
    .expiration(Date.from(now.plusMillis(expirationMs)))
    .signWith(signingKey)          // algorithm auto-selected from key length
    .compact();
```

**Token validation:**
```java
Jwts.parser()
    .verifyWith(signingKey)
    .build()
    .parseSignedClaims(token)     // throws JwtException on invalid/expired
    .getPayload()
    .getSubject();
```

### Spring Security integration pattern

Use `OncePerRequestFilter` — guarantees one execution per request across forwarding chains:

```java
public class JwtAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ... {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(req, res); return;
        }
        String token = header.substring(7);
        try {
            String userId = jwtService.extractSubject(token);
            // load UserDetails, set SecurityContextHolder
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException e) {
            // do nothing — request proceeds as unauthenticated
        }
        chain.doFilter(req, res);
    }
}
```

Wire in `SecurityConfig`:
```java
http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
```

### What NOT to do with JJWT

| Avoid | Why |
|-------|-----|
| `.setSubject()` / `.setExpiration()` | Removed in 0.12.x — compile error |
| `.parseClaimsJws()` | Replaced by `.parseSignedClaims()` in 0.12.x |
| Catching `ExpiredJwtException` separately to renew access tokens | Access tokens are short-lived (15 min) — let them expire and use the refresh cookie flow |
| Storing JWT secret as plain text in application.properties | Use `${JWT_SECRET}` ENV var; minimum 32 chars for HS256 |

---

## BCrypt Password Hashing

**Library:** `spring-security-crypto` — included transitively via `spring-boot-starter-security`. No additional dependency.

### Configuration

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12); // cost 12
}
```

**Cost 12 rationale:** Default is 10. Cost 12 produces ~250–400ms hash time on modern hardware, which is the accepted balance between UX friction and brute-force resistance. Per auth-flows.md, this is already decided.

### SHA-256 token hashing

All single-use tokens (email verification, password reset, email change, refresh) are stored as SHA-256 hashes. Standard Java `MessageDigest` — no extra library:

```java
public static String sha256Hex(String raw) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash); // Java 17+ HexFormat
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 not available", e);
    }
}
```

`HexFormat` is available from Java 17+ — confirmed safe on Java 25.

### What NOT to do with passwords

| Avoid | Why |
|-------|-----|
| Argon2 instead of BCrypt | Spring Security does support Argon2, but BCrypt is decided for this project — changing now means Flyway migration for existing hashes |
| Cost factor below 10 | Insecure on modern hardware |
| MD5 / SHA-256 for passwords | Not a password hashing algorithm — no work factor |

---

## AES-256-GCM Encryption (API Keys at Rest)

**Library:** `javax.crypto` — JDK standard library. No external dependency needed.

### Why raw JDK, not Spring Security Crypto

Spring Security's `AesBytesEncryptor` with GCM mode exists but forces a specific key derivation scheme (PBKDF2). Since the master key comes from ENV as a 32-byte string (configured in `application.properties` as `encryption.master-key`), raw JDK crypto gives direct control without unwanted PBKDF2 overhead.

### Implementation pattern

```java
@Service
public class AesGcmEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;   // 96-bit IV: GCM standard
    private static final int TAG_LENGTH_BITS = 128;  // 128-bit auth tag

    private final SecretKey secretKey;

    public AesGcmEncryptionService(@Value("${encryption.master-key}") String masterKey) {
        byte[] keyBytes = masterKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) throw new IllegalStateException("Master key must be exactly 32 bytes");
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Prepend IV to ciphertext: [12 bytes IV][ciphertext+tag]
        byte[] combined = new byte[IV_LENGTH_BYTES + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
        System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    public String decrypt(String base64Ciphertext) throws GeneralSecurityException {
        byte[] combined = Base64.getDecoder().decode(base64Ciphertext);
        byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
```

### IV management rule

A fresh `SecureRandom` IV is generated on every encrypt call and prepended to the stored ciphertext. The database column `encrypted_key` stores the Base64-encoded `[IV || ciphertext || GCM tag]`. The GCM tag is automatically appended by `Cipher.doFinal()` in Java — no manual handling needed.

### Masked display in API responses

API key responses always return masked values (`••••1234`). The unencrypted key is never returned after initial save — only existence (is key set? true/false) is exposed.

### What NOT to do with AES-GCM

| Avoid | Why |
|-------|-----|
| Reusing the same IV | Catastrophic for GCM — breaks confidentiality and authentication. Always generate fresh IV per encrypt call. |
| CBC mode | No authentication tag — vulnerable to padding oracle. GCM provides both encryption and integrity. |
| Storing IV separately from ciphertext | Increases schema complexity; prepend-IV-to-ciphertext is the standard pattern. |
| Hardcoding master key in source | Use `${ENCRYPTION_MASTER_KEY}` ENV var. Already configured in application.properties. |
| PBKDF2 key derivation | Unnecessary overhead when the ENV-sourced key is already 32 random bytes. |

---

## OpenSearch Java Client 2.19.0

**Dependency in build.gradle.kts:** `org.opensearch.client:opensearch-java:2.19.0` + `org.apache.httpcomponents.client5:httpclient5:5.4.4`

### Client bean configuration

Use `ApacheHttpClient5Transport` — the current recommended transport. The old `RestClientTransport` is deprecated in favor of this.

```java
@Configuration
public class OpenSearchConfig {

    @Value("${opensearch.host:localhost}")
    private String host;

    @Value("${opensearch.port:9200}")
    private int port;

    @Bean
    public OpenSearchClient openSearchClient() {
        HttpHost[] hosts = new HttpHost[]{
            new HttpHost("http", host, port)
        };
        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
            .builder(hosts)
            .setMapper(new JacksonJsonpMapper())
            .build();
        return new OpenSearchClient(transport);
    }
}
```

For production with authentication, extend with `BasicCredentialsProvider` and TLS context via `SSLContextBuilder`.

### Index creation with custom analyzer

The custom analyzer from data-model.md must be created before documents are indexed. Use `IndexSettings` with an `IndexSettingsAnalysis` to wire up the analyzer:

```java
// Custom analyzer: standard tokenizer + asciifolding + lowercase + elision + stop(english) + kstem
CustomAnalyzer analyzer = new CustomAnalyzer.Builder()
    .tokenizer("standard")
    .filter(List.of("asciifolding", "lowercase", "elision", "stop_english", "kstem"))
    .build();

// stop_english is a built-in filter name in OpenSearch for English stopwords
// asciifolding, lowercase, elision, kstem are built-in token filters — no custom definition needed

IndexSettingsAnalysis analysis = new IndexSettingsAnalysis.Builder()
    .analyzer("custom_english_analyzer", new Analyzer.Builder().custom(analyzer).build())
    .build();

IndexSettings settings = new IndexSettings.Builder()
    .analysis(analysis)
    .build();

CreateIndexRequest req = new CreateIndexRequest.Builder()
    .index("movies-" + userId)
    .settings(settings)
    .mappings(buildMovieMappings())
    .build();

client.indices().create(req);
```

For field mapping with sub-fields (e.g., `title.raw` as keyword), use `KeywordProperty` as an inner sub-field:

```java
Property titleProperty = new Property.Builder()
    .text(t -> t
        .analyzer("custom_english_analyzer")
        .fields("raw", new Property.Builder()
            .keyword(k -> k.build())
            .build()))
    .build();
```

### Index-per-user pattern

Create the index on first movie save for that user (or on account creation). Use `client.indices().exists()` to check before creating — idempotent setup is safe in async context:

```java
boolean exists = client.indices()
    .exists(e -> e.index("movies-" + userId))
    .value();
if (!exists) {
    createMoviesIndex(userId);
}
```

### Document indexing

The `movies` Postgres table holds the source of truth. The OpenSearch document is a projection (snapshot at index time). Use a dedicated `MovieDocument` record for serialization:

```java
IndexRequest<MovieDocument> req = new IndexRequest.Builder<MovieDocument>()
    .index("movies-" + userId)
    .id(movieId.toString())
    .document(movieDocument)
    .build();
client.index(req);
```

After successful indexing, set `indexed_at = Instant.now()` in the Postgres `movies` row.

### What NOT to do with OpenSearch client

| Avoid | Why |
|-------|-----|
| `RestClientTransport` (deprecated) | Replaced by `ApacheHttpClient5Transport` in opensearch-java 2.x |
| `spring-data-opensearch` | Adds abstraction overhead not needed here; direct client gives full control over index settings and custom analyzers |
| Creating the index on every indexing call without existence check | Throws `ResourceAlreadyExistsException` — always check first |
| Indexing inside the web request thread | Blocks the request thread; indexing must happen in the `@Async` enrichment pipeline |

---

## Spring @Async + @Retryable — Enrichment Pipeline

**Libraries in build.gradle.kts:**
- `org.springframework.retry:spring-retry` (explicit)
- `org.springframework:spring-aspects` (required for `@Retryable` AOP proxy)

**Enable annotations:**
```java
@SpringBootApplication
@EnableAsync
@EnableRetry
public class MovieArchiveApplication { ... }
```

### Thread pool configuration

Spring Boot's default `SimpleAsyncTaskExecutor` creates a new thread per task — not suitable for production. Configure a bounded `ThreadPoolTaskExecutor`:

```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);         // personal app: 2 concurrent enrichment tasks
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("movie-enrichment-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
```

**Size rationale for a personal app:** Core 2, max 5, queue 50. The enrichment pipeline makes sequential external API calls (TMDB → OMDB → Wikipedia); no benefit from large pools. The queue absorbs burst saves.

### Enrichment service pattern

```java
@Service
public class MovieEnrichmentService {

    @Async
    public CompletableFuture<Void> enrichAndIndex(UUID movieId, UUID userId) {
        try {
            // 1. Fetch TMDB detail (retried)
            TmdbDetail tmdb = tmdbClient.fetchDetail(movieId);
            // 2. Fetch OMDB if key available (retried, failures don't throw)
            OmdbDetail omdb = omdbClient.fetchIfKeyPresent(tmdb.imdbId(), userId);
            // 3. Fetch Wikipedia 6-step fallback (retried)
            WikiData wiki = wikiClient.fetchWithFallback(tmdb);
            // 4. Persist to Postgres
            movieRepository.updateEnrichedData(movieId, tmdb, omdb, wiki);
            // 5. Index to OpenSearch
            openSearchIndexer.index(movieId, userId);
        } catch (Exception e) {
            log.error("Enrichment failed for movie {}", movieId, e);
            // indexed_at stays null — admin endpoint can retry
        }
        return CompletableFuture.completedFuture(null);
    }
}
```

**Self-invocation trap:** `@Async` only works through the Spring proxy. Never call `enrichAndIndex()` from within the same bean class — always inject the service and call through it.

### Retry configuration

Per api-contracts.md, the retry policy is:
```java
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
public TmdbDetail fetchDetail(int tmdbId) { ... }
```

- `maxAttempts = 3`: 1 initial + 2 retries
- `delay = 1000`: 1 second initial delay
- `multiplier = 2.0`: exponential backoff → delays: 1s, 2s (before attempts 2 and 3)
- Apply to: `TmdbClient`, `OmdbClient`, `WikipediaClient` methods
- Do NOT apply `@Retryable` to the `@Async` method itself — nesting causes proxy interception issues

### OMDB graceful degradation

OMDB is explicitly optional. The client must swallow all exceptions and return `Optional.empty()`:

```java
@Retryable(...)
public Optional<OmdbDetail> fetchIfKeyPresent(String imdbId, UUID userId) {
    Optional<String> key = apiKeyService.getDecryptedKey(userId, Provider.OMDB);
    if (key.isEmpty()) return Optional.empty();
    try {
        return Optional.of(omdbApi.fetch(imdbId, key.get()));
    } catch (Exception e) {
        log.warn("OMDB fetch failed for {}, proceeding without OMDB data", imdbId, e);
        return Optional.empty();
    }
}
```

### WebClient vs RestTemplate for external API calls

**Use WebFlux `WebClient`** — `spring-boot-starter-webflux` is already in `build.gradle.kts`. WebClient is the current standard for reactive HTTP calls. Even though the enrichment pipeline is not reactive end-to-end, using WebClient in blocking mode (`.block()`) within the `@Async` thread is correct and idiomatic. RestTemplate is in maintenance mode.

```java
// In @Service annotated with @Retryable on the method:
WebClient webClient = WebClient.builder()
    .baseUrl("https://api.themoviedb.org/3")
    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
    .build();

TmdbDetail result = webClient.get()
    .uri("/movie/{id}?api_key={key}&language=en-US&append_to_response=credits,...", id, apiKey)
    .retrieve()
    .bodyToMono(TmdbDetail.class)
    .block(); // blocking is fine inside @Async thread
```

### What NOT to do with @Async/@Retryable

| Avoid | Why |
|-------|-----|
| Self-invoking `@Async` or `@Retryable` methods from same class | Spring proxy is bypassed — annotations have no effect |
| Applying `@Retryable` to the `@Async` orchestrating method | The retry wraps the async submission, not the async execution — retry never fires |
| Using `SimpleAsyncTaskExecutor` (Spring default) | Creates unlimited threads; production code needs bounded pool |
| `RestTemplate` for external calls | In maintenance mode; `WebClient` (already on classpath) is the current standard |
| Catching `RetryExhaustedException` outside the client layer | Retry exhaustion should be caught in the enrichment service and logged, not propagated to the HTTP layer |

---

## Supporting Libraries (Already in build.gradle.kts)

| Library | Version | Purpose | Notes |
|---------|---------|---------|-------|
| MapStruct | 1.6.3 | DTO/entity mapping | Annotation processor — Lombok must come first in `annotationProcessor` order (already set via `lombok-mapstruct-binding:0.2.0`) |
| Bucket4j | 8.10.1 | Rate limiting | In-memory token bucket per IP or user; use for `/auth/*` endpoints to prevent brute force |
| Flyway | BOM-managed | Schema migrations | PostgreSQL dialect; V1–V3 already applied |
| Lombok | BOM-managed | Boilerplate reduction | `@Builder`, `@Data`, `@Slf4j` — use `@Slf4j` for consistent log format |
| GreenMail | 2.1.3 (test) | In-process SMTP | Already wired; use `@RegisterExtension` with `GreenMailExtension` |
| WireMock | 3.13.0 (test) | HTTP mock server | Stub TMDB, OMDB, Wikipedia in WireMock JSON fixture files |
| Testcontainers | BOM-managed (test) | Real DB/OS in tests | `postgres:16-alpine` + OpenSearch 2.x image — no H2 |
| Bucket4j (auth) | 8.10.1 | Brute-force protection | Apply rate limit on `/auth/login`, `/auth/forgot-password` |

---

## Version Compatibility

| Package | Compatible With | Notes |
|---------|-----------------|-------|
| `jjwt-api:0.12.6` | Spring Boot 3.5.0 / Spring Security 6.x | No Spring Security OAuth2 ResourceServer dependency needed — custom filter |
| `opensearch-java:2.19.0` | `httpclient5:5.4.4` | Transport requires httpclient5; already in build.gradle.kts |
| `spring-retry` | `spring-aspects` | Both required together; `@EnableRetry` must be present |
| `mapstruct:1.6.3` | `lombok` | `lombok-mapstruct-binding:0.2.0` must appear after both in `annotationProcessor` order — already set |
| `flyway-database-postgresql` | PostgreSQL 16 | PostgreSQL-specific Flyway module; required alongside `flyway-core` since Flyway 10 |

---

## Alternatives Considered

| Recommended | Alternative | Why Not |
|-------------|-------------|---------|
| JJWT 0.12.6 (locked) | Spring Security OAuth2 Resource Server (nimbus-jose-jwt) | Overkill for a personal app; JJWT is simpler and sufficient for HS256 |
| Raw JDK AES-GCM | Spring Security `AesBytesEncryptor` GCM | Forces PBKDF2 key derivation; unnecessary when master key is already 32 random bytes from ENV |
| `ApacheHttpClient5Transport` | `RestClientTransport` (deprecated) | RestClientTransport is deprecated in opensearch-java 2.x |
| `spring-data-opensearch` | Direct `opensearch-java` client | Spring Data abstraction hides index settings and custom analyzer config; direct client is more transparent for this use case |
| `WebClient` (webflux) | `RestTemplate` | RestTemplate is in maintenance mode; WebFlux already on classpath |
| Custom `ThreadPoolTaskExecutor` | `SimpleAsyncTaskExecutor` (default) | Default creates unbounded threads; bounded pool required for production stability |

---

## Sources

- `build.gradle.kts` — authoritative version numbers (HIGH confidence)
- `application.properties` — ENV variable names and defaults confirmed (HIGH confidence)
- `.claude/auth-flows.md`, `.claude/data-model.md`, `.claude/api-contracts.md` — project-specific design decisions (HIGH confidence)
- [OpenSearch Java client docs](https://docs.opensearch.org/latest/clients/java/) — `ApacheHttpClient5Transport` as recommended transport, builder patterns (HIGH confidence)
- [JJWT GitHub](https://github.com/jwtk/jjwt) — 0.12.x API method names confirmed (HIGH confidence)
- [Spring @Async / @Retryable Baeldung](https://www.baeldung.com/spring-async-retry) — proxy self-invocation trap, `CompletableFuture` pattern (MEDIUM confidence — well-established community source)
- [BCryptPasswordEncoder Spring Security docs](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.html) — constructor with strength parameter (HIGH confidence)
- WebSearch results for AES-GCM Java patterns — IV handling and GCM tag behavior (MEDIUM confidence — confirmed against JDK javadoc behavior)

---
*Stack research for: MovieArchive brownfield — JWT auth, AES-GCM encryption, OpenSearch indexing, async enrichment pipeline*
*Researched: 2026-05-15*
