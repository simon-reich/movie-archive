# Phase 8: Wiki Enrichment Tracking & Batch Reload - Research

**Researched:** 2026-08-22
**Domain:** Spring Boot backend — schema migration, @Async batch processing, admin REST endpoint, JPA query, resilient external-API retry pacing
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Retry Scope & Re-indexing**
- **D-01:** Batch-reload retries **only the Wikipedia step**, not TMDB or OMDB. A new lean method (e.g. `retryWikipedia(movieId)` on `EnrichmentService` or a new service) reuses the existing `WikipediaClient` 6-step fallback. TMDB/OMDB data and the movie's `status` (SUCCESS/ERROR from the original save) are left untouched — this phase never re-fetches those.
- **D-02:** When a late Wikipedia fetch succeeds, the film is **re-indexed in OpenSearch** (reuse `IndexingService`, same as the existing enrichment pipeline's final step) and `indexed_at` is updated, so `wikipedia_summary`/`wikipedia_plot`/`wikipedia_critics` become searchable (SRCH-01 depends on the OpenSearch index, not Postgres directly). — **Reversibility:** reversible — re-indexing is idempotent and can be re-run via the existing `/admin/reindex/{userId}` endpoint if ever needed.

**Cooldown Window**
- **D-03:** Cooldown duration is **30 days** by default — a film whose last Wikipedia attempt (success-with-no-page-found or failure) was less than 30 days ago is skipped by batch-reload; films never attempted, or attempted more than 30 days ago, are eligible.
- **D-04:** The cooldown value is **configurable via an application property** (e.g. `wiki.retry.cooldown-days=30` in `application.properties`, overridable via ENV), matching the project's existing ENV-driven config convention (`JWT_SECRET`, `ENCRYPTION_MASTER_KEY`).

**Trigger & Execution Model**
- **D-05:** Batch-reload runs as **fire-and-forget async** (`@Async`, same pattern as the existing enrichment pipeline's bounded thread pool) — the endpoint returns immediately; the job iterates eligible films sequentially with the pacing delay in the background. No live progress tracking in this phase (progress UI is Phase 11's concern, for the import flow, not this endpoint) — progress is visible only via logs. — **Reversibility:** reversible — internal implementation detail, no published contract depends on it being sync.
- **D-06:** Batch-reload is triggered via an **admin endpoint only** in this phase — `POST /admin/wiki-reload/{userId}`, same authenticated-admin style as the existing `POST /admin/reindex/{userId}`. No dedicated UI button yet (the manual per-film retry button is Phase 9; bulk-import UI is Phase 10/11). No scheduled/automatic triggering — not requested by ENRICH-01..03.

**Pacing Delay**
- **D-07:** Pacing delay between Wikipedia calls during a batch run is **1 second** by default. This is the root-cause knob from the original incident (~89% of ~630 bulk-imported films silent-failed from rate limiting) — 1s is conservative-but-not-glacial (a 630-film run takes ~10.5 minutes).
- **D-08:** The pacing delay is **configurable via an application property** (e.g. `wiki.retry.pacing-delay-ms=1000`), consistent with the cooldown-days property — tunable without a redeploy if Wikipedia's actual rate limits turn out to need adjustment.

### Claude's Discretion
- Exact naming of the new property keys (`wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` are suggestions, not locked).
- Exact naming of the new service method(s) and repository query for "films missing wiki data outside cooldown".
- Whether the sequential pacing loop uses `Thread.sleep` inside the `@Async` batch method or a scheduled-delay mechanism — implementation detail, not user-facing.
- Exact Flyway migration version number for the new `wiki_last_attempted_at` column.
- Whether "missing Wikipedia data" is determined by `wiki_url IS NULL` (the existing convention — no Wikipedia match sets no wiki fields) — this is the natural existing signal and doesn't need a new status field.

### Deferred Ideas (OUT OF SCOPE)
- **Manual per-film retry button** — Phase 9 (ENRICH-04, ENRICH-05).
- **Live progress UI for batch-reload** — not requested for this endpoint; Phase 11's progress UI (IMPORT-05/06) is specifically for the bulk-import flow, not this admin job.
- **Scheduled/automatic batch-reload** — considered during discussion, explicitly rejected: this phase only builds an admin-triggered endpoint, not a background scheduler.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-------------------|
| ENRICH-01 | System speichert `wiki_last_attempted_at` Zeitstempel pro Film bei jedem Wikipedia-Enrichment-Versuch (Erfolg oder Fehlschlag) | New `V8` migration + `Movie.wikiLastAttemptedAt` (Pattern 1/2); set in **both** `EnrichmentService.enrich()`'s existing Wikipedia step (Pitfall 3) and the new `WikiReloadService.retryWikipedia()` (Pattern 5) |
| ENRICH-02 | Batch-Reload-Endpoint findet alle Filme eines Users ohne Wikipedia-Daten, deren letzter Versuch außerhalb des Cooldown-Fensters liegt (oder nie versucht wurde) | New `MovieRepository.findEligibleForWikiReload()` query (Pattern 3) + `POST /admin/wiki-reload/{userId}` controller (Pattern 4); see Open Question 1 for a `status = SUCCESS` filter recommendation |
| ENRICH-03 | Batch-Reload verarbeitet gefundene Filme gepaced (Delay zwischen Wikipedia-Calls), um erneutes Rate-Limiting zu vermeiden | `Thread.sleep(pacingDelayMs)` in the sequential loop inside `WikiReloadService.batchReload()` (Pattern 5), dedicated executor to prevent concurrent runs from double-pacing (Pattern 6, Pitfall 2) |
</phase_requirements>

## Summary

Phase 8 is a backend-only, low-risk extension of the existing enrichment pipeline (Phase 3). No new external libraries are required — `spring-retry`, `spring-aspects`, `spring-boot-starter-webflux` (`WebClient`), Flyway, and the `enrichmentExecutor` thread pool are already wired and proven. The work is entirely additive: one new Flyway migration (`V8`), one new `Movie` entity field, one new repository query, one new service (or two cooperating methods) for the Wikipedia-only retry, one new admin controller mirroring `ReindexController`, and two new `application.properties` keys.

The single most important scope clarification this research surfaces: **ENRICH-01 requires `wiki_last_attempted_at` to be set on every Wikipedia attempt system-wide — including the original save-flow enrichment in `EnrichmentService.enrich()`, not only the new batch-reload path.** CONTEXT.md's code-context section focuses on extracting the Wikipedia step for reuse in a new retry method, but Success Criterion 1 and ENRICH-01's wording ("bei jedem Wikipedia-Enrichment-Versuch") cover both call sites. A plan that only updates the new batch-reload path would under-deliver ENRICH-01 for movies saved after this phase ships (their first attempt would leave `wiki_last_attempted_at` null even though `EnrichmentService.enrich()` already ran the Wikipedia step).

A second correction to CONTEXT.md: `WikipediaClient.fetch()` is **not** annotated `@Retryable` in the current code, contradicting both `08-CONTEXT.md` line 83 ("already `@Retryable`") and `.claude/api-contracts.md`'s Retry Policy section ("Applies to: TMDB client, OMDB client, Wikipedia client"). Verified by reading `WikipediaClient.java` in full — no `org.springframework.retry` import, no `@Retryable` annotation on `fetch()` or any private helper. This is architecturally sound (each of the ~10 title candidates already has its own internal try/catch that swallows exceptions and returns `Optional.empty()`, so `@Retryable` on `fetch()` would only matter for the rare case where every candidate throws), but the planner must not assume Spring-managed retry-with-backoff is already happening on Wikipedia calls — the new pacing delay is the *only* resilience mechanism for this phase, by design (D-07/D-08).

**Primary recommendation:** Add `wikiLastAttemptedAt` (`Instant`) to `Movie`/`V8` migration; set it in **two** places — `EnrichmentService.enrich()`'s existing Wikipedia try/catch (both branches) and the new `WikiReloadService.retryWikipedia(Movie)` method; give batch-reload its own small dedicated `ThreadPoolTaskExecutor` bean (not `enrichmentExecutor`) sized `core=1/max=1` to guarantee sequential pacing and avoid contention with the live save flow; follow `ReindexController`'s exact structure (duplicated `assertOwnership` private method, same package, same exception handler) for the new `/admin/wiki-reload/{userId}` controller.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| `wiki_last_attempted_at` persistence | Database / Storage | API / Backend | New `movies` column, written by backend service layer on every Wikipedia attempt |
| Cooldown-eligible movie lookup | API / Backend | Database / Storage | New JPA repository query (`WHERE wiki_url IS NULL AND (wiki_last_attempted_at IS NULL OR < cutoff)`) |
| Batch-reload orchestration + pacing | API / Backend | — | New `@Async` service method; Thread.sleep-based pacing is a backend-thread concern, no other tier involved |
| Wikipedia-only re-fetch | API / Backend | External Service (Wikipedia) | Reuses existing `WikipediaClient`, called from the new retry service |
| Re-indexing after late wiki fetch | API / Backend | Search (OpenSearch) | Reuses `IndexingService.index()`, same call as `EnrichmentService.enrich()` Step 5 |
| Admin trigger endpoint | API / Backend | — | `POST /admin/wiki-reload/{userId}`, authenticated, no new UI (D-06) |
| Config (cooldown-days, pacing-delay-ms) | API / Backend | — | `application.properties` + ENV override, existing convention |

## Standard Stack

### Core
No new libraries. Everything below is already present and pinned in `backend/build.gradle.kts` / `application.properties`, confirmed by reading the files directly.

| Library | Version | Purpose | Why Standard (already used by this exact codebase) |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.0 `[VERIFIED: backend/build.gradle.kts:3]` | App framework | `id("org.springframework.boot") version "3.5.0"` |
| Java | 25 (toolchain) `[VERIFIED: backend/build.gradle.kts:12]` | Language/runtime | `languageVersion = JavaLanguageVersion.of(25)` |
| `spring-retry` + `spring-aspects` | Boot-managed `[VERIFIED: backend/build.gradle.kts:39-40]` | `@Retryable` support on `TmdbClient`/`OmdbClient` | `implementation("org.springframework.retry:spring-retry")` / `implementation("org.springframework:spring-aspects")` |
| Flyway | Boot-managed `[VERIFIED: db/migration listing]` | Schema migration V8 | Sequential `V{n}__desc.sql`, highest existing is `V7__add_personal_fields_to_movies.sql` |
| `spring-boot-starter-webflux` (`WebClient`) | Boot-managed `[VERIFIED: WikipediaClient.java:7-8]` | Already used for the Wikipedia HTTP calls the batch job will pace | `org.springframework.web.reactive.function.client.WebClient` |
| Lombok | Boot-managed | `@Getter`/`@Setter` on the new `Movie.wikiLastAttemptedAt` field | Consistent with all existing `Movie` fields |

### Supporting
None new. `Thread.sleep()` (JDK-native) is the pacing mechanism per D-08's "implementation detail, not user-facing" discretion — no scheduling library needed for a sequential, single-threaded loop.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `Thread.sleep(delayMs)` in a plain sequential loop | `TaskScheduler`/`@Scheduled` with fixed-delay per-item scheduling | Scheduler-based fixed-delay is designed for recurring triggers, not a bounded one-shot batch; adds indirection (state to track "still items left") for no benefit in a single `@Async` method that already owns its own thread for the run's duration |
| Dedicated single-thread executor for batch-reload | Reuse `enrichmentExecutor` (`core=2/max=5`) | Reuse is simpler (zero new config) but ties up one of only 2 core threads for ~10.5 min per full 630-film reload, delaying regular `/movies/save` enrichment queued behind it if triggered concurrently — see Pitfall 2 |
| Sequential Wikipedia calls (chosen, D-07) | Parallel calls with a rate limiter (e.g. Resilience4j `RateLimiter`) | Directly contradicts the phase's design driver — the original incident was *caused* by too many near-simultaneous Wikipedia calls; sequential pacing is the explicit fix, not a limitation to engineer around |

**Installation:** None — no new dependencies to add to `build.gradle.kts`.

**Version verification:** No new packages to verify against a registry. Existing pinned versions confirmed by direct file read (see Standard Stack tags above), not `npm view`/`pip index` (this is a Gradle/Java project — the analogous check is reading `build.gradle.kts`, which was done).

## Package Legitimacy Audit

**Not applicable — this phase installs no new external packages.** All functionality is built from already-vetted, already-pinned dependencies (`spring-retry`, `spring-aspects`, `spring-boot-starter-webflux`, JDK `Thread.sleep`). No `npm view`/`pip index`/`cargo search` or `package-legitimacy check` run was needed.

**Packages removed due to [SLOP] verdict:** none (no packages evaluated)
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
POST /admin/wiki-reload/{userId}  (WikiReloadController, new — mirrors ReindexController)
        │
        │ 1. assertOwnership(auth, userId)  — 403 if JWT subject != path userId
        ▼
   wikiReloadService.batchReload(userId)   ◄── @Async("wikiReloadExecutor")  [new dedicated bean, see Pitfall 2]
        │  (endpoint returns 202/200 immediately — D-05 fire-and-forget)
        ▼
   ┌─────────────────────────────────────────────────────────────┐
   │ MovieRepository.findEligibleForWikiReload(userId, cutoff)    │  ← new @Query, JPQL
   │   WHERE user_id = :userId AND wiki_url IS NULL               │
   │   AND (wiki_last_attempted_at IS NULL                        │
   │        OR wiki_last_attempted_at < :cutoff)                  │
   └─────────────────────────────────────────────────────────────┘
        │  cutoff = Instant.now().minus(cooldownDays, DAYS)
        ▼
   for each eligible movie (sequential loop):
        │
        ├─► wikiReloadService.retryWikipedia(movie)   (plain method, no @Async/@Retryable —
        │     │                                          safe to call intra-class or cross-bean)
        │     ├─ wikipediaClient.fetch(origTitle, title, year)   — reused as-is, 6-step fallback
        │     ├─ movie.setWikiLastAttemptedAt(Instant.now())     — ALWAYS set (success or failure)
        │     ├─ on success: movie.setWikiUrl/Summary/Plot/Critics(...)
        │     │     └─► indexingService.index(movie); movie.setIndexedAt(Instant.now())  (D-02)
        │     └─ on WikipediaNotFoundException / any Exception: log.warn, continue (silent, like D-15)
        │     movieRepository.save(movie)
        │
        └─► Thread.sleep(pacingDelayMs)   — D-07/D-08, paces the NEXT iteration only
                                             (skip sleep after the last item)
```

The same `retryWikipedia(Movie)` method is the natural extension point Phase 9 (ENRICH-04/05, manual per-film retry button) will call directly from a new lightweight controller — no batch loop involved for that phase, just a single invocation.

### Recommended Project Structure
```
backend/src/main/java/de/moviearchive/
├── enrichment/
│   ├── EnrichmentService.java       # existing — MODIFY: set wikiLastAttemptedAt in Step 3 (both branches)
│   ├── WikipediaClient.java         # existing — reuse fetch() as-is, no changes
│   ├── WikiReloadService.java       # NEW — retryWikipedia(Movie) + @Async batchReload(UUID userId)
│   └── ... (TmdbClient, OmdbClient, WikipediaResult, WikipediaNotFoundException — unchanged)
├── admin/
│   ├── ReindexController.java       # existing, unchanged — structural analog
│   └── WikiReloadController.java    # NEW — POST /admin/wiki-reload/{userId}
├── config/
│   └── AsyncConfig.java             # MODIFY (or add sibling bean): add wikiReloadExecutor bean
├── movie/
│   ├── Movie.java                   # MODIFY — add wikiLastAttemptedAt : Instant
│   └── MovieRepository.java         # MODIFY — add findEligibleForWikiReload query
└── resources/
    ├── application.properties       # MODIFY — add wiki.retry.cooldown-days / wiki.retry.pacing-delay-ms
    └── db/migration/
        └── V8__add_wiki_last_attempted_at_to_movies.sql   # NEW
```

### Pattern 1: New Flyway migration — simple ALTER TABLE, matches V7's style exactly
**What:** Single-statement additive column migration.
**When to use:** This phase's only schema change.
**Example (verbatim style match to the existing V7):**
```sql
-- Source: backend/src/main/resources/db/migration/V7__add_personal_fields_to_movies.sql (read in full)
-- V7 pattern: ALTER TABLE movies ADD COLUMN watched BOOLEAN NOT NULL DEFAULT FALSE;
--             ALTER TABLE movies ADD COLUMN personal_rating SMALLINT;
--             ALTER TABLE movies ADD COLUMN personal_notes TEXT;

-- V8__add_wiki_last_attempted_at_to_movies.sql (new, next available version — V7 confirmed
-- highest existing via `ls db/migration/`, no V8 present)
ALTER TABLE movies ADD COLUMN wiki_last_attempted_at TIMESTAMPTZ;
```
Type choice `TIMESTAMPTZ` matches the existing `indexed_at TIMESTAMPTZ` column `[VERIFIED: backend/src/main/resources/db/migration/V6__create_movies.sql:16]` (`indexed_at TIMESTAMPTZ,`), and the entity mapping precedent `Instant indexedAt` `[VERIFIED: backend/src/main/java/de/moviearchive/movie/Movie.java:69-70]` (`@Column(name = "indexed_at") private Instant indexedAt;`). No `NOT NULL`/default — column starts null for all existing rows, matching "never attempted" semantics required by D-03/ENRICH-02 ("films never attempted... are eligible").

### Pattern 2: `Movie` entity field addition
```java
// Source: backend/src/main/java/de/moviearchive/movie/Movie.java:66-70 (read in full, exact context)
    @Column(name = "wiki_url")
    private String wikiUrl;

    @Column(name = "indexed_at")
    private Instant indexedAt;

// ADD, following the same style:
    @Column(name = "wiki_last_attempted_at")
    private Instant wikiLastAttemptedAt;
```
`Instant` is already imported in `Movie.java` (`import java.time.Instant;`) — no new import needed.

### Pattern 3: Repository query — direct analog of the existing "not yet indexed" query
```java
// Source: backend/src/main/java/de/moviearchive/movie/MovieRepository.java:42-46 (read in full)
/**
 * Returns movies not yet indexed in OpenSearch. Used by partial reindex.
 */
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NULL")
List<Movie> findByUserIdAndIndexedAtIsNull(@Param("userId") UUID userId);

// NEW, same style — add java.time.Instant import to MovieRepository.java (not currently imported):
/**
 * Returns movies for the user missing Wikipedia data (wiki_url IS NULL) whose last
 * attempt was either never made or is outside the cooldown window. Used by batch-reload.
 */
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.wikiUrl IS NULL " +
       "AND (m.wikiLastAttemptedAt IS NULL OR m.wikiLastAttemptedAt < :cutoff)")
List<Movie> findEligibleForWikiReload(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
```
`m.wikiUrl IS NULL` as the "missing Wikipedia data" signal is confirmed correct per CONTEXT.md's discretion note and matches the actual persisted-null-on-no-match behavior verified in `EnrichmentService.enrich()` Step 3 `[VERIFIED: backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:107-108]` (`catch (WikipediaNotFoundException e) { log.warn(...); }` — no `setWikiUrl` call on the not-found path, field stays null).

### Pattern 4: New admin controller — direct structural analog of `ReindexController`
```java
// Source: backend/src/main/java/de/moviearchive/admin/ReindexController.java (read in full)
// New sibling class in the SAME package (de.moviearchive.admin), same assertOwnership
// pattern DUPLICATED (not extracted to a shared utility — matches the existing convention;
// MovieController and ReindexController each have their own private assertOwnership/ownership
// check rather than a shared component `[VERIFIED: grep for assertOwnership across
// backend/src/main/java found it only in MovieController.java, MovieService.java, and
// ReindexController.java — three independent implementations, no shared helper class]`).

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
        wikiReloadService.batchReload(userId);   // fire-and-forget, @Async — D-05
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    // assertOwnership + AccessDeniedException handler — copy verbatim from ReindexController
    // (backend/src/main/java/de/moviearchive/admin/ReindexController.java:68-82)
}
```
No new `SecurityConfig` rule needed — `/admin/wiki-reload/**` falls under the existing catch-all `[VERIFIED: backend/src/main/java/de/moviearchive/config/SecurityConfig.java:29-32]`:
```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/auth/**", "/actuator/health", "/settings/confirm-email", "/test/**").permitAll()
        .anyRequest().authenticated()
)
```
There is **no role-based `ROLE_ADMIN` gate anywhere in `SecurityConfig`** — "admin" endpoints in this codebase mean "authenticated + per-resource ownership check", identical to every other endpoint. Do not introduce a `hasRole("ADMIN")` requirement; it would break the established pattern and there is no admin role/authority in the `User` entity to check against.

### Pattern 5: Wikipedia-only retry service + batch orchestration
```java
// New file: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
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

    // constructor injection — omitted for brevity, same style as EnrichmentService

    /**
     * Fire-and-forget batch job (D-05). Runs on a DEDICATED executor (see Pitfall 2),
     * not "enrichmentExecutor" — isolates the long-running sequential loop from the
     * live save-flow's enrichment traffic.
     */
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
                // Per-movie isolation — one failure must not abort the batch
                // (matches IndexingService.fullReindex/reindexPending's per-item try/catch)
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

    /**
     * Retries the Wikipedia step for a single movie. Plain method — NOT @Async, NOT
     * @Retryable. Safe to call from within batchReload() (same class) because it needs
     * no proxy interception of its own; also directly reusable by Phase 9's manual
     * retry endpoint (called from a different bean there, which is equally safe).
     * Sets wikiLastAttemptedAt on EVERY attempt (success or failure) — ENRICH-01.
     */
    @Transactional
    public void retryWikipedia(Movie movie) {
        movie.setWikiLastAttemptedAt(Instant.now());
        try {
            int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
            String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
            String title = movie.getTitle() != null ? movie.getTitle() : "";
            WikipediaResult wiki = wikipediaClient.fetch(origTitle, title, year);
            movie.setWikiUrl(wiki.url());
            movie.setWikiSummary(wiki.summary());
            movie.setWikiPlot(wiki.plot());
            movie.setWikiCritics(wiki.critics());
            movieRepository.save(movie);
            log.info("Wiki retry succeeded movieId={}", movie.getId());

            // D-02: re-index on late success, same pattern as EnrichmentService Step 5
            try {
                indexingService.index(movie);
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
            } catch (Exception e) {
                log.warn("Wiki retry: OpenSearch re-index failed movieId={}: {}",
                        movie.getId(), e.getMessage());
            }
        } catch (WikipediaNotFoundException e) {
            movieRepository.save(movie);  // persist the updated timestamp even on not-found
            log.warn("Wiki retry: still not found movieId={}", movie.getId());
        } catch (Exception e) {
            movieRepository.save(movie);
            log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
        }
    }
}
```

### Pattern 6: Dedicated executor bean (recommendation, see Pitfall 2)
```java
// Source pattern: backend/src/main/java/de/moviearchive/config/AsyncConfig.java:9-22 (read in full)
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

// ADD, same class, same style:
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

### Anti-Patterns to Avoid
- **Applying `@Retryable` to `retryWikipedia()` or `batchReload()`:** `WikipediaClient.fetch()` already has its own internal cascading fallback across ~10 candidates with per-candidate try/catch — wrapping the caller in `@Retryable` would re-run the entire 10-candidate cascade on any residual exception, multiplying request volume during exactly the scenario (rate limiting) this phase exists to prevent.
- **Applying `@Retryable` to `batchReload()` (the `@Async` method):** CLAUDE.md's own stated pitfall — "The retry wraps the async submission, not the async execution — retry never fires." `[CITED: CLAUDE.md §Spring @Async+@Retryable, "What NOT to do with @Async/@Retryable"]`
- **Self-invoking the `@Async` method:** `batchReload()` must be invoked from a different bean than the one it's declared in (i.e., from `WikiReloadController`, not from inside `WikiReloadService` itself) — same proxy-bypass rule as `EnrichmentService.enrich()`, which is deliberately called from `MovieController` `[VERIFIED: backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:47]` ("MUST be called from a different bean (MovieController) — self-invocation bypasses @Async proxy.") and confirmed by the actual call site `[VERIFIED: backend/src/main/java/de/moviearchive/movie/MovieController.java:39]` (`enrichmentService.enrich(result.id());`).
- **Forgetting to persist `wiki_last_attempted_at` in `EnrichmentService.enrich()`'s existing Wikipedia step:** see Summary — this is required for ENRICH-01 compliance on the original save-flow path, not only the new batch-reload path.
- **Adding a `ROLE_ADMIN` check:** no such role/authority exists in this codebase; `/admin/**` means "ownership-checked, authenticated", not privilege-escalated.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Wikipedia title-fallback fetching | A second Wikipedia HTTP client | `WikipediaClient.fetch(origTitle, title, year)` as-is | Already implements the exact 6-step (10-candidate) fallback CLAUDE.md mandates; re-implementing risks drift from the documented strategy |
| Cooldown / rate-limit pacing | A generic rate-limiter library (Resilience4j, Bucket4j) | Plain `Thread.sleep(pacingDelayMs)` in a sequential loop | The batch is single-threaded and bounded by design (D-05/D-07) — a token-bucket abstraction adds a dependency and config surface for a problem a `for` loop with a sleep already solves completely |
| OpenSearch re-indexing after late data arrives | New indexing logic | `IndexingService.index(movie)` (existing, unchanged) | Idempotent, already handles index-creation-on-demand and document upsert; reused identically in D-02 |

**Key insight:** This phase is a textbook "compose existing building blocks" phase — every piece of new logic is a thin orchestration layer over already-tested, already-`@Retryable`/idempotent components. The main risk is not technical complexity but scope precision (see Summary's ENRICH-01 correction) and executor isolation (Pitfall 2).

## Common Pitfalls

### Pitfall 1: `.claude/api-contracts.md` overstates the current Retry Policy
**What goes wrong:** A planner trusts the doc's claim that `WikipediaClient` is `@Retryable` and skips designing explicit resilience for the new retry path, assuming Spring will silently retry transient Wikipedia failures.
**Why it happens:** `.claude/api-contracts.md` line 102-103 states `@Retryable(...)` "Applies to: TMDB client, OMDB client, Wikipedia client" — this is stale; the code was never updated to match (or the doc was aspirational from the start).
**How to avoid:** Treat `WikipediaClient.fetch()` as *not* retried by Spring. Its own internal per-candidate try/catch is the only resilience it has. The new batch-reload's cooldown (30 days) is the actual "retry" mechanism at the system level — a film that fails today gets retried on the *next* batch-reload run outside the cooldown window, not immediately.
**Warning signs:** A plan task that says "confirm WikipediaClient's existing @Retryable config" — there is nothing to confirm; there is no such annotation.

### Pitfall 2: Reusing `enrichmentExecutor` for the batch job risks starving live saves
**What goes wrong:** A 630-film batch-reload run occupies one `@Async` thread for up to ~10.5 minutes (D-07's own estimate). If `enrichmentExecutor` (`core=2/max=5`) is reused and a user saves a new film mid-run, the new film's `enrich()` call competes for the same bounded pool.
**Why it happens:** CONTEXT.md explicitly leaves this open ("reuse the same executor bean... or confirm whether a separate bounded executor is warranted... planner's call").
**How to avoid:** Add a dedicated `wikiReloadExecutor` bean (`core=1/max=1/queue=1`, see Pattern 6). This also naturally prevents two overlapping batch-reload runs for the same or different users from both pacing simultaneously (which would defeat the point of pacing if both hit Wikipedia at the same 1s cadence from two threads).
**Warning signs:** Load-testing shows `/movies/save` enrichment latency spikes during a batch-reload run.

### Pitfall 3: Missing the original-save-flow timestamp update
**What goes wrong:** Only `WikiReloadService.retryWikipedia()` sets `wikiLastAttemptedAt`; `EnrichmentService.enrich()`'s existing Wikipedia step (lines 96-112) is left untouched. Every film saved *after* this phase ships has `wiki_last_attempted_at = NULL` even though a Wikipedia attempt already happened at save time — the batch-reload's cooldown logic then treats it as "never attempted" and retries it immediately on the very next run, even though it was just tried seconds ago.
**Why it happens:** CONTEXT.md's code-context section frames the Wikipedia-step extraction as being *for the new retry method*, which reads as "leave the original untouched."
**How to avoid:** Modify `EnrichmentService.enrich()` Step 3 to also call `movie.setWikiLastAttemptedAt(Instant.now())` in both the success path and both catch blocks (`WikipediaNotFoundException` and generic `Exception`) — see Success Criterion 1's exact wording: "Every Wikipedia enrichment attempt (success or failure)."
**Warning signs:** Integration test for `EnrichmentIntegrationTest.shouldSaveWithSuccess_whenWikipediaFails` (existing test, will need updating) doesn't assert on `wikiLastAttemptedAt` — a plan that doesn't touch this test file is a signal the timestamp-on-original-path requirement was missed.

### Pitfall 4: `Thread.sleep` inside a `@Transactional` method holds a DB connection/transaction open
**What goes wrong:** If `retryWikipedia()` is `@Transactional` (needed so `movieRepository.save()` + the wiki fields persist atomically) and is called in a loop from `batchReload()`, each iteration's transaction is scoped correctly *per movie* (transaction opens and closes inside `retryWikipedia()`, not around the whole loop) — but if a developer instead makes `batchReload()` itself `@Transactional`, the entire ~10-minute run (with `Thread.sleep` between every movie) holds one long-lived transaction and a pooled DB connection for the whole duration.
**Why it happens:** `EnrichmentService.enrich()` is `@Transactional` at the top level (single movie, single external call sequence, short duration) — copying that pattern onto the batch orchestrator without noticing the loop+sleep changes the duration profile by orders of magnitude.
**How to avoid:** `@Transactional` belongs on `retryWikipedia(Movie)` (per-movie, short), never on `batchReload(UUID userId)` (per-batch, long, contains `Thread.sleep`). Pattern 5's code example reflects this.
**Warning signs:** Connection pool exhaustion warnings in logs during a large batch-reload run.

### Pitfall 5: Testing `Thread.sleep(1000ms default)` naively makes the test suite slow
**What goes wrong:** An integration test that runs `batchReload()` against N eligible movies with the real 1000ms default pacing delay takes N seconds — multiplying test suite runtime, especially in CI.
**Why it happens:** The default `wiki.retry.pacing-delay-ms=1000` is tuned for the production incident (avoid Wikipedia rate limiting), not for fast tests.
**How to avoid:** Override `wiki.retry.pacing-delay-ms` to a small value (e.g. `1` or `0`) via `@DynamicPropertySource` or `application-test.properties` in the new integration test class, mirroring how `EnrichmentIntegrationTest`/`WikipediaClientTest` already override `wikipedia.base-url` via `@DynamicPropertySource` `[VERIFIED: backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java:31-36]`.
**Warning signs:** CI backend test job duration increases noticeably after this phase's tests are added.

## Code Examples

See **Architecture Patterns** section above (Patterns 1-6) — every example is either a verbatim quote from an existing file (tagged `[VERIFIED: path:lines]`) or new code following that verified file's exact structural conventions. No separate examples section is needed; duplicating them here would only paraphrase already-precise, already-cited code.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Wikipedia failure = permanent silent skip (D-15, Phase 3) | Wikipedia failure = timestamped, cooldown-eligible for automatic future retry | This phase (v1.1) | Films that failed due to transient rate-limiting (the 630-film incident) become recoverable without a full re-import |

**Deprecated/outdated:** None — this phase adds capability, it does not replace or deprecate anything from Phase 3-4's original design.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Property key names `wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` (and their ENV equivalents `WIKI_RETRY_COOLDOWN_DAYS`/`WIKI_RETRY_PACING_DELAY_MS`) are the right shape — explicitly left as "suggestions, not locked" by CONTEXT.md | Standard Stack, Pattern 5/6 | None — CONTEXT.md already flags these as Claude's discretion; any consistent dotted-key name following the `jwt.secret`/`encryption.master-key` convention satisfies the requirement |
| A2 | A dedicated `wikiReloadExecutor` bean (`core=1/max=1/queue=1`) is the right sizing, rather than reusing `enrichmentExecutor` | Pitfall 2, Pattern 6 | Low — if wrong, the fallback (reuse `enrichmentExecutor`) still functionally works for a single-user app; only a resource-contention edge case is affected, not correctness |
| A3 | `retryWikipedia()` should be `@Transactional` per-movie rather than the whole batch — inferred from `EnrichmentService.enrich()`'s existing `@Transactional` placement and the loop+sleep duration concern, not explicitly stated in CONTEXT.md | Pitfall 4 | Medium if wrong — a long-held transaction risks connection pool exhaustion during a large reload run, though this personal single-user app's typical batch sizes (dozens, not 630, after the initial backlog is cleared) make it unlikely to manifest immediately |

**If this table is empty:** N/A — see entries above; all three are low/medium-risk implementation-detail assumptions explicitly within CONTEXT.md's "Claude's Discretion" scope, not decisions requiring new user confirmation.

## Open Questions

1. **Should `retryWikipedia()` skip movies whose `status` is not `SUCCESS`?**
   - What we know: D-01 says batch-reload "retries only the Wikipedia step... TMDB/OMDB data and the movie's `status` (SUCCESS/ERROR from the original save) are left untouched." A movie with `status=ERROR` means the *TMDB* fetch failed (per `EnrichmentService.enrich()`'s catch-all `[VERIFIED: backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:131-136]`, which sets `MovieStatus.ERROR` only on the outer catch, i.e. TMDB/unexpected failure) — such a movie likely has no `title`/`originalTitle`/`releaseDate` populated, so `wikipediaClient.fetch()` would receive blank/null-derived arguments.
   - What's unclear: CONTEXT.md doesn't explicitly say whether the eligibility query (`wiki_url IS NULL AND ...`) should also filter `status = 'SUCCESS'`, or whether `ERROR`-status movies (which also have `wiki_url IS NULL` by construction, since they never reached Step 3) would incorrectly show up as "eligible" and burn a Wikipedia call on a title-less lookup.
   - Recommendation: Add `AND m.status = 'SUCCESS'` (or `m.status = de.moviearchive.movie.MovieStatus.SUCCESS`) to `findEligibleForWikiReload` — an `ERROR`-status movie already failed at the mandatory TMDB step and has no reliable title/year to look up on Wikipedia; retrying it would waste a paced Wikipedia call on data that's fundamentally incomplete. Flag this filter choice for confirmation during planning/discussion if not already implicitly covered by CONTEXT.md's D-01 "status is left untouched" framing.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| PostgreSQL (via Testcontainers `postgres:16-alpine`) | `movies` table, new `V8` migration, repository tests | ✓ (Testcontainers-managed, no local install needed) | 16-alpine `[VERIFIED: backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java pattern + CLAUDE.md]` | — |
| OpenSearch 2.x (via Testcontainers `opensearchproject/opensearch:2.19.0`) | `IndexingService.index()` re-index step (D-02) | ✓ (Testcontainers-managed) `[VERIFIED: backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java:27]` | 2.19.0 | — |
| WireMock | Stubbing Wikipedia calls in the new `WikiReloadService`/`WikiReloadController` tests | ✓ (`AbstractWireMockTest` base class already exists) `[VERIFIED: backend/src/test/java/de/moviearchive/AbstractWireMockTest.java]` | dynamic port via `WireMockExtension` | — |
| Live Wikipedia API (`en.wikipedia.org`) | Production runtime only — never touched in tests (all mocked, per CLAUDE.md's "External APIs are always mocked in tests" rule) | N/A for this phase's tests | — | — |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** none — everything needed is already present in the test/build infrastructure.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ + Testcontainers + WireMock (all already on the backend test classpath, confirmed via existing test files read) |
| Config file | `backend/build.gradle.kts` (test dependencies), no separate JUnit config file found |
| Quick run command | `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceTest"` (unit, Mockito-only, once created) |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| ENRICH-01 (original save-flow path) | `EnrichmentService.enrich()` sets `wikiLastAttemptedAt` on Wikipedia success and failure | unit/integration | `./gradlew test --tests "de.moviearchive.movie.EnrichmentServiceTest"` and `EnrichmentIntegrationTest` (both existing files, extend with new assertions) | ✅ exists, needs new test methods |
| ENRICH-01 (batch-reload path) | `WikiReloadService.retryWikipedia()` sets `wikiLastAttemptedAt` on both success and failure | unit | `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceTest"` (Mockito-mocked `WikipediaClient`, style copied from `EnrichmentServiceTest`) | ❌ Wave 0 — new file |
| ENRICH-02 | Eligibility query returns only `wiki_url IS NULL` movies outside cooldown (or never attempted); recently-failed movies within cooldown are excluded | unit (repository query via `@DataJpaTest` or covered transitively by integration test) + integration (full endpoint flow with WireMock + Testcontainers Postgres, style copied from `ReindexControllerTest`) | `./gradlew test --tests "de.moviearchive.admin.WikiReloadControllerTest"` | ❌ Wave 0 — new file |
| ENRICH-03 | Batch-reload paces requests — a delay occurs between consecutive Wikipedia calls; a full run does not fire near-simultaneous requests | integration, asserting on WireMock's request timing/count via `wireMock.verify(...)` with a reduced test-only `pacing-delay-ms` (see Pitfall 5) | `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceIntegrationTest"` | ❌ Wave 0 — new file |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceTest"` (fast, Mockito-only)
- **Per wave merge:** `./gradlew test` (full suite — includes Testcontainers Postgres/OpenSearch + WireMock integration tests)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` — Mockito unit test covering ENRICH-01 (both success/failure timestamp paths) and the per-movie exception isolation in `batchReload()`
- [ ] `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java` (or fold into `EnrichmentIntegrationTest`-style test in the same package) — covers ENRICH-02 eligibility filtering + ENRICH-03 pacing, extending `AbstractWireMockTest` + `AbstractOpenSearchTest` (needs both — Postgres for eligibility query, OpenSearch for D-02 re-index verification, WireMock for Wikipedia)
- [ ] `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` — MockMvc controller test covering the 403 ownership check (style copied verbatim from `ReindexControllerTest.shouldReturn403_whenUserMismatch`)
- [ ] Extend existing `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` and `EnrichmentIntegrationTest.java` with `wikiLastAttemptedAt` assertions (Pitfall 3)
- [ ] No new test framework/config install needed — all infrastructure (`AbstractWireMockTest`, `AbstractOpenSearchTest`, Testcontainers Postgres base) already exists and is reused as-is

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | yes | Existing JWT bearer auth (`JwtAuthFilter`), unchanged — new endpoint inherits `anyRequest().authenticated()` |
| V3 Session Management | no | Stateless JWT, no session state introduced by this phase |
| V4 Access Control | yes | Per-resource ownership check (`assertOwnership`, IDOR protection) — same pattern as `ReindexController`'s existing D-03 protection, duplicated into `WikiReloadController` |
| V5 Input Validation | yes | `@PathVariable UUID userId` — Spring's type coercion rejects non-UUID path segments with 400 before the handler runs, same as `ReindexController` |
| V6 Cryptography | no | No new secrets/encryption surface — this phase touches no API keys or tokens |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| IDOR — triggering another user's wiki-reload via `/admin/wiki-reload/{otherUserId}` | Elevation of Privilege | `assertOwnership()` check (403 on JWT-subject/path-userId mismatch), copied from `ReindexController`'s existing, already-tested pattern `[VERIFIED: backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java:151-169]` |
| Resource exhaustion — repeatedly triggering batch-reload to keep threads/DB connections busy | Denial of Service | Dedicated `wikiReloadExecutor` with `queueCapacity=1` (Pattern 6) — a third concurrent trigger is rejected by the bounded queue rather than unboundedly queuing; this is a defense-in-depth improvement over the CONTEXT.md-described minimum, not a strict requirement of ENRICH-01..03 |
| SSRF via Wikipedia base URL | Tampering | Not new — `wikipedia.base-url` is a fixed `application.properties` value (`https://en.wikipedia.org` default), not user-controllable per request; this phase reuses `WikipediaClient` unchanged |

## Sources

### Primary (HIGH confidence — direct file reads this session)
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — full file read, Wikipedia step lines 96-112, indexing step lines 119-129, self-invocation comment line 47
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — full file read, confirmed no `@Retryable`
- `backend/src/main/java/de/moviearchive/admin/ReindexController.java` — full file read, structural analog
- `backend/src/main/java/de/moviearchive/indexing/IndexingService.java` — full file read, `index()`/`reindexPending()` patterns
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — full file read, query style precedent
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — full file read, entity field style
- `backend/src/main/java/de/moviearchive/movie/MovieStatus.java` — full file read, enum values `PENDING`/`SUCCESS`/`ERROR`
- `backend/src/main/java/de/moviearchive/movie/MovieController.java` — full file read, `enrich()` call site confirming cross-bean invocation
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` — full file read, executor sizing
- `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` — full file read, no admin-role rule
- `backend/src/main/resources/application.properties` — full file read, config key conventions
- `backend/src/main/resources/db/migration/V6__create_movies.sql`, `V7__add_personal_fields_to_movies.sql` — full read, `V7` confirmed as highest existing migration
- `backend/build.gradle.kts` (grepped) — Spring Boot 3.5.0, Java 25, `spring-retry`/`spring-aspects` deps
- `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java`, `EnrichmentIntegrationTest.java`, `WikipediaClientTest.java` — full/partial reads, test conventions
- `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — full read, controller test conventions incl. 403 ownership test
- `backend/src/test/java/de/moviearchive/AbstractWireMockTest.java`, `AbstractOpenSearchTest.java` — full reads, test base class infrastructure
- `.planning/phases/08-wiki-enrichment-tracking-batch-reload/08-CONTEXT.md`, `.planning/REQUIREMENTS.md`, `.planning/STATE.md`, `.planning/PROJECT.md` (grepped for incident detail) — phase scope and requirements

### Secondary (MEDIUM confidence)
- `.claude/api-contracts.md` §Wikipedia API, §Retry Policy — used for the 6-step fallback description (accurate) but flagged as **stale** for the Wikipedia-client-is-`@Retryable` claim (Pitfall 1)
- `.claude/data-model.md` — `movies` table field list and OpenSearch mapping fields (`wikipedia_summary`/`wikipedia_plot`/`wikipedia_critics`), cross-checked against `DocumentBuilder.java` grep (lines 227-233) which confirmed exact field name mapping

### Tertiary (LOW confidence)
- WebSearch on "Spring @Async Thread.sleep pacing external API calls" — returned only generic `@Async` tutorials, none specific to sequential rate-limit pacing; not used as a basis for any claim, included here only to document that the search was attempted and yielded no additional authoritative guidance beyond what the codebase's own conventions already establish

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all versions confirmed by direct `build.gradle.kts`/`application.properties` reads
- Architecture: HIGH — every pattern is either a verbatim-quoted existing file or new code following that file's exact conventions
- Pitfalls: HIGH for Pitfalls 1-3 (directly sourced from reading the actual code and comparing against CONTEXT.md/docs claims); MEDIUM for Pitfalls 4-5 (reasoned from Spring transaction/testing conventions, not drawn from an existing in-repo precedent since no prior phase has a long-running paced loop)

**Research date:** 2026-08-22
**Valid until:** 30 days (stable internal codebase, no external API version drift risk for this phase's scope)
