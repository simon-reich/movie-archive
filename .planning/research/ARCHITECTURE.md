# Architecture Research

**Domain:** Personal film archive web app (brownfield, Phase 1 in progress)
**Researched:** 2026-05-15
**Confidence:** HIGH

## Standard Architecture

### System Overview

```
Browser
  |
  v
Caddy (reverse proxy)
  |
  +---> /api/*  --> Spring Boot BE (port 8080)
  |                   |
  |                   +-- Spring Security Filter Chain
  |                   |     |- JwtAuthFilter (OncePerRequestFilter)
  |                   |     |- (sets SecurityContext)
  |                   |
  |                   +-- Controllers  -->  Services
  |                   |                      |
  |                   |                      +-- PostgreSQL (source of truth)
  |                   |                      +-- OpenSearch (derived, rebuildable)
  |                   |                      +-- SMTP/Mailpit (outbound)
  |                   |
  |                   +-- AsyncEnrichmentService (@Async)
  |                         |- TMDB API  (WireMock in tests)
  |                         |- OMDB API  (optional, WireMock in tests)
  |                         |- Wikipedia API  (WireMock in tests)
  |
  +---> /*      --> Nuxt 3 FE (SSR, port 3000)
                      |
                      +-- Pinia (auth store, movie store)
                      +-- useAuth composable
                      +-- Route middleware (auth guard)
```

### Component Responsibilities

| Component | Responsibility | Notes |
|-----------|----------------|-------|
| Caddy | TLS termination, routing `/api/*` vs `/*` | Profile `app` only; dev uses native ports |
| Spring Security Filter Chain | Stateless JWT validation on every request | STATELESS session policy, CSRF disabled |
| JwtAuthFilter | Extract Bearer token, validate, set SecurityContext | `OncePerRequestFilter`; skips public matchers |
| AuthController | `/auth/**` endpoints — issue/revoke tokens, email flows | Public matcher; no JWT required |
| SettingsController | `/settings/**` — API key management, email/password change | Protected; JWT required |
| MoviesController | `/movies/**` — save (202), list, detail, personal fields | Protected; JWT required |
| SearchController | `/search/**` — simple + advanced search against OpenSearch | Protected; JWT required |
| AsyncEnrichmentService | Async movie fetch chain (TMDB → OMDB → Wikipedia) | Separate bean from caller; `@Async` + `@Retryable` |
| OpenSearchIndexService | Index lifecycle — create per-user index on demand, index document, rebuild | Idempotent create: exists-check before PUT |
| PostgreSQL | Users, tokens, raw movie snapshots (JSONB) | Source of truth; OpenSearch is derived |
| OpenSearch | Full-text + faceted search, one index per user: `movies-{userId}` | Rebuilt from Postgres if corrupt |
| Nuxt FE | SSR pages, auth state, TMDB search UI, archive UI | Communicates with BE only through `/api/*` |
| Pinia auth store | In-memory JWT access token + user info | Access token NOT in localStorage (XSS); refresh token is HttpOnly cookie |
| useAuth composable | Login, logout, refresh, user state | Wraps Pinia + `$fetch` with credential forwarding |
| Route middleware | Redirect unauthenticated users to `/login` | Runs on client and server (SSR-safe via `useCookie`) |

## Recommended Project Structure

### Backend

```
backend/src/main/java/de/moviearchive/
├── config/
│   ├── SecurityConfig.java          # Filter chain, permitAll matchers, stateless
│   ├── AsyncConfig.java             # @EnableAsync, ThreadPoolTaskExecutor
│   └── OpenSearchConfig.java        # OpenSearchClient bean
├── security/
│   ├── JwtAuthFilter.java           # OncePerRequestFilter
│   ├── JwtService.java              # Sign, validate, extract claims (jjwt)
│   └── UserDetailsServiceImpl.java  # Load user by email for SecurityContext
├── auth/
│   ├── AuthController.java
│   ├── AuthService.java
│   └── dto/                         # LoginRequest, SignupRequest, TokenResponse
├── settings/
│   ├── SettingsController.java
│   ├── ApiKeyService.java           # AES-256-GCM encrypt/decrypt
│   └── dto/
├── movie/
│   ├── MovieController.java         # POST /movies/save (202), GET /movies
│   ├── MovieService.java            # Orchestrates save: persist stub → trigger async
│   ├── MovieRepository.java
│   ├── Movie.java                   # JPA entity with JSONB columns
│   └── dto/
├── enrichment/
│   ├── MovieEnrichmentService.java  # @Async entry point (separate bean)
│   ├── TmdbClient.java              # WebClient; WireMock in tests
│   ├── OmdbClient.java              # WebClient; optional; WireMock in tests
│   ├── WikipediaClient.java         # WebClient; 6-step fallback; WireMock in tests
│   └── EnrichmentErrorHandler.java  # AsyncUncaughtExceptionHandler
├── search/
│   ├── SearchController.java
│   ├── SearchService.java           # Builds OpenSearch queries
│   └── dto/                         # SearchRequest, SearchResponse
├── opensearch/
│   ├── OpenSearchIndexService.java  # Index lifecycle (create, index, rebuild)
│   └── IndexMappingProvider.java    # Custom analyzer + field mapping definitions
├── token/                           # Existing: token entities + repositories
└── user/                            # Existing: User entity + UserRepository
```

### Frontend

```
frontend/
├── composables/
│   ├── useAuth.ts          # Login, logout, refresh, reactive user state
│   ├── useMovies.ts        # Save, list, detail fetch
│   └── useSearch.ts        # Simple + advanced search
├── stores/
│   ├── auth.ts             # Pinia: accessToken (memory), user, isAuthenticated
│   └── movies.ts           # Pinia: archive list, current movie
├── middleware/
│   └── auth.ts             # Route guard: redirect to /login if not authenticated
├── pages/
│   ├── index.vue           # Archive landing / search
│   ├── login.vue
│   ├── signup.vue
│   ├── settings.vue
│   ├── movies/
│   │   └── [id].vue        # Movie detail page
│   └── auth/
│       └── verify-email.vue
├── layouts/
│   └── default.vue
└── components/
    ├── MovieCard.vue
    ├── SearchBar.vue
    └── ...
```

## Architectural Patterns

### Pattern 1: JWT Filter Chain Placement

**What:** A custom `OncePerRequestFilter` (`JwtAuthFilter`) is added to the Spring Security filter chain before `UsernamePasswordAuthenticationFilter`. It extracts the `Authorization: Bearer <token>` header, validates the JWT, and sets the `SecurityContextHolder`. Public endpoints bypass the filter via `permitAll()` matchers — the filter still runs but skips validation when no token is present (or the path is public).

**When to use:** Always — this is the standard Spring Security 6 stateless JWT pattern.

**Trade-offs:** Simple, no session state, scales horizontally. Downside: cannot revoke JWTs mid-lifetime (15 min window acceptable here).

**SecurityConfig pattern:**

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/auth/signup",
                    "/auth/login",
                    "/auth/refresh",
                    "/auth/verify-email",
                    "/auth/resend-verification",
                    "/auth/forgot-password",
                    "/auth/reset-password",
                    "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

**JwtAuthFilter pattern:**

```java
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        if (jwtService.isValid(token)) {
            String email = jwtService.extractEmail(token);
            UserDetails user = userDetailsService.loadUserByUsername(email);
            var auth = new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
```

**Note:** `/auth/refresh` is in the public matcher because the client sends the HttpOnly refresh cookie — no Bearer token needed. The endpoint validates the cookie token internally via `RefreshTokenRepository`.

### Pattern 2: Async Enrichment with @Async + @Retryable (Separate Bean)

**What:** The save flow immediately persists a minimal stub to Postgres, returns `202 Accepted`, then triggers async enrichment. The critical constraint is that `@Async` and `@Retryable` must live on a **different bean** than the caller — Spring AOP proxies are bypassed on self-invocation.

**When to use:** Whenever an async method calls another annotated method, or when the caller and the annotated method are in the same class.

**Build order implication:** `MovieService` (thin orchestrator) → `MovieEnrichmentService` (async, separate bean) → external clients.

**Pattern:**

```java
// MovieService — the caller (thin orchestrator)
@Service
public class MovieService {
    private final MovieRepository repo;
    private final MovieEnrichmentService enrichment; // injected — different bean

    public UUID save(SaveMovieRequest req, UUID userId) {
        Movie stub = new Movie(userId, req.tmdbId(), /* status=PENDING */);
        repo.save(stub);
        enrichment.enrich(stub.getId(), userId); // triggers @Async via proxy
        return stub.getId();
    }
}

// MovieEnrichmentService — separate bean, holds @Async
@Service
public class MovieEnrichmentService {

    @Async("movieEnrichmentExecutor")
    @Retryable(
        retryFor = {TransientDataAccessException.class, RestClientException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void enrich(UUID movieId, UUID userId) {
        // TMDB fetch → OMDB fetch (if key present) → Wikipedia fetch
        // → Postgres update → OpenSearch index
    }

    @Recover
    public void enrichFailed(Exception ex, UUID movieId, UUID userId) {
        // Mark movie as ENRICHMENT_FAILED in Postgres
        // Log error — no queue, no DLT in this stack
    }
}
```

**Error handling strategy:** After `maxAttempts` exhausted, `@Recover` marks the movie row with `status = ENRICHMENT_FAILED`. The movie is visible to the user as "pending" until recovered. A manual rebuild endpoint (`POST /admin/movies/{id}/reindex`) can re-trigger enrichment. No Kafka/queue infrastructure — this is intentional per CLAUDE.md.

**`AsyncUncaughtExceptionHandler`** is also registered for exceptions that escape the `@Recover` method (defensive):

```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Unhandled async exception in {}: {}", method.getName(), ex.getMessage(), ex);
    }

    @Bean("movieEnrichmentExecutor")
    public TaskExecutor movieEnrichmentExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(5);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("enrich-");
        exec.initialize();
        return exec;
    }
}
```

### Pattern 3: OpenSearch Index Lifecycle — Create on First Save, Rebuild Command

**What:** Each user gets a dedicated index `movies-{userId}`. The index is created lazily on first save. Creation is idempotent: check existence before PUT. The custom analyzer and full field mapping are applied at creation time (cannot change analyzer on live index without reindex).

**When to use:** Phase 4 (OpenSearch indexing). Must be in place before Phase 5 (search).

**Idempotent creation pattern:**

```java
@Service
public class OpenSearchIndexService {

    private final OpenSearchClient client;
    private final IndexMappingProvider mappingProvider;

    public void ensureIndexExists(UUID userId) {
        String indexName = "movies-" + userId;
        BooleanResponse exists = client.indices()
            .exists(r -> r.index(indexName));
        if (!exists.value()) {
            client.indices().create(r -> r
                .index(indexName)
                .settings(mappingProvider.settings())   // custom_english_analyzer definition
                .mappings(mappingProvider.mappings())   // all 40+ fields
            );
        }
    }

    public void indexMovie(UUID userId, MovieDocument doc) {
        ensureIndexExists(userId);
        client.index(r -> r
            .index("movies-" + userId)
            .id(doc.id().toString())
            .document(doc)
        );
    }

    public void rebuildIndex(UUID userId) {
        String indexName = "movies-" + userId;
        // 1. Delete existing index if present
        BooleanResponse exists = client.indices().exists(r -> r.index(indexName));
        if (exists.value()) {
            client.indices().delete(r -> r.index(indexName));
        }
        // 2. Recreate with current mapping (picks up any mapping changes)
        ensureIndexExists(userId);
        // 3. Bulk re-index from Postgres
        List<Movie> movies = movieRepository.findByUserId(userId);
        movies.forEach(m -> indexMovie(userId, movieMapper.toDocument(m)));
    }
}
```

**Rebuild trigger:** `POST /admin/reindex` (Phase 4). Because OpenSearch is derived and Postgres is source of truth, a full rebuild is always safe.

**Note on `indexed_at`:** The `movies` table has `indexed_at TIMESTAMPTZ` (null = not yet indexed). The enrichment flow sets this after successful OpenSearch indexing. The rebuild command uses this field to skip already-indexed movies in incremental mode (future optimization — full rebuild is safe for v1).

### Pattern 4: Nuxt Auth Composable Pattern

**What:** JWT access token lives in Pinia store (in memory, reactive). Refresh token is an HttpOnly + Secure + SameSite=Strict cookie managed by the browser — never readable by JS. On page load / SSR, `useAuth` calls `/auth/me` (or `/auth/refresh`) to restore state. On 401, `$fetch` interceptor triggers refresh then retries.

**When to use:** Phase 1 (auth frontend). All subsequent phases depend on this being in place.

**Pattern:**

```typescript
// stores/auth.ts
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)   // memory only, not localStorage
  const user = ref<User | null>(null)
  const isAuthenticated = computed(() => !!accessToken.value)

  function setToken(token: string, userData: User) {
    accessToken.value = token
    user.value = userData
  }
  function clear() {
    accessToken.value = null
    user.value = null
  }
  return { accessToken, user, isAuthenticated, setToken, clear }
})

// composables/useAuth.ts
export function useAuth() {
  const store = useAuthStore()

  async function login(email: string, password: string) {
    const data = await $fetch<TokenResponse>('/api/auth/login', {
      method: 'POST', body: { email, password }
      // refresh cookie set by browser from Set-Cookie header
    })
    store.setToken(data.accessToken, data.user)
    return navigateTo('/')
  }

  async function logout() {
    await $fetch('/api/auth/logout', { method: 'POST',
      headers: { Authorization: `Bearer ${store.accessToken}` }
    })
    store.clear()
    return navigateTo('/login')
  }

  async function refresh() {
    // refresh cookie is automatically sent (HttpOnly, SameSite=Strict)
    const data = await $fetch<TokenResponse>('/api/auth/refresh', { method: 'POST' })
    store.setToken(data.accessToken, data.user)
  }

  return { login, logout, refresh, user: store.user, isAuthenticated: store.isAuthenticated }
}

// middleware/auth.ts
export default defineNuxtRouteMiddleware(() => {
  const store = useAuthStore()
  if (!store.isAuthenticated) return navigateTo('/login')
})
```

**SSR consideration:** On server-side render, `accessToken` in Pinia is null (Pinia state is not hydrated from cookie). Nuxt's `useRequestFetch` or a plugin that calls `/auth/refresh` on server startup restores state. The refresh token cookie IS available server-side in Nitro via `useCookie`. An app plugin pattern initializes auth state:

```typescript
// plugins/auth.server.ts
export default defineNuxtPlugin(async () => {
  const { refresh } = useAuth()
  try { await refresh() } catch { /* unauthenticated, middleware handles redirect */ }
})
```

## Data Flow

### Save Movie Flow (Full)

```
User clicks "Save"
    |
    v
FE: POST /api/movies/save { tmdbId }    (Authorization: Bearer <access_token>)
    |
    v
JwtAuthFilter validates token → SecurityContext populated
    |
    v
MovieController.save() → MovieService.save()
    |-- Creates Movie stub (status=PENDING, indexed_at=null)
    |-- Saves to PostgreSQL
    |-- Returns movieId
    |-- Calls enrichment.enrich(movieId, userId)  [non-blocking]
    |
    v
202 Accepted { movieId }  → FE polls GET /api/movies/{id} until status != PENDING
    |
    v  [async thread pool "enrich-"]
MovieEnrichmentService.enrich()
    |-- TmdbClient.fetchDetails(tmdbId)       [WireMock in tests]
    |     → extracts imdb_id, full cast, keywords, poster, etc.
    |-- OmdbClient.fetchByImdbId(imdbId)      [skip if no OMDB key]
    |     → imdb_rating, content_rating, director_list, box_office
    |-- WikipediaClient.fetchPlot(title, year) [6-step fallback]
    |     → wikipedia_summary, wikipedia_plot, wikipedia_critics
    |-- movie.updateWithEnrichedData(...)
    |-- movieRepository.save(movie)            [Postgres updated]
    |-- openSearchIndexService.indexMovie(userId, movie)
    |     → ensureIndexExists(userId)          [idempotent]
    |     → client.index(...)                  [OpenSearch]
    |-- movie.setIndexedAt(Instant.now())
    |-- movieRepository.save(movie)            [indexed_at set]
    |
    v  [on failure after 3 retries]
MovieEnrichmentService.enrichFailed()
    |-- movie.setStatus(ENRICHMENT_FAILED)
    |-- movieRepository.save(movie)
```

### Search Flow

```
User types in search bar
    |
    v
FE: GET /api/search?q=inception&year=2010&...  (Bearer token)
    |
    v
SearchController → SearchService
    |-- Builds multi-match query: [title, original_title, overview,
    |   wikipedia_summary, wikipedia_plot, full_cast_names, director_list]
    |-- Applies filters: genre, year range, content_rating, watched
    |-- Null-safe OMDB fields: exists() + must_not exists() for nullable filters
    |-- Targets index: movies-{userId}
    |
    v
OpenSearch returns hits with _score
    |
    v
SearchService maps to SearchResultDTO[]
    |
    v
200 OK [{ id, title, year, poster_path, vote_average, imdb_rating }]
```

### Auth Token Flow (Access Token Expiry)

```
FE: any API call with expired access token
    |
    v
Spring returns 401 Unauthorized
    |
    v
$fetch interceptor catches 401
    |-- POST /api/auth/refresh  (refresh cookie sent automatically)
    |-- Spring validates refresh token hash, issues new JWT + new refresh cookie
    |-- FE: store.setToken(newAccessToken)
    |-- Retry original request with new token
    |
    v
Original response returned to component
```

## Integration Points

### External APIs

| Service | Integration Pattern | Failure Handling |
|---------|---------------------|------------------|
| TMDB | WebClient (WebFlux), `language=en-US`, `/movie/{id}?append_to_response=credits,keywords,images,videos` | No key → `POST /movies/save` returns 400 before async starts |
| OMDB | WebClient, optional. Key check before call | No key → skip silently. HTTP error → log, continue with null OMDB fields |
| Wikipedia | WebClient, 6-step title fallback | No article found → save film without wiki data (not a failure) |
| Mailpit/SMTP | `JavaMailSender` (sync, same request for auth emails) | Mail failure → log error, don't fail auth flow (eventual resend option) |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| FE ↔ BE | HTTP/REST via Caddy `/api/*` | FE never talks to OpenSearch directly |
| MovieService → MovieEnrichmentService | Method call (different bean, async proxy) | Must be separate beans |
| MovieEnrichmentService → OpenSearchIndexService | Method call (same thread, sync) | OpenSearch call is inside async task |
| OpenSearchIndexService ↔ OpenSearch | opensearch-java client over HTTP (port 9200) | No auth in Docker Compose (`DISABLE_SECURITY_PLUGIN=true`) |
| AsyncConfig → enrichment executor | `ThreadPoolTaskExecutor` named `movieEnrichmentExecutor` | Separate from default Spring async executor |

## Build Order (Phase Dependencies)

```
Phase 1 (Auth)
  Requires: User entity, token tables, SecurityConfig, JwtAuthFilter, JwtService,
            AuthController, UserDetailsServiceImpl
  Enables: All subsequent phases (everything needs JWT)

Phase 2 (Settings / API Keys)
  Requires: Phase 1 complete (endpoints are protected)
  Requires: ApiKeyService (AES-256-GCM), user_api_keys table
  Enables: Phase 3 (TMDB key needed for save flow)

Phase 3 (Save Movie Flow)
  Requires: Phase 2 (TMDB key), AsyncConfig, MovieEnrichmentService, external clients
  Requires: movies table Flyway migration (V4 or later)
  Enables: Phase 4 (movies must exist to index them)

Phase 4 (OpenSearch Indexing)
  Requires: Phase 3 (enrichment pipeline writes to Postgres)
  Requires: OpenSearchIndexService, IndexMappingProvider, custom analyzer
  Enables: Phase 5 (index must exist to search)

Phase 5 (Search)
  Requires: Phase 4 (index populated)
  Requires: SearchController, SearchService, null-safe query builders

Phase 6 (Movie Detail + Personal Fields)
  Requires: Phase 4 (indexed_at, full document stored)
  Requires: Phase 5 (detail often reached from search results)
  Notes: personal_rating, personal_notes, watched are user-editable fields
         stored in both Postgres (authoritative) and OpenSearch (for search)

Phase 7 (E2E, Polish)
  Requires: All phases complete
  Notes: Playwright runs against full Docker Compose stack
```

## Scaling Considerations

| Scale | Approach |
|-------|----------|
| 1 user (current) | Single Docker Compose instance, all services co-located |
| ~10 users | Same Compose stack; add OpenSearch heap tuning; connection pool tuning |
| ~100 users | Consider OpenSearch dedicated node; async thread pool tuning; add rate limiting (Bucket4j already in deps) |
| 1000+ users | Separate OpenSearch cluster; migrate from `@Async` to dedicated queue (RabbitMQ); consider index aliases for zero-downtime mapping changes |

## Anti-Patterns

### Anti-Pattern 1: @Async and @Retryable on the Same Bean as the Caller

**What people do:** Put `enrich()` in `MovieService` and call it from `MovieService.save()`.
**Why it's wrong:** Spring AOP proxies are bypassed on self-invocation — `@Async` and `@Retryable` silently do nothing; the method runs synchronously in the same thread without retry.
**Do this instead:** Move `enrich()` to a separate `MovieEnrichmentService` bean. Inject it into `MovieService`.

### Anti-Pattern 2: Storing JWT Access Token in localStorage

**What people do:** `localStorage.setItem('token', accessToken)`.
**Why it's wrong:** Any XSS vector can steal the token. The refresh token is already HttpOnly cookie — the access token should live in memory (Pinia reactive ref) and be re-populated from the refresh endpoint on page load.
**Do this instead:** Memory-only access token in Pinia. Refresh token in HttpOnly cookie. Plugin restores token on SSR hydration.

### Anti-Pattern 3: Creating OpenSearch Index Without Custom Analyzer on First Document Index

**What people do:** Call `client.index(doc)` directly. If the index doesn't exist, OpenSearch auto-creates it with default mapping. Later, adding the custom analyzer requires a destructive reindex.
**Do this instead:** Always call `ensureIndexExists(userId)` before `client.index()`. `ensureIndexExists` applies the `custom_english_analyzer` and full field mapping. Idempotent — safe to call on every save.

### Anti-Pattern 4: Blocking the Save Endpoint on External API Calls

**What people do:** Call TMDB, OMDB, Wikipedia synchronously in `POST /movies/save` and return only when all enrich steps are done.
**Why it's wrong:** External API latency (1-5s each) blocks the HTTP thread; user waits 5-15s for a response; any external API outage makes save fail.
**Do this instead:** Return `202 Accepted` with `movieId` immediately after Postgres stub insert. Async enrichment runs in background. FE polls `/movies/{id}` status.

### Anti-Pattern 5: Using OMDB Failure as a Hard Save Failure

**What people do:** If OMDB returns a non-200 or missing key, abort the save and return an error.
**Why it's wrong:** OMDB is optional by design (CLAUDE.md explicit rule). Many users won't have an OMDB key.
**Do this instead:** If no OMDB key → skip OMDB call entirely. If OMDB key present but request fails → log, continue with `raw_omdb_json = null`. Save completes with TMDB + Wikipedia data only.

## Sources

- Spring Security 6 stateless JWT: [Stateless JWT Authentication with Spring Security](https://skryvets.com/blog/2024/12/15/spring-auth-jwt/) (MEDIUM confidence — verified against official Spring Security docs structure)
- Spring @Async + @Retryable self-invocation issue: [GitHub spring-retry #180](https://github.com/spring-projects/spring-retry/issues/180), [Baeldung Spring Async Retry](https://www.baeldung.com/spring-async-retry) (HIGH confidence — well-documented Spring proxy behavior)
- OpenSearch Java client idempotent index creation: [LearnersBucket check-if-index-exists](https://learnersbucket.com/examples/elasticsearch/check-if-index-exists-in-opensearch-via-java-client/), [OpenSearch Java USER_GUIDE.md](https://github.com/opensearch-project/opensearch-java/blob/main/USER_GUIDE.md) (HIGH confidence — official client)
- Nuxt 3 auth pattern: [Mastering Authentication in Nuxt SSR](https://www.telerik.com/blogs/mastering-authentication-nuxt-3-server-side-rendering-minimalist-guide), [Nuxt auth discussions](https://github.com/nuxt/nuxt/discussions/16429) (MEDIUM confidence — community patterns, no single official guide)
- Spring Boot 3.5.0 + Spring Security 6 dependency versions: confirmed from `build.gradle.kts` in this repo

---
*Architecture research for: MovieArchive — personal film archive web app*
*Researched: 2026-05-15*
