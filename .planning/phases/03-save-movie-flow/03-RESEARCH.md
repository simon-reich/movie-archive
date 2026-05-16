# Phase 3: Save Movie Flow — Research

**Researched:** 2026-05-16
**Domain:** Async enrichment pipeline (Spring @Async + @Retryable + WebClient), polling-based status UX (Nuxt 3 + composable), Flyway migration, TMDB/OMDB/Wikipedia API integration
**Confidence:** HIGH — all findings grounded in codebase inspection and locked project documentation

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Save flow returns 202 Accepted immediately. Async enrichment via `@Async` + `@Retryable`.
- **D-02:** Dedicated `/add` page for film search UI. No modal.
- **D-03:** TMDB search: submit-and-show poster grid. No autocomplete/typeahead.
- **D-04:** No direct TMDB ID input. Search-only for v1. (Deviation from SAVE-01 wording — accepted.)
- **D-05:** Poster grid: poster image + title + year per cell. Click poster triggers save.
- **D-06:** Clicking poster shows spinner overlay on that poster inline. Poster stays in grid.
- **D-07:** Frontend polls `GET /movies/{id}/status` every 2-3s until terminal state (SUCCESS or ERROR). Movie UUID returned in 202 body.
- **D-08:** On SUCCESS: green checkmark, then poster removed from grid.
- **D-09:** On ERROR: red X / error icon on poster + brief tooltip. Poster stays for retry.
- **D-10:** No archive/list page in Phase 3. Films visible starting Phase 5.
- **D-11:** Async pipeline: TMDB fetch → OMDB (optional) → Wikipedia (6-step fallback) → Postgres persist. No OpenSearch write in Phase 3.
- **D-12:** `movies` table needs `status` column (PENDING / SUCCESS / ERROR). Flyway V6 migration.
- **D-13:** `GET /movies/{id}/status` is authenticated. Returns `{ "id": "...", "status": "...", "title": "..." }`.
- **D-14:** Error responses use `{"message": "..."}` flat JSON (carry-forward from Phase 1/2).
- **D-15:** OMDB and Wikipedia failures are always silent. Only TMDB failure causes ERROR status.

### Claude's Discretion

- Exact Flyway migration version (V6, next after V5).
- Polling interval (2-3 seconds recommended; client-side backoff optional).
- Exact error message wording on poster.
- Whether TMDB search backend endpoint is `/movies/search?q=` or another naming convention.
- Whether `/add` page nav link is "+" icon or text ("Add Film") in AppNav.
- Visual design of spinner overlay (opacity, animation style).

### Deferred Ideas (OUT OF SCOPE)

- Direct TMDB ID input (Phase 6/7 power-user feature).
- Archive/list view (Phase 5).
- OpenSearch write (Phase 4).
- Dedicated "Retry" button on error poster (Phase 6/7 polish).
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SAVE-01 | User can add a film via TMDB search; returns 202 Accepted immediately | D-02–D-07 define the UX. D-04 drops TMDB ID input. POST /movies/save endpoint returns 202 with movie UUID. |
| SAVE-02 | Backend enriches async: TMDB → OMDB (optional) → Wikipedia → Postgres → OpenSearch | D-11 scopes to Postgres only (no OpenSearch in Phase 3). Pipeline orchestrated by `@Async` service method calling three `@Retryable` clients. |
| SAVE-03 | OMDB enrichment fails gracefully: no key or no imdb_id → skip silently | `SettingsService.getApiKey()` pattern already exists for key lookup. Guard: if no OMDB key OR tmdb detail response has no imdb_id → skip OMDB call entirely. |
| SAVE-04 | Wikipedia enrichment fails gracefully: 6-step fallback; film always saved | 6-step URL sequence documented in api-contracts.md. Any exhausted fallback → null wiki fields, save continues. |
| SAVE-05 | UI displays save status: pending → success / error; no silent failures | Polling endpoint `GET /movies/{id}/status` returns `{ id, status, title }`. Frontend useMovies composable drives poster state machine. |
</phase_requirements>

---

## Summary

Phase 3 implements the full save-movie pipeline: a `/add` page with TMDB search → poster grid → click-to-save interaction, backed by a Spring `@Async` enrichment chain that calls TMDB, optionally OMDB, then Wikipedia, and persists to Postgres. The frontend polls a status endpoint to update the poster from spinner to success/error in-place.

The infrastructure for this phase is already substantially in place. `@EnableAsync` and `@EnableRetry` are already declared on `MovieArchiveApplication`. WebFlux (`WebClient`) is on the classpath and the `TmdbKeyValidator` / `OmdbKeyValidator` demonstrate the correct WebClient usage pattern. The settings `SettingsService.getApiKey()` pattern provides how to retrieve the user's decrypted TMDB/OMDB keys at enrichment time. The Flyway migration sequence ends at V5 — Phase 3 adds V6 for the `movies` table.

The only new infrastructure needed is: (1) a bounded `ThreadPoolTaskExecutor` bean (not yet present — `@Async` is enabled but no custom executor bean exists), (2) the V6 Flyway migration, (3) the three API client beans (TmdbClient, OmdbClient, WikipediaClient), (4) the Movie entity + repository + service + controller, and (5) the Nuxt `/add` page with `useMovies` composable.

**Primary recommendation:** Model MovieService + MovieController directly after SettingsService + SettingsController (established pattern). Build three thin `@Retryable` WebClient beans — TmdbClient, OmdbClient, WikipediaClient — called from a single `@Async` `EnrichmentService.enrich(movieId)` method. Wire polling via `GET /movies/{id}/status` (authenticated, returns status enum + title).

---

## Standard Stack

### Core (all already in build.gradle.kts)

| Library | Version | Purpose | Status |
|---------|---------|---------|--------|
| Spring Boot Starter WebFlux | BOM-managed (Boot 3.5.0) | WebClient for TMDB/OMDB/Wikipedia calls | [VERIFIED: build.gradle.kts — `spring-boot-starter-webflux` present] |
| spring-retry | BOM-managed | `@Retryable` on API client methods | [VERIFIED: build.gradle.kts — `spring-retry` present] |
| spring-aspects | transitive via spring-retry | AOP proxy for `@Retryable` | [VERIFIED: confirmed needed by CLAUDE.md §Spring @Async+@Retryable] |
| Spring Data JPA | BOM-managed | Movie entity / repository | [VERIFIED: already used for User, UserApiKey, token entities] |
| Flyway PostgreSQL | BOM-managed | V6 migration for movies table | [VERIFIED: V5 present, migration mechanism established] |
| Lombok | BOM-managed | `@Getter/@Setter/@NoArgsConstructor/@Slf4j` | [VERIFIED: used on all entities] |
| Jackson | BOM-managed | `raw_tmdb_json` / `raw_omdb_json` JSONB serialization | [VERIFIED: ObjectMapper autowired in tests] |

### No New Dependencies Required

[VERIFIED: codebase inspection] All required libraries are already declared. Phase 3 adds no new `build.gradle.kts` entries. The only missing runtime piece is a `ThreadPoolTaskExecutor` bean — this is a configuration addition, not a dependency.

---

## Architecture Patterns

### Recommended Package Structure

```
backend/src/main/java/de/moviearchive/
├── movie/
│   ├── Movie.java                   # JPA entity: movies table
│   ├── MovieRepository.java         # Spring Data JPA
│   ├── MovieStatus.java             # Enum: PENDING, SUCCESS, ERROR
│   ├── MovieService.java            # save(), getStatus(), findByIdAndUserId()
│   ├── MovieController.java         # POST /movies/save, GET /movies/search, GET /movies/{id}/status
│   └── dto/
│       ├── SaveMovieRequest.java    # { tmdbId: int } (after TMDB search result click)
│       ├── MovieStatusResponse.java # { id, status, title }
│       └── TmdbSearchResultItem.java # { tmdbId, title, year, posterPath }
├── enrichment/
│   ├── EnrichmentService.java       # @Async orchestrator: calls clients in order
│   ├── TmdbClient.java              # @Retryable TMDB API calls
│   ├── OmdbClient.java              # @Retryable OMDB API calls
│   └── WikipediaClient.java         # @Retryable Wikipedia 6-step fallback
└── config/
    └── SecurityConfig.java          # Add /movies/** to authenticated routes (already default)
```

```
frontend/
├── pages/
│   └── add.vue                     # /add page: search form + poster grid
├── composables/
│   └── useMovies.ts                # searchTmdb(), saveMovie(), pollStatus()
└── test/
    ├── unit/
    │   └── composables/
    │       └── useMovies.spec.ts
    ├── pages/
    │   └── add.spec.ts
    └── mocks/handlers/
        └── movies.ts               # MSW handlers for /api/movies/*
```

### Pattern 1: ThreadPoolTaskExecutor Bean (NEW — not yet present)

`@EnableAsync` is declared but no custom executor bean exists. Without one, Spring uses `SimpleAsyncTaskExecutor` (unbounded threads). Must add:

```java
// Source: CLAUDE.md §Spring @Async+@Retryable — thread pool configuration
@Configuration
public class AsyncConfig {
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
}
```

Use `@Async("enrichmentExecutor")` on the enrichment method.

### Pattern 2: @Async Orchestrator + @Retryable Clients (Separation of Concerns)

```java
// Source: CLAUDE.md §Spring @Async+@Retryable — Do NOT apply @Retryable to the @Async method itself
@Service
public class EnrichmentService {

    @Async("enrichmentExecutor")
    public void enrich(UUID movieId) {
        Movie movie = movieRepository.findById(movieId).orElseThrow();
        try {
            // 1. TMDB detail fetch (mandatory)
            TmdbDetail detail = tmdbClient.fetchDetail(movie.getTmdbId(), tmdbKey);
            movie.setRawTmdbJson(objectMapper.valueToTree(detail));
            movie.setTitle(detail.getTitle());
            movie.setOriginalTitle(detail.getOriginalTitle());
            movie.setImdbId(detail.getExternalIds().getImdbId());
            movie.setReleaseDate(detail.getReleaseDate());
            movie.setRuntime(detail.getRuntime());

            // 2. OMDB (optional — skip if no key or no imdb_id)
            String omdbKey = settingsService.getDecryptedKey(movie.getUserId(), OMDB);
            if (omdbKey != null && movie.getImdbId() != null) {
                try {
                    OmdbResponse omdb = omdbClient.fetch(movie.getImdbId(), omdbKey);
                    movie.setRawOmdbJson(objectMapper.valueToTree(omdb));
                } catch (Exception e) {
                    log.warn("OMDB enrichment failed for movieId={} — continuing without OMDB data", movieId);
                }
            }

            // 3. Wikipedia 6-step fallback
            try {
                WikipediaResult wiki = wikipediaClient.fetch(movie.getOriginalTitle(), movie.getTitle(), releaseYear);
                movie.setWikiPlot(wiki.getPlot());
                movie.setWikiSummary(wiki.getSummary());
                movie.setWikiCritics(wiki.getCritics());
                movie.setWikiUrl(wiki.getUrl());
            } catch (Exception e) {
                log.warn("Wikipedia enrichment failed for movieId={} — continuing without wiki data", movieId);
            }

            movie.setStatus(MovieStatus.SUCCESS);
        } catch (Exception e) {
            log.error("TMDB enrichment failed for movieId={}", movieId, e);
            movie.setStatus(MovieStatus.ERROR);
        }
        movieRepository.save(movie);
    }
}
```

### Pattern 3: @Retryable on Client Methods (Not on @Async)

```java
// Source: CLAUDE.md §Spring @Async+@Retryable — retry on client layer only
@Component
@Slf4j
public class TmdbClient {
    private final WebClient webClient;

    public TmdbClient(WebClient.Builder builder,
                      @Value("${tmdb.base-url:https://api.themoviedb.org}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public TmdbSearchResponse search(String query, String apiKey) {
        return webClient.get()
                .uri("/3/search/movie?query={q}&api_key={key}&language=en-US", query, apiKey)
                .retrieve()
                .bodyToMono(TmdbSearchResponse.class)
                .block();
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public TmdbDetail fetchDetail(int tmdbId, String apiKey) {
        return webClient.get()
                .uri("/3/movie/{id}?api_key={key}&language=en-US&append_to_response=credits,keywords,videos,images,release_dates,external_ids",
                        tmdbId, apiKey)
                .retrieve()
                .bodyToMono(TmdbDetail.class)
                .block();
    }
}
```

OmdbClient and WikipediaClient follow the same structure. WikipediaClient implements the 6-step loop internally — tries each URL in sequence, returns on first hit, throws `WikipediaNotFoundException` after all 6 fail (caught silently in orchestrator).

### Pattern 4: Wikipedia 6-Step Fallback Implementation

```java
// Source: CLAUDE.md §Wikipedia 6-step fallback + api-contracts.md
// Fallback order (EXACT — do not reorder):
// 1. {OriginalTitle}_{Year}_film
// 2. {OriginalTitle}_(film)
// 3. {OriginalTitle}
// 4. {Title}_{Year}_film
// 5. {Title}_(film)
// 6. {Title}

@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
public WikipediaResult fetch(String originalTitle, String title, int year) {
    List<String> candidates = List.of(
        originalTitle + "_" + year + "_film",
        originalTitle + "_(film)",
        originalTitle,
        title + "_" + year + "_film",
        title + "_(film)",
        title
    );
    for (String candidate : candidates) {
        Optional<WikipediaResult> result = tryFetch(candidate);
        if (result.isPresent()) return result.get();
    }
    throw new WikipediaNotFoundException("No Wikipedia page found after 6 attempts");
}
```

Wikipedia API base: `https://en.wikipedia.org/w/api.php` with `User-Agent: MovieArchive/0.1` header.

### Pattern 5: POST /movies/save Controller Endpoint

```java
// POST /movies/save — returns 202 with { "id": "uuid" }
@PostMapping("/save")
public ResponseEntity<Map<String, String>> saveMovie(
        @Valid @RequestBody SaveMovieRequest req,
        Authentication auth) {
    UUID movieId = movieService.initiate(auth.getName(), req.tmdbId());
    enrichmentService.enrich(movieId);  // fires async, returns immediately
    return ResponseEntity.accepted().body(Map.of("id", movieId.toString()));
}
```

`MovieService.initiate()` creates the Movie row with `status = PENDING` and returns the UUID. The `@Async` enrich call is non-blocking from the HTTP thread's perspective.

### Pattern 6: GET /movies/search Proxy Endpoint

The frontend does NOT call TMDB directly — it calls the backend, which uses the user's stored TMDB key.

```java
// GET /movies/search?q={query}
@GetMapping("/search")
public ResponseEntity<List<TmdbSearchResultItem>> search(
        @RequestParam String q,
        Authentication auth) {
    String tmdbKey = settingsService.getDecryptedKey(auth.getName(), TMDB);
    if (tmdbKey == null) {
        return ResponseEntity.status(422).build(); // or body with message
    }
    List<TmdbSearchResultItem> results = tmdbClient.search(q, tmdbKey);
    return ResponseEntity.ok(results);
}
```

Recommended endpoint name: `GET /movies/search?q=` (aligns with Spring MVC convention, consistent with TMDB's `query` parameter name mapped to shorter `q`).

### Pattern 7: Flyway V6 Migration (movies table)

```sql
-- V6__create_movies.sql
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

Key design points:
- `status` as `VARCHAR(10)` with CHECK constraint (not enum type) — avoids Postgres ALTER TYPE issues on status changes.
- `UNIQUE (user_id, tmdb_id)` — prevents duplicate saves of the same film by the same user.
- `indexed_at = null` means not yet indexed (Phase 4 will set this).
- `status` defaults to PENDING — inserted row is immediately queryable.

### Pattern 8: Frontend useMovies Composable

Following `useSettings.ts` pattern exactly:

```typescript
// frontend/composables/useMovies.ts
export function useMovies() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  async function searchTmdb(query: string): Promise<TmdbSearchResult[]> {
    return await $fetch<TmdbSearchResult[]>(`/api/movies/search?q=${encodeURIComponent(query)}`, {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function saveMovie(tmdbId: number): Promise<{ id: string }> {
    return await $fetch<{ id: string }>('/api/movies/save', {
      method: 'POST',
      body: { tmdbId },
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function getStatus(movieId: string): Promise<MovieStatusResponse> {
    return await $fetch<MovieStatusResponse>(`/api/movies/${movieId}/status`, {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  return { searchTmdb, saveMovie, getStatus }
}
```

### Pattern 9: Poster State Machine (add.vue)

Each poster in the result grid tracks its own state locally:

```typescript
type PosterState = 'idle' | 'pending' | 'success' | 'error'

interface SearchResult {
  tmdbId: number
  title: string
  year: number
  posterPath: string | null
  state: PosterState
  movieId?: string
  errorMessage?: string
}
```

On click: set `state = 'pending'`, call `saveMovie(tmdbId)`, receive `{ id }`, begin polling loop. Poll `getStatus(id)` every 2-3 seconds. On SUCCESS: set `state = 'success'`, after brief delay remove from results array. On ERROR: set `state = 'error'`, set `errorMessage`.

### Anti-Patterns to Avoid

- **`@Retryable` on `@Async` method:** Retry wraps the async submission, not async execution — retry never fires. Put `@Retryable` only on the individual client methods (TmdbClient, OmdbClient, WikipediaClient).
- **Self-invoking `@Async` or `@Retryable` from same class:** Spring proxy is bypassed — always call from a different bean.
- **`SimpleAsyncTaskExecutor` (Spring default):** Creates unlimited threads. Must define a bounded `ThreadPoolTaskExecutor` bean — not yet present in the project.
- **Calling TMDB directly from frontend:** Frontend must go through `/api/movies/search?q=` which uses the user's stored encrypted key. No TMDB key on the frontend.
- **`RestTemplate` for client calls:** In maintenance mode. The project already uses WebClient (confirmed by TmdbKeyValidator).
- **Reusing same IV for AES-GCM:** Not directly Phase 3, but Phase 3 reads encrypted keys using existing `EncryptionService.decrypt()` — do not re-implement.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Async execution | Custom Thread management | Spring `@Async` + `ThreadPoolTaskExecutor` | [VERIFIED: already enabled via @EnableAsync] |
| Retry with backoff | Manual retry loops | `@Retryable` + `@Backoff` | [VERIFIED: spring-retry on classpath] |
| External HTTP calls | Raw `HttpURLConnection` | `WebClient` | [VERIFIED: webflux on classpath, TmdbKeyValidator pattern] |
| Key decryption for enrichment | Duplicate decrypt logic | `SettingsService.getApiKeys()` / `EncryptionService.decrypt()` | [VERIFIED: EncryptionService bean already exists] |
| Status polling | SSE / WebSocket | Simple `setInterval` polling every 2-3s | [VERIFIED: explicitly chosen in CONTEXT.md D-07; SSE rejected as overkill] |
| Postgres JSONB storage | Custom serialization | JPA `@Column(columnDefinition = "jsonb")` + Jackson `JsonNode` | [ASSUMED: standard pattern for Spring + Postgres JSONB; consistent with data-model.md design] |

---

## Common Pitfalls

### Pitfall 1: @Async Not Truly Async (Same-Bean Self-Invocation)
**What goes wrong:** If `MovieController` calls a method annotated `@Async` on itself, or if `EnrichmentService` calls its own `@Async` method, Spring's proxy is bypassed and the call runs synchronously on the HTTP thread.
**Why it happens:** Spring AOP wraps beans in proxies; self-invocation skips the proxy.
**How to avoid:** `MovieController` calls `enrichmentService.enrich()` where `EnrichmentService` is a separate injected bean. `@Async` on `EnrichmentService` method, not on `MovieService` or `MovieController`.
**Warning signs:** HTTP request hangs waiting for enrichment; no `enrich-X` thread names in logs.

### Pitfall 2: No Bounded Thread Pool
**What goes wrong:** Without a `ThreadPoolTaskExecutor` bean, Spring uses `SimpleAsyncTaskExecutor` which spawns a new thread per task — unlimited threads under load.
**Why it happens:** `@EnableAsync` defaults to `SimpleAsyncTaskExecutor` when no `Executor` bean is present.
**How to avoid:** Add `AsyncConfig` bean with `ThreadPoolTaskExecutor` named `"enrichmentExecutor"`. Use `@Async("enrichmentExecutor")`. **This bean does not yet exist in the project** — it is Wave 0 work for Phase 3.
**Warning signs:** No thread pool warnings at startup; `Thread[SimpleAsync...]` in stack traces.

### Pitfall 3: OMDB Skip Logic Must Be Checked in Two Places
**What goes wrong:** OMDB must be skipped when (a) no OMDB key is configured OR (b) TMDB detail response has no `imdb_id`. Checking only one condition leaks NullPointerExceptions or unnecessary API calls.
**Why it happens:** TMDB's `imdb_id` comes from `external_ids.imdb_id` in the `append_to_response`; it can be null even on valid responses.
**How to avoid:** In `EnrichmentService.enrich()`, after TMDB detail fetch: `if (omdbKey != null && movie.getImdbId() != null)` — both guards required.
**Warning signs:** NullPointerException in OMDB client when `imdb_id` is null; or OMDB calls with empty `i=` parameter returning 400.

### Pitfall 4: Wikipedia Titles Must Be URL-Encoded
**What goes wrong:** Film titles with spaces, apostrophes, colons, or non-ASCII characters break the Wikipedia API lookup if not encoded.
**Why it happens:** Wikipedia API uses URL path parameters for page titles. Spaces must be `_` (underscore), not `%20` or raw space.
**How to avoid:** Replace spaces with `_` in the candidate title string before building the Wikipedia URL. Use `UriComponentsBuilder` or manual string replace — NOT `URLEncoder.encode()` which uses `+` for spaces.
**Warning signs:** Wikipedia returns 400 or "page not found" for films with multi-word titles.

### Pitfall 5: Duplicate Save (Same User + Same TMDB Film)
**What goes wrong:** User clicks a poster, sees spinner, clicks again — two rows inserted for the same film.
**Why it happens:** No database constraint prevents it, and the async gap means the first PENDING row isn't SUCCESS yet when the second click fires.
**How to avoid:** `UNIQUE (user_id, tmdb_id)` constraint in V6 migration. `MovieService.initiate()` catches `DataIntegrityViolationException` and returns the existing movie's UUID (idempotent save).
**Warning signs:** Duplicate films in archive; constraint violation in logs.

### Pitfall 6: Polling Loop Not Cleaned Up on Component Unmount
**What goes wrong:** If the user navigates away from `/add` while a save is in-flight, the polling `setInterval` continues, causing background fetch errors and potential memory leaks.
**Why it happens:** JavaScript timers are not automatically cleared when Vue components unmount.
**How to avoid:** Track all active polling intervals in a `Map<string, NodeJS.Timeout>`. In `onUnmounted()` hook, clear all intervals. Each polling loop also self-clears when it reaches SUCCESS or ERROR state.
**Warning signs:** Network requests to `/api/movies/{id}/status` continue after leaving `/add` page in browser DevTools.

### Pitfall 7: TMDB Search Endpoint Needs User's TMDB Key
**What goes wrong:** Treating TMDB search as a public pass-through — the backend must attach the user's stored TMDB key to every TMDB request.
**Why it happens:** TMDB requires per-request authentication; the user's key is stored encrypted in `user_api_keys`.
**How to avoid:** `GET /movies/search?q=` backend endpoint resolves the authenticated user's TMDB key via `SettingsService`, decrypts it, then makes the TMDB call. Return 422 if no key is configured (user hasn't set up TMDB key yet).
**Warning signs:** 401 from TMDB in WireMock tests; search works without stored key in tests using hardcoded key.

---

## Code Examples

### Existing WebClient Pattern (TmdbKeyValidator — reuse as template for TmdbClient)

```java
// Source: backend/src/main/java/de/moviearchive/settings/TmdbKeyValidator.java
public TmdbKeyValidator(WebClient.Builder builder,
                        @Value("${tmdb.base-url:https://api.themoviedb.org}") String baseUrl) {
    this.webClient = builder.baseUrl(baseUrl).build();
}
// Each API client (TmdbClient, OmdbClient, WikipediaClient) follows this exact constructor pattern
// with a @Value-injected base URL so tests can override it via WireMock's dynamic port
```

### Existing WireMock Override Pattern (SettingsIntegrationTest — reuse for MovieIntegrationTest)

```java
// Source: backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java
@DynamicPropertySource
static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
    registry.add("tmdb.base-url", wireMock::baseUrl);
    registry.add("omdb.base-url", wireMock::baseUrl);
    // Add for Phase 3:
    // registry.add("wikipedia.base-url", wireMock::baseUrl);
}
```

### Existing useSettings Pattern (reference for useMovies)

```typescript
// Source: frontend/composables/useSettings.ts — exact pattern to follow
const accessTokenCookie = useCookie<string | null>('access_token')
function authHeaders(): Record<string, string> {
  return accessTokenCookie.value
    ? { Authorization: `Bearer ${accessTokenCookie.value}` }
    : {}
}
// $fetch with credentials: 'include' and authHeaders() on every call
```

### Existing MSW Handler Pattern (reference for movies.ts handlers)

```typescript
// Source: frontend/test/mocks/handlers/settings.ts — pattern for movies.ts
import { http, HttpResponse } from 'msw'
export const moviesHandlers = [
  http.get('/api/movies/search', ({ request }) => {
    const url = new URL(request.url)
    const q = url.searchParams.get('q') ?? ''
    if (q === 'no-key') {
      return HttpResponse.json({ message: 'No TMDB key configured.' }, { status: 422 })
    }
    return HttpResponse.json([
      { tmdbId: 27205, title: 'Inception', year: 2010, posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg' }
    ])
  }),
  http.post('/api/movies/save', () =>
    HttpResponse.json({ id: 'test-movie-uuid-1234' }, { status: 202 })
  ),
  http.get('/api/movies/:id/status', ({ params }) =>
    HttpResponse.json({ id: params.id, status: 'SUCCESS', title: 'Inception' })
  ),
]
```

---

## State of the Art

| Old Approach | Current Approach | Notes |
|--------------|------------------|-------|
| `RestTemplate` for HTTP calls | `WebClient` (Spring WebFlux) | RestTemplate in maintenance mode; WebClient already on classpath |
| `SimpleAsyncTaskExecutor` | Named `ThreadPoolTaskExecutor` bean | Bounded pool required; must add `AsyncConfig` in Phase 3 |
| SSE / WebSocket for real-time status | Simple polling every 2-3s | Explicitly chosen in CONTEXT.md D-07 for personal single-user app |
| OpenSearch write in same pipeline step | Postgres only in Phase 3; OpenSearch in Phase 4 | Separation allows Phase 4 to own index lifecycle independently |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| PostgreSQL 16 | Flyway V6 migration, movie entity | ✓ | Testcontainers `postgres:16-alpine` in tests; Docker Compose in dev | — |
| WebFlux / WebClient | TMDB, OMDB, Wikipedia calls | ✓ | Boot 3.5.0 BOM | — |
| spring-retry | @Retryable on API clients | ✓ | Declared in build.gradle.kts | — |
| TMDB API (external) | TmdbClient | Mocked in tests (WireMock) | — | WireMock — no real calls needed |
| OMDB API (external) | OmdbClient | Mocked in tests (WireMock) | — | WireMock — no real calls needed |
| Wikipedia API (external) | WikipediaClient | Mocked in tests (WireMock) | — | WireMock — no real calls needed |

**Missing dependencies with no fallback:** None — all required libraries are present.

**Note:** `ThreadPoolTaskExecutor` bean does not yet exist. It is a configuration addition (new `AsyncConfig.java`), not a dependency install.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Backend | JUnit 5, Mockito, Testcontainers, WireMock, MockMvc — all established |
| Backend config | `AbstractIntegrationTest` + `AbstractWireMockTest` base classes exist |
| Backend quick run | `./gradlew test --tests "de.moviearchive.movie.*"` |
| Backend full suite | `./gradlew test` |
| Frontend | Vitest + Vue Test Utils + MSW — all established |
| Frontend config | `vitest.config.ts` exists |
| Frontend quick run | `pnpm test --reporter=verbose --run composables/useMovies` |
| Frontend full suite | `pnpm test --run` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | Notes |
|--------|----------|-----------|-------------------|-------|
| SAVE-01 | POST /movies/save returns 202 with movie UUID | Integration (MockMvc) | `./gradlew test --tests "*.MovieControllerTest.shouldReturn202_whenSaveInitiated"` | Wave 0: create MovieControllerTest |
| SAVE-01 | TMDB search returns poster grid items | Integration (MockMvc + WireMock) | `./gradlew test --tests "*.MovieControllerTest.shouldReturnSearchResults_whenTmdbKeyValid"` | Wave 0: MovieControllerTest |
| SAVE-01 | 422 when no TMDB key configured | Integration (MockMvc) | `./gradlew test --tests "*.MovieControllerTest.shouldReturn422_whenNoTmdbKey"` | Wave 0: MovieControllerTest |
| SAVE-02 | TMDB detail is fetched and persisted to Postgres | Integration (@SpringBootTest + WireMock) | `./gradlew test --tests "*.EnrichmentIntegrationTest.shouldPersistTmdbData_afterEnrichment"` | Wave 0: EnrichmentIntegrationTest |
| SAVE-03 | OMDB skipped when no key | Unit (EnrichmentServiceTest) | `./gradlew test --tests "*.EnrichmentServiceTest.shouldSkipOmdb_whenNoOmdbKey"` | Wave 0: EnrichmentServiceTest |
| SAVE-03 | OMDB skipped when imdb_id null | Unit (EnrichmentServiceTest) | `./gradlew test --tests "*.EnrichmentServiceTest.shouldSkipOmdb_whenImdbIdNull"` | Wave 0: EnrichmentServiceTest |
| SAVE-04 | Film saved with SUCCESS when Wikipedia exhausted | Unit (EnrichmentServiceTest) | `./gradlew test --tests "*.EnrichmentServiceTest.shouldSaveWithSuccess_whenWikipediaFails"` | Wave 0: EnrichmentServiceTest |
| SAVE-04 | Wikipedia 6-step fallback tries all candidates | Unit (WikipediaClientTest) | `./gradlew test --tests "*.WikipediaClientTest.shouldTryAllSixCandidates_beforeFailing"` | Wave 0: WikipediaClientTest |
| SAVE-05 | Status transitions PENDING → SUCCESS visible in DB | Integration (EnrichmentIntegrationTest) | `./gradlew test --tests "*.EnrichmentIntegrationTest.shouldTransitionToSuccess_afterEnrichment"` | Wave 0 |
| SAVE-05 | GET /movies/{id}/status returns correct status | Integration (MockMvc) | `./gradlew test --tests "*.MovieControllerTest.shouldReturnPendingStatus_immediately"` | Wave 0: MovieControllerTest |
| SAVE-05 | useMovies composable polls and resolves SUCCESS | FE Unit (Vitest + MSW) | `pnpm test --run composables/useMovies` | Wave 0: useMovies.spec.ts |
| SAVE-05 | /add page shows spinner → success → poster removed | FE Component (Vitest + MSW) | `pnpm test --run pages/add` | Wave 0: add.spec.ts |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "de.moviearchive.movie.*" && pnpm --prefix frontend test --run`
- **Per wave merge:** `./gradlew test && pnpm --prefix frontend test --run`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps (test files that do not yet exist)

- [ ] `backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java` — covers SAVE-01, SAVE-05
- [ ] `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java` — covers SAVE-02, SAVE-04, SAVE-05
- [ ] `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` — covers SAVE-03, SAVE-04
- [ ] `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` — covers SAVE-04
- [ ] `backend/src/test/resources/fixtures/tmdb/inception-detail.json` — WireMock fixture
- [ ] `backend/src/test/resources/fixtures/omdb/inception.json` — WireMock fixture
- [ ] `backend/src/test/resources/fixtures/wikipedia/inception-plot.json` — WireMock fixture
- [ ] `frontend/test/unit/composables/useMovies.spec.ts` — covers SAVE-05 FE
- [ ] `frontend/test/unit/pages/add.spec.ts` — covers SAVE-01, SAVE-05 FE
- [ ] `frontend/test/mocks/handlers/movies.ts` — MSW handlers for all /api/movies/* endpoints

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | All `/movies/**` endpoints require JWT (SecurityConfig default `anyRequest().authenticated()`) |
| V3 Session Management | no | Stateless JWT; no session state in Phase 3 |
| V4 Access Control | yes | `GET /movies/{id}/status` must verify movie belongs to authenticated user — prevent cross-user status leakage |
| V5 Input Validation | yes | `@Valid` on `SaveMovieRequest`; `tmdbId` must be positive integer; `q` query param must be non-blank |
| V6 Cryptography | yes (indirect) | TMDB/OMDB keys retrieved via existing `EncryptionService.decrypt()` — never re-implement crypto |

### Known Threat Patterns for This Phase

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Cross-user status read (`GET /movies/{id}/status` without ownership check) | Information Disclosure | `movieRepository.findByIdAndUserId(movieId, userId)` — always scope queries to authenticated user |
| TMDB API key leakage via search logs | Information Disclosure | `@Slf4j` log at DEBUG level only; never log the raw api_key value |
| Malformed `tmdbId` (negative, 0, non-integer) | Tampering | Bean validation: `@Positive` on `SaveMovieRequest.tmdbId` |
| Unbounded TMDB search abuse | DoS | Bucket4j rate limiting already on classpath; apply to `/movies/search` if needed (Claude's discretion for this phase) |
| Duplicate save race condition | Integrity | UNIQUE constraint on `(user_id, tmdb_id)`; catch `DataIntegrityViolationException` idempotently |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | JSONB storage for `raw_tmdb_json` / `raw_omdb_json` uses JPA `@Column(columnDefinition = "jsonb")` with Jackson `JsonNode` field type | Don't Hand-Roll | Low — alternative is String column; either works, JsonNode is cleaner |
| A2 | `SettingsService` can be injected into `EnrichmentService` to retrieve decrypted TMDB/OMDB keys | Architecture | Low — SettingsService is a Spring-managed @Service bean; injection is straightforward |
| A3 | Wikipedia API returns section content parseable via `prop=extracts&section=` or `prop=revisions` — exact response parsing strategy | Architecture | Medium — Wikipedia API shape varies; WireMock fixture must match actual response structure |

---

## Open Questions (RESOLVED)

1. **Wikipedia API response parsing strategy** — RESOLVED
   - **Chosen approach:** `action=parse&prop=sections` to discover section list, then `action=parse&prop=wikitext&section=N` for the relevant section (Plot, Critical response). WireMock fixtures in Plan 03-01 are designed around these two endpoints.
   - Rationale: Allows precise section targeting by title; `action=query&prop=extracts` returns unstructured HTML that requires additional stripping.

2. **ThreadPoolTaskExecutor placement** — RESOLVED
   - **Chosen placement:** `backend/src/main/java/de/moviearchive/config/AsyncConfig.java`
   - Rationale: Consistent with `config/SecurityConfig.java`; config/ is the established home for cross-cutting infrastructure beans.

---

## Sources

### Primary (HIGH confidence)
- `backend/build.gradle.kts` — all library versions verified by codebase inspection
- `backend/src/main/java/de/moviearchive/MovieArchiveApplication.java` — @EnableAsync + @EnableRetry confirmed present
- `backend/src/main/java/de/moviearchive/settings/TmdbKeyValidator.java` — WebClient constructor pattern verified
- `backend/src/main/java/de/moviearchive/settings/SettingsController.java` — Controller pattern verified
- `backend/src/main/java/de/moviearchive/settings/SettingsService.java` — Service pattern verified
- `backend/src/main/resources/application.properties` — ENV var names and TMDB base-url property key confirmed
- `backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java` — WireMock + DynamicPropertySource pattern verified
- `.claude/data-model.md` — movies table schema and all field names confirmed
- `.claude/api-contracts.md` — TMDB/OMDB/Wikipedia endpoints, Wikipedia 6-step fallback, retry policy confirmed
- `.claude/test-strategy.md` — test types, WireMock fixture locations, coverage targets confirmed
- `CLAUDE.md` — @Async/@Retryable patterns, WebClient vs RestTemplate, Wikipedia fallback order, all locked

### Secondary (MEDIUM confidence)
- `frontend/composables/useSettings.ts` — composable pattern (useMovies must follow exactly)
- `frontend/test/mocks/handlers/settings.ts` — MSW handler pattern for movies.ts

### Tertiary (LOW confidence — see Assumptions Log)
- Wikipedia API section parsing — exact `action=parse` parameter shape not verified against live API; must be confirmed by WireMock fixture design

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries verified in build.gradle.kts
- Architecture: HIGH — patterns directly mirror established SettingsService/SettingsController precedent
- API contracts: HIGH — TMDB/OMDB/Wikipedia fully documented in .claude/api-contracts.md
- Wikipedia parsing internals: MEDIUM — shape of section extraction not verified against live API
- Pitfalls: HIGH — @Async/@Retryable proxy traps verified against CLAUDE.md; duplicate save constraint is standard practice

**Research date:** 2026-05-16
**Valid until:** 2026-07-16 (stable stack — Spring Boot 3.5.0, no fast-moving dependencies)
