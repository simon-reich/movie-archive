# Phase 5: Search — Pattern Map

**Mapped:** 2026-05-17
**Files analyzed:** 24 new/modified files
**Analogs found:** 22 / 24

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/.../search/SearchController.java` | controller | request-response | `ReindexController.java` | exact |
| `backend/.../search/DashboardController.java` | controller | request-response | `MovieController.java` | role-match |
| `backend/.../search/SearchService.java` | service | request-response | `IndexingService.java` | exact (same OS client) |
| `backend/.../search/DashboardService.java` | service | request-response | `MovieService.java` | role-match |
| `backend/.../search/dto/SearchRequest.java` | model | — | `SaveMovieRequest.java` | role-match |
| `backend/.../search/dto/SearchResponse.java` | model | — | `MovieStatusResponse.java` | role-match |
| `backend/.../search/dto/SearchResultItem.java` | model | — | `MovieStatusResponse.java` | role-match |
| `backend/.../search/dto/FilterCriteria.java` | model | — | `SaveMovieRequest.java` | role-match |
| `backend/.../search/dto/DashboardResponse.java` | model | — | `MovieStatusResponse.java` | role-match |
| `backend/.../search/dto/DashboardMovieItem.java` | model | — | `MovieStatusResponse.java` | role-match |
| `backend/.../search/dto/AutocompleteResponse.java` | model | — | `MovieStatusResponse.java` | role-match |
| `backend/.../search/dto/HistogramBucket.java` | model | — | `MovieStatusResponse.java` | role-match |
| `backend/MovieRepository.java` (modify) | model | CRUD | `MovieRepository.java` | self |
| `backend/.../search/SearchControllerTest.java` | test | — | `ReindexControllerTest.java` | exact |
| `frontend/pages/index.vue` | component (page) | request-response | `settings.vue` (data-on-mount) | role-match |
| `frontend/pages/search.vue` | component (page) | request-response | `add.vue` | exact |
| `frontend/composables/useSearch.ts` | hook | request-response | `useMovies.ts` | exact |
| `frontend/composables/useDashboard.ts` | hook | request-response | `useMovies.ts` | role-match |
| `frontend/components/MovieGrid.vue` | component | — | `add.vue` (grid block) | exact |
| `frontend/components/MovieCard.vue` | component | — | `add.vue` (grid cell) | exact |
| `frontend/components/MovieList.vue` | component | — | `add.vue` (grid block) | role-match |
| `frontend/components/MovieListItem.vue` | component | — | `add.vue` (grid cell) | role-match |
| `frontend/components/FilterPanel.vue` | component | event-driven | no close analog | none |
| `frontend/components/SearchBar.vue` | component | — | `add.vue` (search form) | role-match |
| `frontend/components/SortSelect.vue` | component | — | no close analog | none |
| `frontend/components/ViewToggle.vue` | component | — | no close analog | none |
| `frontend/components/ImdbHistogram.vue` | component | — | no close analog | none |
| `frontend/components/MovieOfTheDay.vue` | component | — | `add.vue` (poster cell) | partial |
| `frontend/stores/search.ts` | store | — | `stores/auth.ts` | role-match |
| `frontend/test/mocks/handlers/search.ts` | test | — | `handlers/movies.ts` | exact |
| `frontend/test/unit/composables/useSearch.spec.ts` | test | — | `useMovies.spec.ts` | exact |
| `frontend/test/unit/pages/search.spec.ts` | test | — | `add.spec.ts` | exact |
| `frontend/test/unit/pages/index.spec.ts` | test | — | `add.spec.ts` | role-match |

---

## Pattern Assignments

### `backend/.../search/SearchController.java` (controller, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/admin/ReindexController.java`

**Imports pattern** (lines 1-15):
```java
package de.moviearchive.search;

import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.UUID;
```

**Class + constructor pattern** (ReindexController.java lines 17-28):
```java
@RestController
@RequestMapping("/search")
@Slf4j
public class SearchController {

    private final SearchService searchService;
    private final UserRepository userRepository;

    public SearchController(SearchService searchService, UserRepository userRepository) {
        this.searchService = searchService;
        this.userRepository = userRepository;
    }
```

**Auth ownership pattern — resolveUserId** (ReindexController.java lines 68-75):
```java
// CRITICAL: auth.getName() returns email (not UUID). Never do UUID.fromString(auth.getName()).
private UUID resolveUserId(Authentication auth) {
    String email = auth.getName();
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
            .getId();
}
```

Note: SearchController uses `resolveUserId(auth)` internally — the userId is NEVER in the URL path (unlike ReindexController's `@PathVariable UUID userId`). The index name is derived server-side: `"movies-" + resolveUserId(auth)`.

**Error handler pattern** (ReindexController.java lines 79-83):
```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
}
```

---

### `backend/.../search/DashboardController.java` (controller, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/movie/MovieController.java`

**Class structure** (MovieController.java lines 17-31):
```java
@RestController
@RequestMapping("/dashboard")
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserRepository userRepository;

    public DashboardController(DashboardService dashboardService, UserRepository userRepository) {
        this.dashboardService = dashboardService;
        this.userRepository = userRepository;
    }
```

**GET endpoint pattern** (MovieController.java lines 44-47):
```java
@GetMapping
public ResponseEntity<DashboardResponse> getDashboard(Authentication auth) throws IOException {
    UUID userId = resolveUserId(auth);
    return ResponseEntity.ok(dashboardService.getDashboard("movies-" + userId, userId));
}
```

**Validation error handler pattern** (MovieController.java lines 67-84):
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .orElse("Validation failed.");
    return ResponseEntity.status(400).body(Map.of("message", message));
}
```

---

### `backend/.../search/SearchService.java` (service, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/indexing/IndexingService.java`

**Class structure + client injection** (IndexingService.java lines 31-45):
```java
@Service
@Slf4j
public class SearchService {

    private final OpenSearchClient client;

    public SearchService(OpenSearchClient client) {
        this.client = client;
    }
```

**OpenSearch client call pattern** (IndexingService.java lines 113-116):
```java
// Typed builder pattern: SearchRequest.of(r -> r.index(indexName).query(...).sort(...))
SearchResponse<Map> response = client.search(searchReq, Map.class);
long total = response.hits().total().value();
List<Map> hits = response.hits().hits().stream()
        .map(Hit::source)
        .collect(Collectors.toList());
```

**Generic client usage** (IndexingService.java lines 69-74):
```java
// Used for refreshIndex() helper in tests — same client bean:
try (var response = client.generic().execute(
        Requests.builder()
                .method("POST")
                .endpoint("/" + indexName + "/_refresh")
                .build())) { }
```

**IOException propagation** (IndexingService.java line 109):
```java
// Service methods throw IOException — controller catches or declares throws IOException
public SearchResponse<SearchResultItem> search(String indexName, SearchRequest request) throws IOException {
```

---

### `backend/.../search/DashboardService.java` (service, request-response)

**Analog:** `backend/src/main/java/de/moviearchive/movie/MovieService.java`

**Service class + constructor pattern** (MovieService.java lines 19-37):
```java
@Service
@Transactional
@Slf4j
public class DashboardService {

    private final OpenSearchClient client;
    private final MovieRepository movieRepository;

    public DashboardService(OpenSearchClient client, MovieRepository movieRepository) {
        this.client = client;
        this.movieRepository = movieRepository;
    }
```

**User lookup pattern** (MovieService.java lines 47-50):
```java
// DashboardService receives already-resolved userId from controller — no email lookup in service
// Recently-added from Postgres:
List<Movie> recent = movieRepository.findTop10ByUserIdOrderByIndexedAtDesc(userId, PageRequest.of(0, 10));
```

---

### `backend/.../search/dto/SearchRequest.java` (model)

**Analog:** `backend/src/main/java/de/moviearchive/movie/dto/SaveMovieRequest.java`

**Record + validation pattern** (SaveMovieRequest.java lines 1-8):
```java
package de.moviearchive.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

// Use @Data + @Builder for complex DTOs (not record) to allow null fields
@Data
@Builder
public class SearchRequest {
    private String query;          // null = match_all
    @Valid
    private FilterCriteria filters;
    private String sort;           // "title_asc" | "year_desc" | "rating_desc" | "imdb_desc"
    @Min(0)
    private int page;
}
```

**Simple response record** (MovieStatusResponse.java lines 1-8):
```java
package de.moviearchive.search.dto;

public record SearchResultItem(
    String id,
    Integer tmdbId,
    String title,
    Integer year,
    String posterPath,
    java.util.List<String> directorList,
    java.util.List<String> genreList,
    Double imdbRating,
    Integer runtime
) {}
```

---

### `backend/MovieRepository.java` (modify — add recently-added query)

**Analog:** `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` (self)

**Existing @Query pattern to follow** (MovieRepository.java lines 36-43):
```java
/**
 * Returns movies not yet indexed in OpenSearch. Used by partial reindex.
 */
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NULL")
List<Movie> findByUserIdAndIndexedAtIsNull(@Param("userId") UUID userId);
```

**New method to add** (follows same pattern, add after line 43):
```java
/**
 * Returns the N most recently indexed movies for the given user.
 * Used by DashboardService for the "recently added" dashboard section.
 * Pageable controls the limit (pass PageRequest.of(0, 10)).
 */
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NOT NULL " +
       "ORDER BY m.indexedAt DESC")
List<Movie> findRecentlyIndexedByUserId(@Param("userId") UUID userId, Pageable pageable);
```

---

### `backend/.../search/SearchControllerTest.java` (test)

**Analog:** `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java`

**Class declaration + base class** (ReindexControllerTest.java lines 32-50):
```java
@AutoConfigureMockMvc
class SearchControllerTest extends AbstractOpenSearchTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private IndexingService indexingService;
    @Autowired private OpenSearchClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();
```

**@BeforeEach cleanup pattern** (ReindexControllerTest.java lines 53-56):
```java
@BeforeEach
void cleanDb() throws Exception {
    movieRepository.deleteAll();
    userRepository.deleteAll();
}
```

**createActiveUser helper** (ReindexControllerTest.java lines 61-66):
```java
private User createActiveUser(String email) {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    User user = new User(email, encoder.encode("Password1!"));
    user.setStatus(UserStatus.ACTIVE);
    return userRepository.save(user);
}
```

**loginAndGetToken helper** (ReindexControllerTest.java lines 69-79):
```java
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

**persistMovie helper** (ReindexControllerTest.java lines 89-109):
```java
// Copy this pattern for indexTestMovie() in SearchControllerTest.
// Unique tmdbId counter avoids (user_id, tmdb_id) unique constraint violations.
private int tmdbIdSeq = 1000;

private Movie persistMovie(User user, String title, Instant indexedAt) throws Exception {
    int tmdbId = tmdbIdSeq++;
    String tmdbJson = String.format(
            "{\"id\":%d,\"title\":\"%s\",\"original_title\":\"%s\"," +
            "\"overview\":\"A test movie.\",\"tagline\":\"\",\"release_date\":\"2010-07-16\"," +
            "\"runtime\":120,\"vote_average\":7.5,\"vote_count\":1000," +
            "\"poster_path\":\"\",\"backdrop_path\":\"\"," +
            "\"genres\":[],\"production_countries\":[],\"spoken_languages\":[]," +
            "\"production_companies\":[],\"keywords\":{\"keywords\":[]}," +
            "\"credits\":{\"cast\":[],\"crew\":[]}," +
            "\"videos\":{\"results\":[]},\"images\":{\"posters\":[],\"backdrops\":[]}}",
            tmdbId, title, title);
    Movie movie = new Movie(user, tmdbId);
    movie.setTitle(title);
    movie.setOriginalTitle(title);
    movie.setStatus(MovieStatus.SUCCESS);
    movie.setRawTmdbJson(objectMapper.readTree(tmdbJson));
    movie.setIndexedAt(indexedAt);
    return movieRepository.save(movie);
}
```

**deleteIndexIfExists helper** (ReindexControllerTest.java lines 112-122):
```java
private void deleteIndexIfExists(String indexName) throws Exception {
    try {
        client.indices().delete(
                org.opensearch.client.opensearch.indices.DeleteIndexRequest.of(
                        r -> r.index(indexName)));
    } catch (org.opensearch.client.opensearch._types.OpenSearchException e) {
        if (!"index_not_found_exception".equals(e.error().type())) {
            throw e;
        }
    }
}
```

**refreshIndex helper** (ReindexControllerTest.java lines 125-133):
```java
// MANDATORY: call after every indexing operation before asserting search results.
// OpenSearch is near-real-time (1-second refresh interval by default).
private void refreshIndex(String indexName) throws Exception {
    try (var response = client.generic().execute(
            Requests.builder()
                    .method("POST")
                    .endpoint("/" + indexName + "/_refresh")
                    .build())) { }
}
```

**MockMvc POST with JSON body** (ReindexControllerTest.java lines 189-193):
```java
mockMvc.perform(post("/search")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\",\"sort\":\"title_asc\",\"page\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].title").value("Amelie"))
        .andExpect(jsonPath("$.total").value(2));
```

---

### `frontend/composables/useSearch.ts` (hook, request-response)

**Analog:** `frontend/composables/useMovies.ts`

**Auth cookie + authHeaders pattern** (useMovies.ts lines 23-29):
```typescript
export function useSearch() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }
```

**$fetch with credentials + auth headers** (useMovies.ts lines 32-37):
```typescript
// All fetch calls follow this exact pattern — credentials: 'include' is mandatory.
const response = await $fetch<SearchApiResponse>('/api/search', {
  method: 'POST',
  body: buildRequestBody(),
  credentials: 'include',
  headers: authHeaders(),
})
```

**Manual debounce** (add.vue polling pattern, adapted):
```typescript
// VueUse is NOT installed — use manual setTimeout/clearTimeout.
let debounceTimer: ReturnType<typeof setTimeout> | null = null

watch(query, (val) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    router.replace({ query: { ...route.query, q: val, page: '0' } })
  }, 300)
})
```

**URL param normalization (multi-value):**
```typescript
// route.query.genre is string | string[] | null — always normalize.
function normalizeQueryParam(val: string | string[] | null | undefined): string[] {
  if (!val) return []
  return Array.isArray(val) ? val as string[] : [val as string]
}
```

**Watch route for URL-driven filter navigation (D-14):**
```typescript
// Fires on mount (immediate: true) and on every URL param change (clickable attr navigation).
watch(() => route.query, () => { executeSearch() }, { immediate: true })
```

---

### `frontend/composables/useDashboard.ts` (hook, request-response)

**Analog:** `frontend/composables/useMovies.ts`

**GET fetch on mount pattern** (useMovies.ts lines 32-36):
```typescript
export function useDashboard() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const data = ref<DashboardResponse | null>(null)
  const isLoading = ref(false)

  async function fetchDashboard(): Promise<void> {
    isLoading.value = true
    try {
      data.value = await $fetch<DashboardResponse>('/api/dashboard', {
        credentials: 'include',
        headers: authHeaders(),
      })
    } finally {
      isLoading.value = false
    }
  }

  return { data, isLoading, fetchDashboard }
}
```

---

### `frontend/pages/search.vue` (component page, request-response)

**Analog:** `frontend/pages/add.vue`

**Script setup + composable call pattern** (add.vue lines 1-11):
```vue
<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { Grid, List, SlidersHorizontal } from 'lucide-vue-next'
import SearchBar from '@/components/SearchBar.vue'
import FilterPanel from '@/components/FilterPanel.vue'
import MovieGrid from '@/components/MovieGrid.vue'
import MovieList from '@/components/MovieList.vue'
import ViewToggle from '@/components/ViewToggle.vue'
import SortSelect from '@/components/SortSelect.vue'

const { query, results, total, hasMore, isLoading, sort, genres, executeSearch } = useSearch()
const searchStore = useSearchStore()
```

**Poster grid section** (add.vue lines 126-174, grid block):
```vue
<!-- Copy the grid class string exactly from add.vue line 126 -->
<div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
  <MovieCard
    v-for="item in results"
    :key="item.id"
    :movie="item"
  />
</div>
```

---

### `frontend/pages/index.vue` (component page, request-response)

**Analog:** `frontend/pages/settings.vue` (data-on-mount pattern)

**onMounted data fetch pattern** (settings.vue lines 61-77, adapted):
```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import DashboardStats from '@/components/DashboardStats.vue'
import MovieOfTheDay from '@/components/MovieOfTheDay.vue'

const { data, isLoading, fetchDashboard } = useDashboard()

onMounted(async () => {
  await fetchDashboard()
})
</script>
```

---

### `frontend/components/MovieGrid.vue` (component)

**Analog:** `frontend/pages/add.vue` (grid block, lines 126-174)

**Grid + poster image pattern** (add.vue lines 126-174):
```vue
<!-- Exact Tailwind grid classes from add.vue line 126 -->
<div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
  <div
    v-for="item in movies"
    :key="item.id"
    class="relative group overflow-hidden"
  >
    <img
      :src="posterUrl(item.posterPath)"
      :alt="item.title ?? ''"
      class="w-full aspect-[2/3] object-cover bg-card border border-border"
    />
    <div class="pt-2">
      <p class="text-sm font-medium text-foreground truncate">{{ item.title }}</p>
      <p class="text-xs text-muted-foreground">{{ item.year ?? 'Unknown year' }}</p>
    </div>
  </div>
</div>
```

**posterUrl function** (add.vue lines 98-101):
```typescript
// Copy this function verbatim into MovieCard.vue and MovieListItem.vue
function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}
```

---

### `frontend/components/MovieCard.vue` (component)

**Analog:** `frontend/pages/add.vue` (single grid cell block, lines 128-174)

The individual `<div>` inside the grid loop becomes the MovieCard component. Extract:
- poster `<img>` with `aspect-[2/3]` class
- title `<p class="text-sm font-medium ...">` and year `<p class="text-xs ...">` below
- `posterUrl()` function as a local composable or inline computed
- Clickable genre/director chips emitted as click events navigating to `/search?genre=X`

---

### `frontend/stores/search.ts` (store)

**Analog:** `frontend/stores/auth.ts`

**defineStore setup function pattern** (auth.ts lines 1-38):
```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSearchStore = defineStore('search', () => {
  // viewMode: persisted to localStorage via manual read/write
  const stored = import.meta.client ? localStorage.getItem('viewMode') : null
  const viewMode = ref<'grid' | 'list'>((stored as 'grid' | 'list') ?? 'grid')

  function setViewMode(mode: 'grid' | 'list'): void {
    viewMode.value = mode
    if (import.meta.client) localStorage.setItem('viewMode', mode)
  }

  return { viewMode, setViewMode }
})
```

Note: Pinia store follows the setup function style (not options API), consistent with `auth.ts`.

---

### `frontend/test/mocks/handlers/search.ts` (test)

**Analog:** `frontend/test/mocks/handlers/movies.ts`

**Handler module structure** (movies.ts lines 1-39):
```typescript
import { http, HttpResponse } from 'msw'

export const searchHandlers = [
  // POST /api/search
  http.post('/api/search', async ({ request }) => {
    const body = await request.json() as any
    return HttpResponse.json({
      results: [ /* ... */ ],
      total: 1,
      page: 0,
      totalPages: 1,
      hasMore: false,
    })
  }),

  // GET /api/dashboard
  http.get('/api/dashboard', () => {
    return HttpResponse.json({ /* ... */ })
  }),

  // GET /api/search/autocomplete
  http.get('/api/search/autocomplete', ({ request }) => {
    return HttpResponse.json({ suggestions: ['Christopher Nolan'] })
  }),
]
```

**Registration in handlers.ts** (handlers.ts lines 1-17 — must add `searchHandlers`):
```typescript
import { searchHandlers } from './handlers/search'

export const handlers = [
  http.get('/api/actuator/health', () => HttpResponse.json({ status: 'UP' })),
  ...authHandlers,
  ...settingsHandlers,
  ...moviesHandlers,
  ...searchHandlers,  // ADD THIS LINE
]
```

---

### `frontend/test/unit/composables/useSearch.spec.ts` (test)

**Analog:** `frontend/test/unit/composables/useMovies.spec.ts`

**vi.stubGlobal + dynamic import pattern** (useMovies.spec.ts lines 1-7):
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

// Dynamic import after stubs are in place
const { useSearch } = await import('@/composables/useSearch')

describe('useSearch composable', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })
  // ...
})
```

---

### `frontend/test/unit/pages/search.spec.ts` and `index.spec.ts` (test)

**Analog:** `frontend/test/unit/pages/add.spec.ts`

**Module import test pattern** (add.spec.ts lines 1-25):
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

describe('/search page', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('search page module exports a valid Vue component', async () => {
    const { default: SearchPage } = await import('@/pages/search.vue')
    expect(SearchPage).toBeDefined()
    expect(typeof SearchPage).toBe('object')
  })

  it('useSearch.executeSearch is called and returns results', async () => {
    mockFetch.mockResolvedValueOnce({
      results: [{ id: 'uuid-1', title: 'Inception', year: 2010 }],
      total: 1,
      hasMore: false,
    })
    const { useSearch } = await import('@/composables/useSearch')
    const { executeSearch, results } = useSearch()
    await executeSearch()
    expect(results.value).toHaveLength(1)
  })
})
```

---

## Shared Patterns

### Authentication — resolveUserId (Backend)

**Source:** `ReindexController.java` lines 68-75
**Apply to:** `SearchController.java`, `DashboardController.java`

```java
// auth.getName() returns email. NEVER use UUID.fromString(auth.getName()).
private UUID resolveUserId(Authentication auth) {
    String email = auth.getName();
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
            .getId();
}
```

### Index Name Derivation (Backend)

**Source:** `IndexingService.java` line 110, `ReindexController.java` line 42
**Apply to:** `SearchController.java`, `DashboardController.java`

```java
// Always derived from authenticated userId — never from request body/path
String indexName = "movies-" + resolveUserId(auth);
```

### IOException Propagation (Backend)

**Source:** `IndexingService.java` — all public methods
**Apply to:** `SearchService.java`, `DashboardService.java`, controller endpoints

```java
// Service methods declare throws IOException; controllers declare throws IOException too.
// No try/catch wrapping IOException into RuntimeException.
public SearchResponse search(String indexName, SearchRequest req) throws IOException { ... }
```

### Auth Cookie + authHeaders (Frontend)

**Source:** `useMovies.ts` lines 23-29
**Apply to:** `useSearch.ts`, `useDashboard.ts`

```typescript
const accessTokenCookie = useCookie<string | null>('access_token')

function authHeaders(): Record<string, string> {
  return accessTokenCookie.value
    ? { Authorization: `Bearer ${accessTokenCookie.value}` }
    : {}
}
// All $fetch calls: credentials: 'include', headers: authHeaders()
```

### posterUrl Function (Frontend)

**Source:** `add.vue` lines 98-101
**Apply to:** `MovieCard.vue`, `MovieListItem.vue`, `MovieOfTheDay.vue`, any component displaying posters

```typescript
function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}
```

### Tailwind Poster Grid Layout (Frontend)

**Source:** `add.vue` line 126
**Apply to:** `MovieGrid.vue`

```html
<div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
```

### OpenSearch refreshIndex in Tests (Backend)

**Source:** `ReindexControllerTest.java` lines 125-133 and `IndexingIntegrationTest.java` lines 237-244
**Apply to:** `SearchControllerTest.java` — every test that indexes then searches

```java
// MANDATORY before any search assertion in integration tests.
private void refreshIndex(String indexName) throws Exception {
    try (var response = client.generic().execute(
            Requests.builder()
                    .method("POST")
                    .endpoint("/" + indexName + "/_refresh")
                    .build())) { }
}
```

### MSW Handler Export + Registration Pattern (Frontend)

**Source:** `handlers/movies.ts` lines 1-3, `handlers.ts` lines 1-17
**Apply to:** `handlers/search.ts` — must export `searchHandlers` array, then add to `handlers.ts`

```typescript
// handlers/search.ts: export const searchHandlers = [ ... ]
// handlers.ts: import { searchHandlers } from './handlers/search'
//              then spread: ...searchHandlers
```

### Pinia Store Setup Function Style (Frontend)

**Source:** `stores/auth.ts` lines 1-38
**Apply to:** `stores/search.ts`

```typescript
// Use setup function style (not options API).
// Return only the state and actions needed by consumers.
export const useSearchStore = defineStore('search', () => {
  const viewMode = ref<'grid' | 'list'>('grid')
  // ...
  return { viewMode, setViewMode }
})
```

---

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `frontend/components/FilterPanel.vue` | component | event-driven | No collapsible/accordion component exists; uses radix-vue Collapsible primitive (already installed) |
| `frontend/components/SortSelect.vue` | component | — | No select/dropdown component exists in codebase yet |
| `frontend/components/ViewToggle.vue` | component | — | No toggle/segmented-control component exists |
| `frontend/components/ImdbHistogram.vue` | component | — | No data visualization component exists; render as CSS bar chart (no chart library) |
| `frontend/components/DashboardStats.vue` | component | — | No stats/card-grid component exists |
| `frontend/components/SearchBar.vue` | component | — | `InputText.vue` exists as a wrapper but no search-specific bar with clear button |
| `frontend/components/MovieOfTheDay.vue` | component | — | Partial analog: poster cell in add.vue, but no featured/hero display pattern |
| `backend/.../search/dto/FilterCriteria.java` | model | — | No multi-field filter DTO exists; follow SaveMovieRequest record pattern but use @Data @Builder class |

---

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/`, `backend/src/test/java/de/moviearchive/`, `frontend/composables/`, `frontend/pages/`, `frontend/stores/`, `frontend/test/`
**Files scanned:** 14 source files read in full
**Pattern extraction date:** 2026-05-17
