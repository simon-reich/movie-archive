# Phase 4: OpenSearch Indexing - Research

**Researched:** 2026-05-17
**Domain:** OpenSearch Java Client 2.x, Spring Boot async integration, index lifecycle management
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** OS write failure is silent — movie status stays SUCCESS, indexed_at = null, warning logged. Consistent with OMDB/Wikipedia degradation pattern (Phase 3 D-15).
- **D-02:** GET /movies/{id}/status continues to return status=SUCCESS without indexed_at. Indexing state is an infrastructure detail — transparent to the user.
- **D-03:** Both reindex endpoints protected with JWT auth + userId == authenticated user's ID check. No ADMIN role, no Flyway migration for roles. Controller validates {userId} in path matches JWT subject.
- **D-04:** Two endpoints:
  - `POST /admin/reindex/{userId}` — full rebuild: delete existing index, recreate with fresh analyzer + mapping, reindex all Postgres movies for that user.
  - `POST /admin/reindex/{userId}/pending` — partial load: index only films where indexed_at IS NULL.
- **D-05:** Phase 4 includes watched, personal_rating, personal_notes in index mapping. Written as null in every document until Phase 6.
- **D-06:** Phase 6 must upsert OS doc when personal fields are saved (noted for Phase 6 planner, not Phase 4 scope).

### Claude's Discretion

- Whether IndexingService is its own @Service class or the indexing logic is added to EnrichmentService — whichever is cleaner given the existing code structure.
- How the document builder assembles 40+ fields from raw_tmdb_json / raw_omdb_json at index time (parsed at write, not stored as structured Postgres columns).
- Specific response body format for reindex endpoints (e.g., `{"indexed": 42}` or `{"status": "ok"}`).
- OpenSearch Testcontainers image version (must be 2.x, consistent with docker-compose.yml).
- Exact field extraction logic for computed fields (year from release_date, imdb_link from imdb_id).

### Deferred Ideas (OUT OF SCOPE)

- Phase 6 OS doc update on personal field save — Phase 6 planner must add OS upsert when watched/rating/notes are saved.
- Reindex frontend UI — flagged as v2 (FEAT-V2-03). Phase 4 is API-only.
- Index rebuild with zero-downtime (blue/green alias swap) — overkill for single-user personal app.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| IDX-01 | Film data indexed into `movies-{userId}` after Postgres persist | EnrichmentService Step 5 injection point identified; IndexingService pattern documented |
| IDX-02 | Index auto-created with custom analyzer and final mapping on first write | ensureIndexExists() pattern with withJson() for CreateIndexRequest; index existence check via BooleanResponse |
| IDX-03 | Custom analyzer: asciifolding, lowercase, elision, stop_english, kstem | JSON-based index definition approach documented; all five filters are built-in OS token filters |
| IDX-04 | Admin endpoint for index rebuild `POST /admin/reindex/{userId}` | ReindexController pattern with JWT subject validation; two-endpoint design (full + pending) documented |
</phase_requirements>

---

## Summary

Phase 4 adds OpenSearch as Step 5 in the existing EnrichmentService async pipeline and provides two admin endpoints for index lifecycle management. The technical domain is well-understood and all required OpenSearch Java client APIs exist in version 2.19.0 (already in build.gradle.kts).

The most important architectural insight is the **JSON-based index creation approach**. The opensearch-java typed builder for custom analyzers has a known bug (GitHub issue #1510) where `CustomAnalyzer` deserialization fails with "Missing required property" when the type is a string name. Using `CreateIndexRequest.Builder.withJson(InputStream)` to load the full index definition from a classpath resource sidesteps this issue entirely and produces more readable, maintainable code. This is the recommended approach.

The Testcontainers setup requires a new static `GenericContainer` (or `OpenSearchContainer` from `opensearch-testcontainers` module) in `AbstractIntegrationTest` alongside the existing Postgres container. The existing `AbstractIntegrationTest` / `AbstractWireMockTest` hierarchy extends cleanly to add an OpenSearch container. All OpenSearch integration tests extend `AbstractIntegrationTest` (not `AbstractWireMockTest`) since there are no external HTTP mocks needed for indexing tests.

**Primary recommendation:** Create a separate `IndexingService` @Service class, use JSON-based index creation (withJson from classpath resource), and add OpenSearch container to AbstractIntegrationTest using the same static-block-started pattern established in Phase 1.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Index document on save | API / Backend (async) | — | Runs inside EnrichmentService @Async thread after Postgres persist |
| Auto-create index with analyzer | API / Backend | — | ensureIndexExists() called at IndexingService.index() time; no separate lifecycle manager needed |
| Full index rebuild | API / Backend | — | ReindexController triggers sync reindex loop; no async needed (admin operation) |
| Partial reindex (pending only) | API / Backend | — | Same controller; queries Postgres for indexed_at IS NULL |
| Authorization (userId == subject) | API / Backend | — | Controller-level check; SecurityConfig already gates /admin/** behind JWT auth |

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| opensearch-java | 2.19.0 | OpenSearch Java client | Already in build.gradle.kts; project locked version [VERIFIED: build.gradle.kts] |
| httpclient5 | 5.4.4 | Transport for opensearch-java | Required by ApacheHttpClient5Transport; already in build.gradle.kts [VERIFIED: build.gradle.kts] |
| Jackson / JacksonJsonpMapper | via Spring Boot BOM | JSON serialization for client | Already on classpath via spring-boot-starter-web [VERIFIED: build.gradle.kts] |

### Test Dependencies to Add

| Library | Version | Purpose | Why Needed |
|---------|---------|---------|------------|
| opensearch-testcontainers | 2.1.4 | OpenSearch 2.x container for tests | Official opensearch-project module; v2.x branch is compatible with OS 2.x [CITED: github.com/opensearch-project/opensearch-testcontainers/releases] |

**Why 2.1.4 not 4.1.0:** v4.x of opensearch-testcontainers targets OpenSearch 3.x and requires Testcontainers 2.0.0+. v2.1.4 is the latest stable version targeting OpenSearch 2.x, matching the project's `opensearchproject/opensearch:2.19.0` Docker image. [CITED: github.com/opensearch-project/opensearch-testcontainers/releases]

**Alternative if opensearch-testcontainers module is not used:** Plain `testcontainers:testcontainers` (already on classpath via `spring-boot-testcontainers`) can start the OS image as a `GenericContainer` with `DISABLE_SECURITY_PLUGIN=true` and `discovery.type=single-node`. This adds no new dependency and avoids version compatibility questions between opensearch-testcontainers versions.

### Installation

```groovy
// build.gradle.kts — testImplementation only
testImplementation("org.opensearch:opensearch-testcontainers:2.1.4")
```

Or, zero-dependency alternative using already-present GenericContainer:

```groovy
// No new dependency needed — use GenericContainer from testcontainers:testcontainers
// which is already on classpath via spring-boot-testcontainers
```

### Version Verification

`opensearch-testcontainers 2.1.4` confirmed as Maven Central artifact from official `opensearch-project` GitHub organization. [CITED: github.com/opensearch-project/opensearch-testcontainers/releases]

`opensearch-java 2.19.0` confirmed in `backend/build.gradle.kts` line 53. [VERIFIED: build.gradle.kts]

---

## Package Legitimacy Audit

> This phase adds one optional test dependency (Maven/Java artifact). slopcheck does not verify Maven artifacts — it is npm-only. Manual verification performed instead.

| Package | Registry | Age | Source Repo | Disposition |
|---------|----------|-----|-------------|-------------|
| org.opensearch:opensearch-testcontainers | Maven Central | ~3 yrs | github.com/opensearch-project/opensearch-testcontainers (official org) | Approved |

**slopcheck was not run** (Maven artifact, not npm). Manual verification: the artifact is published by `opensearch-project` — the official OpenSearch GitHub organization — and has 100+ downstream dependents on Maven Central.

**Alternative path (zero new dependency):** Use `GenericContainer<?>` from the already-present `testcontainers` transitive dependency to start `opensearchproject/opensearch:2.19.0`. This eliminates the new dependency entirely.

---

## Architecture Patterns

### System Architecture Diagram

```
HTTP Request
    |
    v
MovieController (POST /movies/save)
    |
    v
MovieService.initiate() --> Postgres (PENDING row created)
    |
    v [202 returned immediately]
    |
EnrichmentService.enrich() [@Async("enrichmentExecutor")]
    |
    +--> TmdbClient.fetchDetail()  --> rawTmdbJson stored
    +--> OmdbClient.fetch()        --> rawOmdbJson stored (optional)
    +--> WikipediaClient.fetch()   --> wiki fields stored (optional)
    |
    +--> movieRepository.save()    --> Postgres (status=SUCCESS)
    |
    +--> IndexingService.index()   --> [NEW] OpenSearch (Step 5)
             |
             +--> ensureIndexExists("movies-{userId}")
             |         |
             |    [exists?] NO --> CreateIndexRequest.withJson(mappingStream)
             |         |
             |         YES --> skip
             |
             +--> Parse rawTmdbJson + rawOmdbJson --> DocumentBuilder
             |
             +--> IndexRequest<Map<String,Object>> --> OS write
             |
             +--> movie.setIndexedAt(Instant.now()) --> Postgres update
             |
     [on OS failure] --> log.warn(), indexed_at stays null (D-01)


Admin Endpoints (ReindexController)
    |
    POST /admin/reindex/{userId}          -- full rebuild
    POST /admin/reindex/{userId}/pending  -- partial (indexed_at IS NULL)
```

### Recommended Project Structure

```
backend/src/main/java/de/moviearchive/
├── indexing/
│   ├── IndexingService.java          # @Service: ensureIndexExists + index(Movie)
│   └── DocumentBuilder.java          # converts Movie entity to Map<String,Object>
├── admin/
│   └── ReindexController.java        # POST /admin/reindex/** endpoints
config/
│   └── OpenSearchConfig.java         # @Bean OpenSearchClient
resources/
│   └── opensearch/
│       └── movies-index.json         # full index definition (settings + mappings)
```

### Pattern 1: OpenSearchClient Bean (no SSL — development/Docker setup)

```java
// Source: docs.opensearch.org/latest/clients/java/ + USER_GUIDE.md
@Configuration
public class OpenSearchConfig {

    @Value("${opensearch.host}")
    private String host;

    @Value("${opensearch.port}")
    private int port;

    @Bean
    public OpenSearchClient openSearchClient() {
        final HttpHost httpHost = new HttpHost("http", host, port);
        final OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
                .builder(httpHost)
                .setMapper(new JacksonJsonpMapper())
                .build();
        return new OpenSearchClient(transport);
    }
}
```

Key points:
- Scheme is `"http"` — `DISABLE_SECURITY_PLUGIN=true` is set in docker-compose.yml, no SSL needed.
- `JacksonJsonpMapper` is already on classpath (no extra import needed).
- `ApacheHttpClient5TransportBuilder` requires `httpclient5` on classpath (already present). [VERIFIED: build.gradle.kts]
- Do NOT use `RestClientTransport` — it is deprecated in opensearch-java 2.x. [CITED: CLAUDE.md §OpenSearch Java Client 2.19.0]

### Pattern 2: JSON-Based Index Creation (Recommended Approach)

The opensearch-java typed builder for custom analyzers has a known serialization issue (GitHub issue #1510 — custom `CustomAnalyzer` deserialization fails with "Missing required property 'Builder.<variant kind>'" when analyzing the returned settings). The safe, proven approach is `withJson()`:

```java
// Source: github.com/opensearch-project/opensearch-java/blob/main/guides/json.md
// + forum.opensearch.org/t/how-to-create-index-using-json-file/11137
void ensureIndexExists(String indexName) throws IOException {
    BooleanResponse exists = client.indices()
            .exists(ExistsRequest.of(r -> r.index(indexName)));
    if (exists.value()) {
        return;
    }
    InputStream mappingStream = getClass().getClassLoader()
            .getResourceAsStream("opensearch/movies-index.json");
    client.indices().create(
            CreateIndexRequest.of(r -> r.index(indexName).withJson(mappingStream)));
    log.info("Created OpenSearch index: {}", indexName);
}
```

The `movies-index.json` resource contains the full index definition. Example structure:

```json
{
  "settings": {
    "analysis": {
      "filter": {
        "stop_english": {
          "type": "stop",
          "stopwords": "_english_"
        }
      },
      "analyzer": {
        "custom_english_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["asciifolding", "lowercase", "elision", "stop_english", "kstem"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "custom_english_analyzer",
        "fields": { "raw": { "type": "keyword" } }
      }
      // ... all 40+ fields per .claude/data-model.md
    }
  }
}
```

Key points:
- `asciifolding`, `lowercase`, `elision`, `kstem` are built-in token filters — no definition block needed.
- `stop_english` requires a definition (custom name for the `stop` filter with `_english_` stopwords).
- All five filters are part of OpenSearch's `analysis-common` module, which ships with the Docker image. [CITED: docs.opensearch.org/latest/analyzers/token-filters/]
- The full field list is in `.claude/data-model.md` — that is the authoritative spec.

### Pattern 3: Index Document with Map (no typed POJO)

```java
// Source: opensearch-java USER_GUIDE.md + confirmed pattern
void indexMovie(Movie movie) throws IOException {
    String indexName = "movies-" + movie.getUser().getId();
    Map<String, Object> document = documentBuilder.build(movie);

    client.index(IndexRequest.of(r -> r
            .index(indexName)
            .id(movie.getId().toString())
            .document(document)));
}
```

`document` is a `Map<String, Object>` assembled by `DocumentBuilder`. The opensearch-java client serializes `Map<String, Object>` natively via `JacksonJsonpMapper` — no typed POJO class is needed for 40+ fields.

### Pattern 4: EnrichmentService Injection Point

```java
// Step 5 — added AFTER the existing Postgres save block (line 111 in current EnrichmentService)
// === Step 5: OpenSearch index (silent on failure — D-01) ===
movie.setStatus(MovieStatus.SUCCESS);
movieRepository.save(movie);

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

Key points:
- Two separate `movieRepository.save()` calls: the existing one for SUCCESS status, then a second for `indexed_at`. This is intentional — the first save must not be blocked by OS failure.
- Alternatively, set `indexed_at` before the second save to avoid a third round-trip.
- The `indexingService.index(movie)` call is synchronous — it runs on the same enrichment thread. Do NOT add a new `@Async` method for the OS write (CLAUDE.md §@Async/@Retryable: do not nest @Async). [CITED: CLAUDE.md]

### Pattern 5: ReindexController (JWT Subject Validation)

```java
// JWT subject = userId (UUID string) — confirmed from JwtService.generateAccessToken()
// Authentication.getName() returns userId (set by JwtAuthFilter via userDetailsService.loadUserByUsername(userId))
@RestController
@RequestMapping("/admin/reindex")
public class ReindexController {

    @PostMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> fullReindex(
            @PathVariable UUID userId,
            Authentication auth) {
        if (!auth.getName().equals(userId.toString())) {
            throw new AccessDeniedException("Access denied.");
        }
        int count = indexingService.fullReindex(userId);
        return ResponseEntity.ok(Map.of("indexed", count));
    }

    @PostMapping("/{userId}/pending")
    public ResponseEntity<Map<String, Object>> pendingReindex(
            @PathVariable UUID userId,
            Authentication auth) {
        if (!auth.getName().equals(userId.toString())) {
            throw new AccessDeniedException("Access denied.");
        }
        int count = indexingService.reindexPending(userId);
        return ResponseEntity.ok(Map.of("indexed", count));
    }
}
```

**Critical detail about JWT subject:**
- `JwtService.generateAccessToken()` sets `.subject(user.getId().toString())` — the subject is a UUID string, not an email.
- `JwtAuthFilter` calls `userDetailsService.loadUserByUsername(userId)` where userId is the extracted subject.
- `Authentication.getName()` returns the `username` from `UserDetails`, which is the userId UUID string.
- Therefore: `auth.getName().equals(userId.toString())` is the correct ownership check. [VERIFIED: JwtService.java, JwtAuthFilter.java]

### Pattern 6: Full Reindex (delete + recreate + reindex all)

```java
public int fullReindex(UUID userId) throws IOException {
    String indexName = "movies-" + userId;

    // 1. Delete existing index (ignore 404)
    try {
        client.indices().delete(DeleteIndexRequest.of(r -> r.index(indexName)));
    } catch (OpenSearchException e) {
        if (!"index_not_found_exception".equals(e.error().type())) {
            throw e;
        }
    }

    // 2. Recreate with fresh analyzer + mapping
    ensureIndexExists(indexName);

    // 3. Reindex all movies for this user
    List<Movie> movies = movieRepository.findAllByUserId(userId);
    for (Movie movie : movies) {
        try {
            indexMovie(movie);
            movie.setIndexedAt(Instant.now());
            movieRepository.save(movie);
        } catch (Exception e) {
            log.warn("Failed to reindex movieId={}: {}", movie.getId(), e.getMessage());
        }
    }
    return movies.size();
}
```

### Pattern 7: Partial Reindex (pending only)

```java
public int reindexPending(UUID userId) {
    List<Movie> pending = movieRepository.findByUserIdAndIndexedAtIsNull(userId);
    // index each movie same as above
    return pending.size();
}
```

Requires new `MovieRepository` query: `List<Movie> findByUserIdAndIndexedAtIsNull(UUID userId)` — Spring Data JPA derives this from the method name automatically.

### Anti-Patterns to Avoid

- **Using RestClientTransport:** Deprecated in opensearch-java 2.x. Always use `ApacheHttpClient5TransportBuilder`. [CITED: CLAUDE.md §OpenSearch Java Client 2.19.0]
- **Calling ensureIndexExists() on every index request:** Adds an HTTP round-trip per document. Call once at start of index operation (reindex), or use a cached boolean per index name.
- **Typed builder for custom analyzer definition:** Known bug in opensearch-java (issue #1510). Use `withJson(InputStream)` approach for index creation.
- **@Async on IndexingService.index():** Would create proxy self-invocation issues if called from EnrichmentService. Run synchronously on the enrichment thread instead. [CITED: CLAUDE.md §@Async/@Retryable]
- **Applying @Retryable to the OS client's index() method:** The enrichment pipeline already handles the OS failure silently (D-01). @Retryable is appropriate for the individual client HTTP calls (TmdbClient, OmdbClient, WikipediaClient) but optional for the OS write given the silent-fail requirement.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Index existence check | Manual HTTP call / boolean flag | `client.indices().exists(ExistsRequest...)` | Built-in API in opensearch-java 2.x; returns `BooleanResponse` |
| Custom analyzer definition | Programmatic Java builder | JSON classpath resource + `withJson()` | Typed builder has known bug (GitHub #1510); JSON approach is more readable |
| Document serialization | Manual JSON string construction | `Map<String, Object>` with `JacksonJsonpMapper` | Client handles serialization natively |
| Index deletion on rebuild | Manual HTTP DELETE | `client.indices().delete(DeleteIndexRequest...)` | Built-in API |
| Field extraction from JsonNode | Manual string parsing | Jackson `JsonNode.path("field").asText(null)` / `asInt(0)` | Already used in EnrichmentService; same pattern |

**Key insight:** The opensearch-java 2.x client covers all needed operations. The only non-obvious choice is using JSON resources for index definition instead of typed builders, due to the known custom analyzer issue.

---

## Common Pitfalls

### Pitfall 1: JWT Subject Is UserId, Not Email
**What goes wrong:** Controller uses `auth.getName()` as email to look up user, then compares User.getId() to path variable — but `auth.getName()` is actually the userId UUID string (not email).
**Why it happens:** JwtAuthFilter loads `UserDetails` via `loadUserByUsername(userId)` where userId = JWT subject. Spring Security's `Authentication.getName()` returns the `UserDetails.getUsername()` which is set to userId.
**How to avoid:** Compare `auth.getName()` directly to `userId.toString()` in the controller. Do NOT call `userRepository.findByEmail(auth.getName())`. [VERIFIED: JwtAuthFilter.java line 37, JwtService.java line 28]
**Warning signs:** `UsernameNotFoundException` thrown with a UUID string when the code tries to find a user by "email".

### Pitfall 2: Custom Analyzer Typed Builder Bug
**What goes wrong:** Using the Java builder API to construct a `CustomAnalyzer` object — specifically when OpenSearch responds with the index settings and the Java client tries to deserialize them.
**Why it happens:** opensearch-java issue #1510: the generated Analyzer class doesn't support all analyzer variants; deserialization throws "Missing required property 'Builder.<variant kind>'" for custom types.
**How to avoid:** Use `CreateIndexRequest.of(r -> r.index(name).withJson(inputStream))` with the full definition in a JSON resource file.
**Warning signs:** Deserialization errors when calling `client.indices().get()` or round-tripping analyzer settings.

### Pitfall 3: LazyInitializationException on Movie.getUser() in IndexingService
**What goes wrong:** `IndexingService.index(movie)` calls `movie.getUser().getId()` to build the index name — but `movie.getUser()` is a lazy-loaded association, and the enrichment is running on an async thread outside the original transaction boundary.
**Why it happens:** The `@Transactional` on `EnrichmentService.enrich()` commits at end of method; `Movie` entity detaches from persistence context after the Postgres save. If user is not loaded before transaction ends, `.getUser()` throws.
**How to avoid:** The existing `movieRepository.findByIdWithUser()` (JOIN FETCH) already loads the user eagerly. Ensure that the Movie object passed to IndexingService comes from this eager-fetch query, not from a re-fetch without the JOIN FETCH. Alternatively, pass `userId` explicitly as a parameter to `IndexingService.index()`. [VERIFIED: MovieRepository.java line 23]
**Warning signs:** `org.hibernate.LazyInitializationException: could not initialize proxy` in enrichment thread logs.

### Pitfall 4: ResourceAlreadyExistsException on Concurrent Index Creation
**What goes wrong:** Two films are indexed for the same user simultaneously; both threads call `ensureIndexExists()`, both see the index does not exist, both try to create it — one throws `ResourceAlreadyExistsException`.
**Why it happens:** TOCTOU (check-then-act) race condition in `ensureIndexExists()`.
**How to avoid:** Catch `OpenSearchException` with type `"resource_already_exists_exception"` in `ensureIndexExists()` and treat it as a success (the index was created by the concurrent call). [CITED: CLAUDE.md §OpenSearch Java Client 2.19.0]
**Warning signs:** Stack traces with `resource_already_exists_exception` appearing in logs during concurrent saves.

### Pitfall 5: indexed_at Not Updated on Reindex
**What goes wrong:** Full reindex completes successfully but `indexed_at` remains null because the code calls `indexMovie()` (OS write) but doesn't call `movieRepository.save(movie.setIndexedAt(...))` afterward.
**Why it happens:** The reindex loop indexes to OS but forgets to update the Postgres movie record.
**How to avoid:** After each successful `indexMovie()` call in the reindex loop, set `movie.setIndexedAt(Instant.now())` and call `movieRepository.save(movie)`.
**Warning signs:** After `/admin/reindex/{userId}`, the `/pending` endpoint immediately returns the same films as "still pending".

### Pitfall 6: OpenSearch Connection Failure During Application Startup
**What goes wrong:** `OpenSearchConfig` bean creation fails at startup if OpenSearch is not yet reachable (e.g., during local dev when only running Spring Boot without Docker Compose).
**Why it happens:** `ApacheHttpClient5TransportBuilder.build()` creates the transport but does NOT test the connection — the client is lazy. This is actually safe. Only actual API calls fail.
**How to avoid:** Do not add a connection test (like `client.ping()`) to the `@PostConstruct` or bean creation. Let the first actual operation handle the failure gracefully (D-01).
**Warning signs:** None — this is safe. Flag any temptation to add startup connectivity checks.

---

## Code Examples

### Complete Index Definition JSON (movies-index.json)

The full file belongs in `backend/src/main/resources/opensearch/movies-index.json`. The authoritative field list is in `.claude/data-model.md`. Key structural patterns:

```json
{
  "settings": {
    "analysis": {
      "filter": {
        "stop_english": {
          "type": "stop",
          "stopwords": "_english_"
        }
      },
      "analyzer": {
        "custom_english_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["asciifolding", "lowercase", "elision", "stop_english", "kstem"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": { "type": "text", "analyzer": "custom_english_analyzer", "fields": { "raw": { "type": "keyword" } } },
      "original_title": { "type": "text", "analyzer": "custom_english_analyzer", "fields": { "raw": { "type": "keyword" } } },
      "genre_list": { "type": "keyword", "fields": { "text": { "type": "text", "analyzer": "custom_english_analyzer" } } },
      "full_cast": { "type": "nested" },
      "rating_list": { "type": "flattened" },
      "poster_path": { "type": "keyword", "index": false },
      "watched": { "type": "boolean" },
      "personal_rating": { "type": "float" },
      "personal_notes": { "type": "text", "analyzer": "custom_english_analyzer" }
    }
  }
}
```

Note: `index: false` is the JSON mapping equivalent of `"indexed": no` in `.claude/data-model.md` for non-searchable storage-only fields (`poster_path`, `backdrop_path`, `poster_list`, `backdrop_list`, `video_list`, `trailer_key`, `wikipedia_url`, `wikipedia_plot_html`, `wikipedia_full_html`, `box_office`).

### DocumentBuilder — Field Extraction Logic

```java
// Source: pattern from EnrichmentService.java (lines 59-73) applied to document assembly
public Map<String, Object> build(Movie movie) {
    Map<String, Object> doc = new HashMap<>();
    JsonNode tmdb = movie.getRawTmdbJson(); // non-null after enrichment
    JsonNode omdb = movie.getRawOmdbJson(); // may be null

    // Scalar fields from TMDB
    doc.put("tmdb_id", movie.getTmdbId());
    doc.put("imdb_id", movie.getImdbId());
    doc.put("title", movie.getTitle());
    doc.put("original_title", movie.getOriginalTitle());
    doc.put("release_date", movie.getReleaseDate() != null ? movie.getReleaseDate().toString() : null);
    doc.put("runtime", movie.getRuntime());

    // Computed fields
    int year = (movie.getReleaseDate() != null) ? movie.getReleaseDate().getYear() : 0;
    doc.put("year", year > 0 ? year : null);
    doc.put("imdb_link", movie.getImdbId() != null ? "https://www.imdb.com/title/" + movie.getImdbId() : null);

    // TMDB JSON fields
    doc.put("tagline", tmdb.path("tagline").asText(null));
    doc.put("overview", tmdb.path("overview").asText(null));
    // ... genre_list, full_cast, full_crew, keyword_list etc.

    // OMDB fields (all nullable)
    if (omdb != null && !omdb.isNull()) {
        doc.put("imdb_rating", parseDoubleOrNull(omdb.path("imdbRating").asText(null)));
        // ...
    }

    // Wikipedia fields
    doc.put("wikipedia_url", movie.getWikiUrl());
    doc.put("wikipedia_summary", movie.getWikiSummary());
    // ...

    // Personal fields (Phase 4: always null)
    doc.put("watched", null);
    doc.put("personal_rating", null);
    doc.put("personal_notes", null);

    return doc;
}
```

### Testcontainers OpenSearch Setup (Extending AbstractIntegrationTest)

```java
// Option A: Using opensearch-testcontainers module
// Source: github.com/opensearch-project/opensearch-testcontainers README
static final OpenSearchContainer<?> opensearch;

static {
    opensearch = new OpenSearchContainer<>(
        DockerImageName.parse("opensearchproject/opensearch:2.19.0"));
    opensearch.start();
}

@DynamicPropertySource
static void overrideOpenSearchProperties(DynamicPropertyRegistry registry) {
    // getHttpHostAddress() returns "localhost:<mapped-port>"
    registry.add("opensearch.host", () -> opensearch.getHost());
    registry.add("opensearch.port", () -> opensearch.getMappedPort(9200));
}
```

```java
// Option B: GenericContainer (zero new dependency — preferred if version compat is uncertain)
// Source: ASSUMED — GenericContainer pattern consistent with docker-compose.yml env vars
static final GenericContainer<?> opensearch;

static {
    opensearch = new GenericContainer<>("opensearchproject/opensearch:2.19.0")
        .withExposedPorts(9200)
        .withEnv("discovery.type", "single-node")
        .withEnv("DISABLE_SECURITY_PLUGIN", "true")
        .waitingFor(Wait.forHttp("/_cluster/health")
            .forStatusCodeMatching(c -> c == 200 || c == 401));
    opensearch.start();
}

@DynamicPropertySource
static void overrideOpenSearchProperties(DynamicPropertyRegistry registry) {
    registry.add("opensearch.host", opensearch::getHost);
    registry.add("opensearch.port", () -> opensearch.getMappedPort(9200));
}
```

**Recommendation:** Option B (GenericContainer) is preferred because:
1. No new test dependency needed.
2. Uses the exact same image tag as docker-compose.yml (`opensearchproject/opensearch:2.19.0`).
3. The opensearch-testcontainers 2.1.4 release notes should be checked for Java 25 / Testcontainers BOM compatibility before Option A is chosen.

OpenSearch integration tests extend a new `AbstractOpenSearchTest` (not `AbstractWireMockTest`) since there are no WireMock stubs needed for OS indexing.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `RestClientTransport` | `ApacheHttpClient5Transport` | opensearch-java 2.x | RestClientTransport is deprecated; always use ApacheHttpClient5Transport |
| Typed builder for analyzers | `withJson(InputStream)` | Ongoing (bug not fixed) | Custom analyzer typed builder has known deserialization issues; JSON approach is the workaround |
| `spring-data-opensearch` abstraction | Direct `opensearch-java` client | Project decision | Spring Data adds overhead; direct client gives full control over index settings and custom analyzers |

**Deprecated/outdated:**
- `RestClientTransport`: Deprecated in opensearch-java 2.x — do not use. [CITED: CLAUDE.md §OpenSearch Java Client 2.19.0]
- `opensearch-rest-high-level-client`: Legacy Java High Level REST Client — do not use. Project already uses `opensearch-java` (the new client). [CITED: docs.opensearch.org/latest/clients/java-rest-high-level/]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | GenericContainer Wait.forHttp("/_cluster/health") with status 200 or 401 is the correct wait strategy for OS 2.x with security disabled | Code Examples §Testcontainers | Container might not be fully ready when Spring context starts; would cause test flakiness |
| A2 | opensearch-testcontainers 2.1.4 is the latest 2.x-compatible release | Standard Stack | Using wrong version could cause container start failures or API incompatibilities |
| A3 | OpenSearchException.error().type() returns "resource_already_exists_exception" for concurrent index creation | Common Pitfalls §Pitfall 4 | Wrong type string means the exception is rethrown instead of swallowed |

---

## Open Questions

1. **OpenSearch container startup time in CI**
   - What we know: OS Docker image takes 30-60 seconds to start (memory-intensive JVM).
   - What's unclear: Whether the existing CI pipeline times out on OpenSearch integration tests. The Postgres container starts in ~5s; OS takes much longer.
   - Recommendation: Use the same static-block-started singleton container pattern as Postgres (started once per JVM run, not per test class). The abstract test base class handles this already.

2. **findAllByUserId vs findByUserId**
   - What we know: `MovieRepository` currently has `findByUserIdAndTmdbId` and `findTmdbIdsByUserId`.
   - What's unclear: Whether Spring Data JPA will auto-derive `findAllByUserId(UUID userId)` returning `List<Movie>`.
   - Recommendation: Use `@Query("SELECT m FROM Movie m WHERE m.user.id = :userId")` explicitly to avoid any ambiguity. Also needed: `findByUserIdAndIndexedAtIsNull(UUID userId)` for the pending reindex endpoint.

3. **@Retryable on IndexingService.index()**
   - What we know: CLAUDE.md says @Retryable applies to client methods, not the @Async orchestrator.
   - What's unclear: Whether silently logging and swallowing the exception (D-01) is better than 3 retries before giving up.
   - Recommendation: Given D-01 (silent fail, admin reindex provides recovery), no @Retryable on the OS write is cleaner. The admin reindex endpoints are the recovery path.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| OpenSearch 2.x | IDX-01, IDX-02, IDX-03 | Via Docker Compose | 2.19.0 (docker-compose.yml) | — |
| Docker (for tests) | Testcontainers OpenSearch | Assumed available | — | Cannot run OS integration tests without Docker |
| Java 25 toolchain | build.gradle.kts | Assumed available | 25 | — |

**Missing dependencies with no fallback:**
- Docker is required for OpenSearch Testcontainers tests. If Docker is unavailable in CI, OpenSearch integration tests must be tagged and skipped (not the approach used in Phase 1-3 which runs all integration tests).

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers + AssertJ |
| Config file | `backend/build.gradle.kts` (JUnit Platform enabled) |
| Quick run command | `./gradlew test --tests "de.moviearchive.indexing.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| IDX-01 | Film indexed to OS after Postgres persist | Integration | `./gradlew test --tests "*IndexingIntegrationTest*"` | No — Wave 0 |
| IDX-01 | OS write failure leaves indexed_at null, status=SUCCESS | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldLeaveIndexedAtNull_whenOsFails"` | No — Wave 0 |
| IDX-02 | Index auto-created on first write with correct settings | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldCreateIndex_whenNotExists"` | No — Wave 0 |
| IDX-02 | ensureIndexExists is idempotent (no error on second call) | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldNotThrow_whenIndexAlreadyExists"` | No — Wave 0 |
| IDX-03 | Custom analyzer normalizes accented characters | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldNormalizeAccents"` | No — Wave 0 |
| IDX-03 | Custom analyzer stems English words (kstem) | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldStemEnglishWords"` | No — Wave 0 |
| IDX-04 | Full reindex deletes and recreates index | Integration (Controller) | `./gradlew test --tests "*ReindexControllerTest*shouldFullReindex"` | No — Wave 0 |
| IDX-04 | Pending reindex only indexes films where indexed_at IS NULL | Integration (Controller) | `./gradlew test --tests "*ReindexControllerTest*shouldIndexOnlyPending"` | No — Wave 0 |
| IDX-04 | Reindex blocked when userId != JWT subject | Web (MockMvc) | `./gradlew test --tests "*ReindexControllerTest*shouldReturn403_whenUserMismatch"` | No — Wave 0 |
| IDX-04 | Reindex returns {indexed: N} with correct count | Web (MockMvc) | `./gradlew test --tests "*ReindexControllerTest*shouldReturnIndexedCount"` | No — Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "de.moviearchive.indexing.*" --tests "de.moviearchive.admin.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` — covers IDX-01, IDX-02, IDX-03
- [ ] `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — covers IDX-04
- [ ] `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` — OpenSearch container base class
- [ ] `backend/src/main/resources/opensearch/movies-index.json` — index definition resource

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | JWT via existing JwtAuthFilter — all /admin/** routes require valid JWT |
| V3 Session Management | no | Stateless JWT; no session state in this phase |
| V4 Access Control | yes | Controller-level userId == JWT subject check (D-03); prevents user A from reindexing user B's data |
| V5 Input Validation | yes | @PathVariable UUID userId — Spring MVC rejects invalid UUID format automatically |
| V6 Cryptography | no | No new secrets or encryption in this phase |

### Known Threat Patterns for This Phase

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| IDOR on reindex endpoints (user A triggers reindex for user B) | Elevation of Privilege | `auth.getName().equals(userId.toString())` check; 403 on mismatch |
| Path traversal in index name construction | Tampering | `"movies-" + userId.toString()` — UUID has no path-traversal characters |
| OS command injection via movie data | Tampering | Document assembled via `Map<String, Object>` + `JacksonJsonpMapper`; no string interpolation into queries |
| Reindex DoS (trigger expensive reindex for a large collection) | Denial of Service | Single-user personal app; reindex is synchronous and user-scoped; acceptable risk |

---

## Sources

### Primary (HIGH confidence)

- `backend/build.gradle.kts` — confirmed opensearch-java 2.19.0, httpclient5 5.4.4 are present [VERIFIED]
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — confirmed injection point at line 111 [VERIFIED]
- `backend/src/main/java/de/moviearchive/security/JwtService.java` — confirmed JWT subject = userId UUID string [VERIFIED]
- `backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java` — confirmed auth.getName() = userId string [VERIFIED]
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — confirmed available queries, identified missing findByUserIdAndIndexedAtIsNull [VERIFIED]
- `backend/src/main/resources/db/migration/V6__create_movies.sql` — confirmed indexed_at column exists [VERIFIED]
- `.claude/data-model.md` — confirmed complete 40+ field mapping spec [VERIFIED]
- `CLAUDE.md` §OpenSearch Java Client 2.19.0 — ApacheHttpClient5Transport, ensureIndexExists pattern, deprecation warnings [VERIFIED]
- `docker-compose.yml` — confirmed `opensearchproject/opensearch:2.19.0` with `DISABLE_SECURITY_PLUGIN=true` [VERIFIED]

### Secondary (MEDIUM confidence)

- [OpenSearch Java Client official docs](https://docs.opensearch.org/latest/clients/java/) — ApacheHttpClient5TransportBuilder bean pattern, index creation, document indexing
- [opensearch-java USER_GUIDE.md](https://github.com/opensearch-project/opensearch-java/blob/main/USER_GUIDE.md) — plain HTTP transport builder, index deletion
- [opensearch-testcontainers README](https://github.com/opensearch-project/opensearch-testcontainers) — OpenSearchContainer, getHttpHostAddress()
- [opensearch-testcontainers releases](https://github.com/opensearch-project/opensearch-testcontainers/releases) — v4.1.0 targets OS 3.x, v2.1.4 targets OS 2.x
- [LearnersBucket index exists check](https://learnersbucket.com/examples/elasticsearch/check-if-index-exists-in-opensearch-via-java-client/) — BooleanResponse ExistsRequest pattern

### Tertiary (LOW confidence — flagged)

- [opensearch-java issue #1510](https://github.com/opensearch-project/opensearch-java/issues/1510) — custom analyzer typed builder bug; withJson workaround inferred, not explicitly documented
- [OpenSearch forum thread on JSON index creation](https://forum.opensearch.org/t/how-to-create-index-using-json-file/11137) — withJson approach confirmed by community; not in official docs

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — opensearch-java 2.19.0 and httpclient5 5.4.4 are confirmed in build.gradle.kts; all patterns derive from official client
- Architecture: HIGH — injection point, JWT subject, existing test base class patterns all verified from codebase
- Custom analyzer JSON approach: MEDIUM — withJson() is documented; the specific typed builder bug is documented in GitHub issues; the workaround is sound but the bug fix status is uncertain
- Testcontainers setup: MEDIUM — GenericContainer pattern is well-established; specific wait strategy is ASSUMED
- Pitfalls: HIGH — most derive from verified codebase inspection (JWT subject is userId, lazy-load pattern, existing MovieRepository gaps)

**Research date:** 2026-05-17
**Valid until:** 2026-06-17 (opensearch-java and testcontainers APIs are stable; 30-day validity)
