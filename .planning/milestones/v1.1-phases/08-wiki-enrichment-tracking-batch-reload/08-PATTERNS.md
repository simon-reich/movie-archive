# Phase 8: Wiki Enrichment Tracking & Batch Reload - Pattern Map

**Mapped:** 2026-08-22
**Files analyzed:** 9 (3 new, 6 modified)
**Analogs found:** 9 / 9

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/src/main/resources/db/migration/V8__add_wiki_last_attempted_at_to_movies.sql` | migration | CRUD (schema) | `V7__add_personal_fields_to_movies.sql` | exact |
| `backend/src/main/java/de/moviearchive/movie/Movie.java` (modify) | model | CRUD | itself (existing `wikiUrl`/`indexedAt` fields) | exact |
| `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` (modify) | model (repository) | CRUD (query) | `findByUserIdAndIndexedAtIsNull` in same file | exact |
| `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` | service | event-driven / batch | `EnrichmentService.java` (`enrich()`) | role-match (async orchestrator), diverges on executor + transactional scope |
| `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` | controller | request-response | `ReindexController.java` | exact |
| `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` (modify) | config | — | itself (`enrichmentExecutor` bean) | exact |
| `backend/src/main/resources/application.properties` (modify) | config | — | existing `tmdb.base-url`/`omdb.base-url` ENV-override block | exact |
| `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` | test | request-response | `ReindexControllerTest.java` | exact |
| `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` | test | batch / unit | `EnrichmentServiceTest.java` | role-match |

Also **modify** (not new files, but touched for ENRICH-01 completeness per RESEARCH.md Pitfall 3):
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — Step 3 Wikipedia try/catch (lines 96-112) must set `movie.setWikiLastAttemptedAt(Instant.now())` on the success path and both catch blocks.
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` / `EnrichmentIntegrationTest.java` — extend with `wikiLastAttemptedAt` assertions.

## Pattern Assignments

### `backend/src/main/resources/db/migration/V8__add_wiki_last_attempted_at_to_movies.sql` (migration)

**Analog:** `backend/src/main/resources/db/migration/V7__add_personal_fields_to_movies.sql`

**Naming convention:** Sequential `V{n}__description.sql`. Highest existing is `V7` (confirmed via directory listing) → next is `V8`.

**Pattern to copy:**
```sql
ALTER TABLE movies ADD COLUMN wiki_last_attempted_at TIMESTAMPTZ;
```
No `NOT NULL`/default — matches "never attempted" = NULL semantics. `TIMESTAMPTZ` type matches the existing `indexed_at TIMESTAMPTZ` column (`V6__create_movies.sql:16`).

---

### `backend/src/main/java/de/moviearchive/movie/Movie.java` (model, modify)

**Analog:** itself — existing `wikiUrl`/`indexedAt` fields (lines 66-70, verified)

**Core pattern to copy** (insert after line 70, `indexedAt` field):
```java
    @Column(name = "wiki_url")
    private String wikiUrl;

    @Column(name = "indexed_at")
    private Instant indexedAt;

// ADD, same style:
    @Column(name = "wiki_last_attempted_at")
    private Instant wikiLastAttemptedAt;
```
`java.time.Instant` already imported in `Movie.java` — no new import needed. Lombok `@Getter`/`@Setter` (class-level, confirmed convention) auto-generates accessors.

---

### `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` (model/repository, modify)

**Analog:** `findByUserIdAndIndexedAtIsNull` in the same file (lines 42-46, verified)

**Imports pattern** (lines 1-10, verified — add `java.time.Instant` since it's not currently imported):
```java
package de.moviearchive.movie;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
// ADD: import java.time.Instant;
```

**Core query pattern to copy** (existing precedent, lines 42-46):
```java
    /**
     * Returns movies not yet indexed in OpenSearch. Used by partial reindex.
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NULL")
    List<Movie> findByUserIdAndIndexedAtIsNull(@Param("userId") UUID userId);
```

**New query to add**, same style:
```java
    /**
     * Returns movies for the user missing Wikipedia data (wiki_url IS NULL) whose last
     * attempt was either never made or is outside the cooldown window. Used by batch-reload.
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.wikiUrl IS NULL " +
           "AND (m.wikiLastAttemptedAt IS NULL OR m.wikiLastAttemptedAt < :cutoff)")
    List<Movie> findEligibleForWikiReload(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
```
Note: RESEARCH.md's Open Question 1 recommends also adding `AND m.status = de.moviearchive.movie.MovieStatus.SUCCESS` to avoid wasting Wikipedia calls on ERROR-status movies with no title/year — planner should decide whether to lock this in.

---

### `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` (service, new)

**Analog:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` (full file, verified)

**Imports pattern** (lines 1-17, verified):
```java
package de.moviearchive.enrichment;

import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
```

**Async self-invocation guard** (critical pattern, from `EnrichmentService.java:47`):
```java
    /**
     * MUST be called from a different bean (WikiReloadController) — self-invocation bypasses @Async proxy.
     */
    @Async("wikiReloadExecutor")
```
Confirmed cross-bean call site precedent: `MovieController.java:39` calls `enrichmentService.enrich(result.id())` — `WikiReloadController` must call `wikiReloadService.batchReload(userId)` the same way (never internally).

**Wikipedia step to extract/reuse** (`EnrichmentService.java` lines 96-112, verified — this is the exact logic to copy into `retryWikipedia`):
```java
            try {
                int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
                String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
                String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
                WikipediaResult wiki = wikipediaClient.fetch(origTitle, movieTitle, year);
                movie.setWikiUrl(wiki.url());
                movie.setWikiSummary(wiki.summary());
                movie.setWikiPlot(wiki.plot());
                movie.setWikiCritics(wiki.critics());
                log.info("Wikipedia data fetched for movieId={}", movieId);
            } catch (WikipediaNotFoundException e) {
                log.warn("Wikipedia: no page found for movieId={} after 6 attempts — saving without wiki data", movieId);
            } catch (Exception e) {
                log.warn("Wikipedia enrichment failed for movieId={} — continuing without wiki data: {}",
                        movieId, e.getMessage());
            }
```

**Re-indexing pattern to reuse** (`EnrichmentService.java` Step 5, lines 119-129, verified — D-02 requires this after a late success):
```java
            // === Step 5: OpenSearch index (silent on failure) ===
            try {
                indexingService.index(movie);
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
                log.info("OpenSearch indexed movieId={}", movieId);
            } catch (Exception e) {
                log.warn("OpenSearch indexing failed for movieId={} — indexed_at stays null: {}",
                        movieId, e.getMessage());
            }
```

**Transaction placement pitfall** (RESEARCH.md Pitfall 4 — do not copy `EnrichmentService`'s top-level `@Transactional` onto the batch method): `@Transactional` belongs on the per-movie `retryWikipedia(Movie)` method, never on `batchReload(UUID userId)` (which contains `Thread.sleep` across iterations — a long-lived transaction there risks connection pool exhaustion).

**Full new-file pattern (from RESEARCH.md Pattern 5, already verified against actual `EnrichmentService`/`WikipediaClient`/`IndexingService` signatures):**
```java
@Service
@Slf4j
public class WikiReloadService {

    private final MovieRepository movieRepository;
    private final WikipediaClient wikipediaClient;
    private final IndexingService indexingService;

    @Value("${wiki.retry.cooldown-days:30}")
    private long cooldownDays;

    @Value("${wiki.retry.pacing-delay-ms:1000}")
    private long pacingDelayMs;

    // constructor injection, same style as EnrichmentService

    @Async("wikiReloadExecutor")
    public void batchReload(UUID userId) {
        Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
        List<Movie> eligible = movieRepository.findEligibleForWikiReload(userId, cutoff);
        log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligible.size());

        for (int i = 0; i < eligible.size(); i++) {
            Movie movie = eligible.get(i);
            try {
                retryWikipedia(movie);
            } catch (Exception e) {
                log.warn("Wiki batch-reload: unexpected error for movieId={}: {}",
                        movie.getId(), e.getMessage());
            }
            if (i < eligible.size() - 1) {
                try {
                    Thread.sleep(pacingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.info("Wiki batch-reload complete userId={} processed={}", userId, eligible.size());
    }

    @Transactional
    public void retryWikipedia(Movie movie) {
        movie.setWikiLastAttemptedAt(Instant.now());
        try {
            // ... fetch + set fields (see extracted block above) ...
            movieRepository.save(movie);
            try {
                indexingService.index(movie);
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
            } catch (Exception e) {
                log.warn("Wiki retry: OpenSearch re-index failed movieId={}: {}", movie.getId(), e.getMessage());
            }
        } catch (WikipediaNotFoundException e) {
            movieRepository.save(movie);
            log.warn("Wiki retry: still not found movieId={}", movie.getId());
        } catch (Exception e) {
            movieRepository.save(movie);
            log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
        }
    }
}
```

---

### `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (controller, new)

**Analog:** `backend/src/main/java/de/moviearchive/admin/ReindexController.java` (full file, verified — copy structure verbatim, same package)

**Imports pattern** (lines 1-15, verified):
```java
package de.moviearchive.admin;

import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
```

**Ownership check pattern to duplicate verbatim** (`ReindexController.java` lines 68-75 — codebase convention is per-controller duplication, not a shared utility; confirmed by RESEARCH.md's grep across `MovieController`/`MovieService`/`ReindexController`):
```java
    private void assertOwnership(Authentication auth, UUID userId) {
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        if (!user.getId().equals(userId)) {
            throw new AccessDeniedException("Access denied.");
        }
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
    }
```

**Core endpoint pattern** (analog to `ReindexController.java:36-45`):
```java
@RestController
@RequestMapping("/admin/wiki-reload")
@Slf4j
public class WikiReloadController {

    private final WikiReloadService wikiReloadService;
    private final UserRepository userRepository;

    public WikiReloadController(WikiReloadService wikiReloadService, UserRepository userRepository) {
        this.wikiReloadService = wikiReloadService;
        this.userRepository = userRepository;
    }

    @PostMapping("/{userId}")
    public ResponseEntity<Map<String, String>> triggerReload(
            @PathVariable UUID userId, Authentication auth) {
        assertOwnership(auth, userId);
        log.info("Wiki batch-reload requested for userId={}", userId);
        wikiReloadService.batchReload(userId);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    // assertOwnership + AccessDeniedException handler — copy verbatim, shown above
}
```

**No new `SecurityConfig` rule needed** — `/admin/wiki-reload/**` falls under the existing catch-all `anyRequest().authenticated()` in `SecurityConfig.java` (verified lines 29-32 in RESEARCH.md). Do not add `hasRole("ADMIN")` — no such role/authority exists in this codebase; confirmed no `ROLE_ADMIN` anywhere in `SecurityConfig`.

---

### `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` (config, modify)

**Analog:** itself — `enrichmentExecutor` bean (full file, verified, 22 lines)

**Pattern to copy** (add sibling bean, same class):
```java
    @Bean(name = "enrichmentExecutor")
    public Executor enrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("enrich-");
        executor.initialize();
        return executor;
    }

// ADD:
    @Bean(name = "wikiReloadExecutor")
    public Executor wikiReloadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);      // one reload run at a time by design
        executor.setQueueCapacity(1);    // a second trigger while one is running queues once, then rejects
        executor.setThreadNamePrefix("wiki-reload-");
        executor.initialize();
        return executor;
    }
```
Rationale (do not reuse `enrichmentExecutor`): a 630-film batch run occupies a thread for ~10.5 min; reusing the `core=2/max=5` pool risks starving live-save enrichment traffic mid-run. Dedicated `core=1/max=1` also structurally prevents two overlapping batch runs from pacing simultaneously.

---

### `backend/src/main/resources/application.properties` (config, modify)

**Analog:** existing ENV-override block (verified, `tmdb.base-url`/`omdb.base-url`/`wikipedia.base-url` lines)

**Pattern to copy:**
```properties
# External API base URLs (overridden by WireMock in tests via @DynamicPropertySource)
tmdb.base-url=${TMDB_BASE_URL:https://api.themoviedb.org}
omdb.base-url=${OMDB_BASE_URL:https://www.omdbapi.com}
wikipedia.base-url=${WIKIPEDIA_BASE_URL:https://en.wikipedia.org}

# ADD, same convention:
# Wiki batch-reload
wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}
wiki.retry.pacing-delay-ms=${WIKI_RETRY_PACING_DELAY_MS:1000}
```

---

### `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` (test, new)

**Analog:** `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` (full file, verified, 275 lines)

**Structure to copy:** `@AutoConfigureMockMvc class ... extends AbstractOpenSearchTest`, with `@Autowired MockMvc`/`UserRepository`/`MovieRepository`, `@BeforeEach cleanDb()`, helper methods `createActiveUser`, `loginAndGetToken`, `persistMovie` (with unique `tmdbIdSeq`), and the key ownership test:

```java
    @Test
    void shouldReturn403_whenUserMismatch() throws Exception {
        User userA = createActiveUser("reload-a@example.com");
        User userB = createActiveUser("reload-b@example.com");
        String tokenB = loginAndGetToken("reload-b@example.com");

        mockMvc.perform(post("/admin/wiki-reload/" + userA.getId())
                        .header("Authorization", tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));
    }
```

Since `batchReload` returns immediately (fire-and-forget `@Async`), a success-path test should assert `202 Accepted` + `{"status":"started"}` and, for eligibility/pacing behavior, use WireMock (`AbstractWireMockTest`) rather than asserting synchronously-completed state — see RESEARCH.md's Pitfall 5 for pacing-delay test overrides (`wiki.retry.pacing-delay-ms` reduced via `@DynamicPropertySource` or `application-test.properties`).

---

### `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` (test, new)

**Analog:** `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` (Mockito-based unit test conventions — mock `MovieRepository`/`WikipediaClient`/`IndexingService`, verify `wikiLastAttemptedAt` set on both success and failure paths, verify per-movie exception isolation in `batchReload()` does not abort the loop).

---

## Shared Patterns

### Admin endpoint ownership check (IDOR protection)
**Source:** `backend/src/main/java/de/moviearchive/admin/ReindexController.java:68-82`
**Apply to:** `WikiReloadController.java` — duplicate `assertOwnership` + `AccessDeniedException` handler verbatim (existing convention: no shared utility class across controllers).

### Async self-invocation avoidance
**Source:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:47` (comment) + `backend/src/main/java/de/moviearchive/movie/MovieController.java:39` (cross-bean call site)
**Apply to:** `WikiReloadController` must call `wikiReloadService.batchReload(userId)` from outside `WikiReloadService` — never self-invoke.

### Dedicated bounded executor per @Async concern
**Source:** `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` (`enrichmentExecutor` pattern)
**Apply to:** New `wikiReloadExecutor` bean, same class, `core=1/max=1/queue=1` sizing (see Pitfall 2 rationale above).

### Silent-failure-with-log pattern for optional enrichment steps
**Source:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:96-129` (Wikipedia step + OpenSearch index step, both catch-and-log-and-continue, never rethrow)
**Apply to:** `WikiReloadService.retryWikipedia()` — same silent-continue semantics for both the Wikipedia fetch and the D-02 re-index step.

### ENV-driven `application.properties` config convention
**Source:** `backend/src/main/resources/application.properties` (`tmdb.base-url=${TMDB_BASE_URL:...}` pattern; also `encryption.master-key=${ENCRYPTION_MASTER_KEY:...}` in CLAUDE.md)
**Apply to:** New `wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` properties.

### Sequential Flyway migration versioning
**Source:** `backend/src/main/resources/db/migration/` directory listing — `V1` through `V7` confirmed, `V7__add_personal_fields_to_movies.sql` is highest.
**Apply to:** New migration is `V8__add_wiki_last_attempted_at_to_movies.sql`.

## No Analog Found

None — every file in this phase has a close, verified structural analog already in the codebase (this phase is entirely additive/compositional per RESEARCH.md's assessment).

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/{admin,enrichment,movie,config}`, `backend/src/main/resources/{db/migration,application.properties}`, `backend/src/test/java/de/moviearchive/{admin,movie}`
**Files scanned:** ReindexController.java, EnrichmentService.java, MovieRepository.java, Movie.java, AsyncConfig.java, application.properties, ReindexControllerTest.java, db/migration/*.sql listing — all read in full or targeted-offset this session
**Pattern extraction date:** 2026-08-22
