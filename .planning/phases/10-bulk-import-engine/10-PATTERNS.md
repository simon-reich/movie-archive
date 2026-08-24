# Phase 10: Bulk Import Engine - Pattern Map

**Mapped:** 2026-08-23
**Files analyzed:** 12
**Analogs found:** 11 / 12

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/.../bulkimport/BulkImportController.java` | controller | request-response (multipart upload → 202) | `backend/.../admin/WikiReloadController.java` | exact (structure) |
| `backend/.../bulkimport/BulkImportService.java` | service | event-driven / batch (async job) | `backend/.../enrichment/WikiReloadService.java` | exact |
| `backend/.../bulkimport/BulkImportLine.java` | model | CRUD | `backend/.../movie/Movie.java` | role-match |
| `backend/.../bulkimport/BulkImportLineRepository.java` | model (repository) | CRUD | `backend/.../movie/MovieRepository.java` | role-match |
| `backend/.../bulkimport/BulkImportLineStatus.java` | model (enum) | — | `backend/.../movie/MovieStatus.java` | role-match |
| `backend/.../bulkimport/ImportLineParser.java` | utility | transform | none (first line-parsing utility in codebase) | no analog |
| `backend/.../config/AsyncConfig.java` (EXTEND) | config | — | itself (add `bulkImportExecutor` bean beside `wikiReloadExecutor`) | exact |
| `backend/.../enrichment/TmdbClient.java` (EXTEND) | service (external API client) | request-response | itself (add `originalTitle` mapping to `search()`) | exact |
| `backend/.../movie/dto/TmdbSearchResultItem.java` (EXTEND) | model (DTO/record) | — | itself (add `originalTitle` field) | exact |
| `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql` | migration | — | `V6__create_movies.sql` | exact |
| `frontend/pages/add.vue` (EXTEND) | component | request-response (file upload) | itself + `frontend/composables/useMovies.ts` | role-match |
| `frontend/composables/useMovies.ts` (EXTEND) | hook/composable | request-response | itself (`saveMovie`/`searchTmdb` pattern) | exact |
| `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` | test | request-response | `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` | exact |

## Pattern Assignments

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` (controller, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (full file read this session)

**Imports pattern** (lines 1-15 of analog):
```java
package de.moviearchive.admin;

import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
```
For bulk import, replace `UUID`/`UsernameNotFoundException` ownership plumbing with the simpler `Authentication auth` → `auth.getName()` pattern MovieController uses (bulk import has no path-variable `userId` to validate — resolve email → user server-side only, per RESEARCH.md's V4 Access Control note).

**Multipart endpoint + 202-Accepted trigger pattern** (lines 51-58 of analog, adapted per RESEARCH.md Pattern 1):
```java
@PostMapping("/{userId}")
public ResponseEntity<Map<String, String>> triggerReload(
        @PathVariable UUID userId, Authentication auth) {
    assertOwnership(auth, userId);
    log.info("Wiki batch-reload requested for userId={}", userId);
    wikiReloadService.batchReload(userId);
    return ResponseEntity.accepted().body(Map.of("status", "started"));
}
```
Bulk import variant reads the `MultipartFile` synchronously and passes only `List<String>` into the async call:
```java
@PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<Map<String, String>> uploadBulkImport(
        @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
    if (file.isEmpty()) {
        throw new IllegalArgumentException("Uploaded file is empty.");
    }
    List<String> rawLines = new String(file.getBytes(), StandardCharsets.UTF_8).lines().toList();
    bulkImportService.runImport(auth.getName(), rawLines);
    return ResponseEntity.accepted().body(Map.of("status", "started"));
}
```

**Error handling / exception handler pattern** (lines 73-91 of analog):
```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
}

@ExceptionHandler(TaskRejectedException.class)
public ResponseEntity<Map<String, String>> handleTaskRejected(TaskRejectedException ex) {
    return ResponseEntity.status(503).body(Map.of(
            "message", "A wiki-reload batch is already in progress; try again shortly."));
}
```
Copy this shape verbatim for `bulkImportExecutor`-full → 503, plus `NoTmdbKeyException` → 422 handler copied from `MovieController.java:68-71` (fail-fast if no TMDB key configured, same as `/movies/search`).

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` (service, event-driven/batch)

**Analog:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` (full file read this session)

**`@Lazy` self-proxy constructor pattern** (lines 33-65):
```java
@Service
@Slf4j
public class WikiReloadService {
    private final MovieRepository movieRepository;
    private final WikipediaClient wikipediaClient;
    private final IndexingService indexingService;
    private final WikiReloadService self;

    @Value("${wiki.retry.cooldown-days:30}")
    private long cooldownDays;

    @Value("${wiki.retry.pacing-delay-ms:1000}")
    private long pacingDelayMs;

    public WikiReloadService(MovieRepository movieRepository,
                             WikipediaClient wikipediaClient,
                             IndexingService indexingService,
                             @Lazy WikiReloadService self) {
        this.movieRepository = movieRepository;
        this.wikipediaClient = wikipediaClient;
        this.indexingService = indexingService;
        this.self = self;
    }
```
Bulk import mirrors this exactly: inject `BulkImportLineRepository`, `TmdbClient`, `MovieService`, `EnrichmentService`, `SettingsService`, and `@Lazy BulkImportService self`; add `@Value("${bulk-import.pacing-delay-ms:1000}")`.

**Async orchestrator with per-item failure isolation + pacing** (lines 113-138):
```java
@Async("wikiReloadExecutor")
public void batchReload(UUID userId) {
    Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
    List<Movie> eligible = movieRepository.findEligibleForWikiReload(userId, cutoff);
    log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligible.size());

    for (int i = 0; i < eligible.size(); i++) {
        Movie movie = eligible.get(i);
        try {
            self.retryWikipedia(movie);
        } catch (Exception e) {
            log.warn("Wiki batch-reload: unexpected error for movieId={}: {}",
                    movie.getId(), e.getMessage());
        }
        if (i < eligible.size() - 1) {
            try {
                Thread.sleep(pacingDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Wiki batch-reload interrupted for userId={} at index={}", userId, i);
                return;
            }
        }
    }
    log.info("Wiki batch-reload complete userId={} processed={}", userId, eligible.size());
}
```
Copy structure for `runImport(String email, List<String> rawLines)` on `@Async("bulkImportExecutor")` — loop over `rawLines` instead of `eligible`, call `self.processLine(email, rawLines.get(i))` instead of `self.retryWikipedia(movie)`.

**Per-item `@Transactional` method pattern** (lines 72-103, structure only — bulk import's per-line unit does more: parse → dedup-check → match → save, not applicable verbatim):
```java
@Transactional
public void retryWikipedia(Movie movie) {
    movie.setWikiLastAttemptedAt(Instant.now());
    try {
        // ... fetch, set fields, save
        log.info("Wiki retry succeeded movieId={}", movie.getId());
    } catch (WikipediaNotFoundException e) {
        movieRepository.save(movie);
        log.warn("Wiki retry: still not found movieId={}", movie.getId());
    } catch (Exception e) {
        movieRepository.save(movie);
        log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
    }
}
```
`BulkImportService.processLine()` follows the same "save on every outcome path, log at each branch" discipline — see RESEARCH.md Pattern 3/4 for the exact matching/dedup logic to layer inside this `@Transactional` method.

---

### `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` (config — EXTEND)

**Analog:** itself, `wikiReloadExecutor` bean (lines 23-39, full file read this session):
```java
/**
 * Dedicated executor for WikiReloadService.batchReload (D-05). Sized core=1/max=1/
 * queue=1 by design (D-07) — a larger pool would allow two batch runs to pace
 * Wikipedia calls simultaneously from different threads, defeating the point of
 * sequential pacing. A third overlapping trigger is rejected (TaskRejectedException)
 * once the single run-slot and single queue-slot are both occupied.
 */
@Bean(name = "wikiReloadExecutor")
public Executor wikiReloadExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("wiki-reload-");
    executor.initialize();
    return executor;
}
```
Add a new `bulkImportExecutor` bean below it following the same shape; per CONTEXT.md's explicit discretion note, start with `core=1/max=1/queue=1` (matching this pattern) unless the planner has a documented reason to widen the queue (RESEARCH.md flags Assumption A2 — a slightly larger queue e.g. `queue=2` may better tolerate double-submit clicks, but the CONTEXT.md default is "mirror wikiReloadExecutor unless there's a reason to differ").

---

### `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java` (service, external API client — EXTEND)

**Analog:** itself, `search()` method (lines 26-54, full file read this session):
```java
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
public List<TmdbSearchResultItem> search(String query, String apiKey) {
    JsonNode response = webClient.get()
            .uri("/3/search/movie?query={q}&api_key={key}&language=en-US", query, apiKey)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    List<TmdbSearchResultItem> results = new ArrayList<>();
    if (response != null && response.has("results")) {
        for (JsonNode item : response.get("results")) {
            int tmdbId = item.get("id").asInt();
            String title = item.path("title").asText(null);
            String releaseDate = item.path("release_date").asText("");
            Integer year = null;
            if (releaseDate.length() >= 4) {
                try {
                    year = Integer.parseInt(releaseDate.substring(0, 4));
                } catch (NumberFormatException ignored) {
                    // Non-numeric release_date prefix — treat year as unknown
                }
            }
            String posterPath = item.path("poster_path").asText(null);
            results.add(new TmdbSearchResultItem(tmdbId, title, year, posterPath));
        }
    }
    log.debug("TMDB search returned {} results for query={}", results.size(), query);
    return results;
}
```
**Change required:** add `String originalTitle = item.path("original_title").asText(null);` and pass it into a new `TmdbSearchResultItem(tmdbId, title, originalTitle, year, posterPath)` constructor arg. Keep the `@Retryable` annotation unchanged — RESEARCH.md's Anti-Pattern list explicitly warns against moving retry to the async orchestrator instead.

**DTO to extend:** `backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java` (full file, 8 lines):
```java
package de.moviearchive.movie.dto;

public record TmdbSearchResultItem(
    int tmdbId,
    String title,
    Integer year,
    String posterPath
) {}
```
Add `String originalTitle` field. Check all callers of the current 4-arg constructor (`TmdbClient.search()` is the only one found) and update.

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` + `BulkImportLineRepository.java` (model, CRUD)

**Analog:** `backend/src/main/java/de/moviearchive/movie/MovieService.java` — idempotent check-then-insert pattern (lines 39-62, full file read this session):
```java
/**
 * Creates a Movie row with status=PENDING and returns a MovieInitiateResult.
 * Idempotent: if the same user already has this tmdbId, returns the existing UUID with isNew=false.
 *
 * Uses check-then-insert rather than catch-on-duplicate because JPA flushes at
 * transaction commit time — a DataIntegrityViolationException from a UNIQUE violation
 * would propagate past the catch block before the transaction ends.
 */
public MovieInitiateResult initiate(String email, int tmdbId) {
    User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

    return movieRepository.findByUserIdAndTmdbId(user.getId(), tmdbId)
            .map(existing -> {
                log.info("Duplicate save detected ...");
                return new MovieInitiateResult(existing.getId(), false);
            })
            .orElseGet(() -> {
                Movie movie = movieRepository.save(new Movie(user, tmdbId));
                log.info("Movie initiated: movieId={} tmdbId={} userId={}", movie.getId(), tmdbId, user.getId());
                return new MovieInitiateResult(movie.getId(), true);
            });
}
```
`BulkImportLineRepository` needs the equivalent find-then-update (not catch-on-duplicate) idempotency shape for the `bulk_import_line` upsert described in RESEARCH.md Pattern 4 — reuse this "check-then-insert, never catch-on-duplicate" discipline verbatim as the reasoning template.

**Reuse of the exact save call (D-12)** — `backend/src/main/java/de/moviearchive/movie/MovieController.java:33-42`:
```java
@PostMapping("/save")
public ResponseEntity<Map<String, String>> saveMovie(
        @Valid @RequestBody SaveMovieRequest req,
        Authentication auth) {
    MovieInitiateResult result = movieService.initiate(auth.getName(), req.tmdbId());
    if (result.isNew()) {
        enrichmentService.enrich(result.id());
    }
    return ResponseEntity.accepted().body(Map.of("id", result.id().toString()));
}
```
`BulkImportService.processLine()` calls `movieService.initiate(email, tmdbId)` then `enrichmentService.enrich(result.id())` exactly like this — no bulk-specific save path (D-12, hard requirement).

---

### `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql` (migration)

**Analog:** `backend/src/main/resources/db/migration/V6__create_movies.sql` (full file, 24 lines):
```sql
CREATE TABLE movies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tmdb_id INTEGER NOT NULL,
    imdb_id VARCHAR(20),
    title VARCHAR(500),
    original_title VARCHAR(500),
    release_date DATE,
    runtime INTEGER,
    raw_tmdb_json JSONB,
    raw_omdb_json JSONB,
    wiki_plot TEXT,
    wiki_summary TEXT,
    wiki_critics TEXT,
    wiki_url TEXT,
    indexed_at TIMESTAMPTZ,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT movies_status_check CHECK (status IN ('PENDING', 'SUCCESS', 'ERROR')),
    UNIQUE (user_id, tmdb_id)
);
CREATE INDEX idx_movies_user_id ON movies(user_id);
CREATE INDEX idx_movies_status ON movies(user_id, status);
```
Copy this shape for `V9__create_bulk_import_line.sql`: `gen_random_uuid()` PK, `user_id UUID NOT NULL REFERENCES users(id)`, `VARCHAR(500)` for title/original_title (matches `movies.title`/`movies.original_title` widths per RESEARCH.md V5 Input Validation note), status `CHECK` constraint, `idx_..._user_id` index. **Deviation from this analog:** do NOT add a `UNIQUE` constraint on the dedup key (RESEARCH.md Common Pitfall #4 — nullable `year` breaks SQL uniqueness semantics for `PARSE_ERROR` rows); use a plain (non-unique) index instead, per RESEARCH.md's exact SQL in its Code Examples section. Next sequential file after `V8__add_wiki_last_attempted_at_to_movies.sql` (confirmed current highest migration via `ls`).

---

### `frontend/composables/useMovies.ts` (hook/composable — EXTEND)

**Analog:** itself, `saveMovie()` (full file read this session, lines 40-47):
```typescript
async function saveMovie(tmdbId: number): Promise<{ id: string }> {
  return await $fetch<{ id: string }>('/api/movies/save', {
    method: 'POST',
    body: { tmdbId },
    credentials: 'include',
    headers: authHeaders(),
  })
}
```
Add `uploadBulkImport(file: File)` per RESEARCH.md's Code Examples section:
```typescript
async function uploadBulkImport(file: File): Promise<{ status: string }> {
  const formData = new FormData()
  formData.append('file', file)
  return await $fetch<{ status: string }>('/api/movies/bulk-import', {
    method: 'POST',
    body: formData,               // ofetch auto-sets multipart/form-data + boundary; do NOT set Content-Type manually
    credentials: 'include',
    headers: authHeaders(),       // Authorization only — omit any Content-Type override
  })
}
```
`authHeaders()` (lines 27-31) is reused unchanged — the `Authorization: Bearer <token>` cookie-to-header bridge already used by every other composable function. Add `uploadBulkImport` to the `return { ... }` object at the bottom (line 64) alongside `searchTmdb, saveMovie, getStatus, getSavedTmdbIds`.

---

### `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` (test)

**Analog:** `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` (full file read this session)

Key structural elements to copy:
- `@AutoConfigureMockMvc class ... extends AbstractOpenSearchTest` + a locally-registered `@RegisterExtension WireMockExtension` (Java forbids extending two base test classes — WireMock stubbing must be duplicated locally, not inherited, exactly as this class does at lines 51-57).
- `@DynamicPropertySource` overriding the external base URL (`wikipedia.base-url` → here: `tmdb.base-url`) and the pacing delay property (lines 59-67) — for bulk import, add `bulk-import.pacing-delay-ms` override, mirroring the `wiki.retry.pacing-delay-ms=2000` override used to make queue-behavior tests deterministic (see Pitfall 3 in RESEARCH.md — default to `1` globally in `application-test.properties`, override to a larger value only in tests that assert concurrency/queueing).
- `@BeforeEach`/`@AfterEach cleanDb()` deleting `movieRepository`/`userRepository` rows both before AND after (lines 83-102) — copy this shared-Postgres-container hygiene discipline for `bulkImportLineRepository` too.
- Helper pattern: `createActiveUser(email)` (lines 106-112) and `loginAndGetToken(email)` (lines 114-125) — copy verbatim, these are generic auth-test helpers, not Wiki-specific.
- 403 IDOR test shape (`shouldReturn403_whenUserMismatch`, lines 275-286) — not directly applicable since bulk import resolves user from JWT only (no path `userId`), but the `assertOwnership`-style defensive pattern is worth noting as "what NOT to need" — bulk import is simpler here.
- 503-on-queue-full test shape (`shouldReject_whenThirdTriggerExceedsQueueCapacity`, lines 352-380) — copy structure for asserting `bulkImportExecutor`'s bounded queue rejects a 3rd concurrent upload with 503.

---

## Shared Patterns

### `@Lazy` self-proxy for per-item `@Transactional` inside an `@Async` batch loop
**Source:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:40,57-65,113-138`
**Apply to:** `BulkImportService.java` — `runImport()` (async orchestrator, NOT `@Transactional`) calling `self.processLine(...)` (the `@Transactional` per-line unit), never `this.processLine(...)`. This project's CLAUDE.md documents this same self-invocation trap generally for `@Async`/`@Retryable`; it applies identically to `@Transactional`.

### Dedicated bounded `ThreadPoolTaskExecutor` bean per background job
**Source:** `backend/src/main/java/de/moviearchive/config/AsyncConfig.java:23-39` (`wikiReloadExecutor`)
**Apply to:** New `bulkImportExecutor` bean in the same file, `core=1/max=1/queue=1` per CONTEXT.md's explicit discretion note (unless documented reason to widen).

### 202 Accepted trigger + `TaskRejectedException` → 503 exception handler
**Source:** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:51-58,86-90`
**Apply to:** `BulkImportController.uploadBulkImport()` and its exception-handler block.

### Idempotent check-then-insert (never catch-on-duplicate)
**Source:** `backend/src/main/java/de/moviearchive/movie/MovieService.java:39-62`
**Apply to:** `BulkImportLineRepository`'s find-or-create-then-update logic for the `bulk_import_line` dedup/upsert (D-08/D-09/D-10).

### Reuse of the exact save + enrich call
**Source:** `backend/src/main/java/de/moviearchive/movie/MovieController.java:37-40` (`movieService.initiate(...)` + `enrichmentService.enrich(...)`)
**Apply to:** `BulkImportService.processLine()`'s unique-match branch — D-12 hard requirement, no bulk-specific save path.

### `@Retryable` on the external-API client method, never on the async orchestrator
**Source:** `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java:26` (`@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))`)
**Apply to:** Unchanged — `TmdbClient.search()` keeps its existing `@Retryable`; `BulkImportService.runImport()` must NOT carry `@Retryable` itself (documented anti-pattern in both CLAUDE.md and RESEARCH.md).

### Fast test-suite pacing override convention
**Source:** `backend/src/test/resources/application-test.properties` (`wiki.retry.pacing-delay-ms=1` default, per-test `@DynamicPropertySource` override up when needed — see `WikiReloadControllerTest.java:64-67`)
**Apply to:** Add `bulk-import.pacing-delay-ms=1` to `application-test.properties`, override up (e.g. `2000`) only in tests asserting `bulkImportExecutor` queue/concurrency behavior.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java` | utility | transform | No line-parsing/CSV utility exists anywhere in the backend today (confirmed via RESEARCH.md's own grep this session). Plan per D-01/D-02/D-03 directly: `String.split(";", -1)` per trimmed non-blank line (the `-1` limit preserves a trailing empty `OriginalTitle` field), no CSV library. Unit-test in isolation per RESEARCH.md's Wave 0 gap list (`ImportLineParserTest.java`). |

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/{admin,enrichment,movie,config}`, `backend/src/main/resources/db/migration`, `backend/src/test/java/de/moviearchive/admin`, `frontend/{pages,composables}`
**Files scanned:** 9 full-file reads (WikiReloadService, WikiReloadController, AsyncConfig, MovieService, MovieController, TmdbClient, TmdbSearchResultItem, V6__create_movies.sql, useMovies.ts, WikiReloadControllerTest) + directory listing of migrations
**Pattern extraction date:** 2026-08-23
