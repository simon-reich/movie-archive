# Phase 5: Search — Research

**Researched:** 2026-05-17
**Domain:** OpenSearch query DSL, Spring Boot REST, Nuxt 3 composable + URL state
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- D-01: `/` is the dashboard, not the search page. `/search` is the search page.
- D-02: Dashboard shows archive stats (total films, top genres, language breakdown, IMDB rating histogram 1-10), movie of the day (random unwatched, date-seeded), recently added (poster cards).
- D-03: Movie of the day algorithm: `watched = false` OR all films until Phase 6, date-seeded for day stability.
- D-04: Search triggers live-as-you-type with ~300ms debounce. No explicit submit button required.
- D-05: Empty query shows all films sorted by title A-Z (`match_all`, sort on `title.raw`).
- D-06: Free-text searches all indexed text fields.
- D-07: Switchable grid (poster + title + year) / list (poster thumbnail + title + year + director + genres + IMDB rating + runtime) view modes.
- D-08: Default view mode is Claude's choice (grid).
- D-09: Collapsible filter panel above results, hidden by default, expanded via "Filters" button.
- D-10: Extended filter set — Genre (multi-select OR), Director (text+autocomplete), Actors/cast (text+autocomplete), Year (range), IMDB rating (range), Content rating (multi-select OR), Runtime max (single), Not-yet-watched (boolean toggle), Language (multi-select OR), Production country (multi-select OR).
- D-11: OR within filter group, AND across groups.
- D-12: Sort options — title A-Z, release year (desc), personal rating (desc, empty until Phase 6), IMDB rating (desc).
- D-13: UI controls per field type — Claude's discretion.
- D-14: Clickable attributes navigate to `/search` with URL query params pre-applied.
- D-15: Clickable attributes on search result cards in Phase 5 only (detail page = Phase 6).
- D-16: URL query param naming — Claude's discretion (must be consistent).
- D-17: Multi-value filter via repeated params (`?genre=Action&genre=Thriller` = OR).

### Claude's Discretion
- OpenSearch query structure for free-text (multi_match vs. bool/should with per-field boosts)
- Debounce implementation
- Autocomplete suggestion source (aggregation vs. prefix query)
- Dashboard aggregation queries
- Date-seeding algorithm for movie of the day
- Pagination strategy
- Default view mode
- Filter panel animation
- Exact URL query param naming

### Deferred Ideas (OUT OF SCOPE)
- Clickable attributes on movie detail page (Phase 6, DETAIL-05)
- OpenSearch doc upsert on personal field save (Phase 6, D-06 from Phase 4)
- Average personal rating on dashboard (Phase 6 dependency)
- Watched vs. unwatched count on dashboard (Phase 6 dependency)
- Infinite scroll / cursor-based pagination (optional — load-more preferred)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SRCH-01 | User can perform free-text search across all indexed film fields | Multi-field bool/should query with boosts; custom_english_analyzer on `.text` sub-fields; match_all for empty query |
| SRCH-02 | User can combine advanced filters: genre, director, year, IMDB rating, content rating, watched | bool.filter with terms/range/term queries; null-safe (OMDB fields may be null — range filter naturally excludes null docs) |
| SRCH-03 | User can sort results by title A-Z, release year, personal rating, IMDB rating | `title.raw` keyword sub-field; float fields with `missing: "_last"` for nulls |
| SRCH-04 | Clicking actor/director/genre on any page opens a pre-filtered search results list | URL query params via useRoute/useRouter; composable reads params on mount and reacts to route changes |
</phase_requirements>

---

## Summary

Phase 5 implements the full search and discovery experience on top of the OpenSearch index built in Phase 4. The backend `SearchService` translates HTTP request parameters into a single OpenSearch `SearchRequest` combining a multi-field bool/should query (free-text) with zero or more filter clauses in `bool.filter` context, a sort directive, and from/size pagination. A separate `DashboardService` handles the home page data using aggregations and a function_score random query for movie of the day. The frontend provides two new pages (`/` and `/search`), a `useSearch` composable managing all query/filter/sort/pagination state synchronized with URL params, and a `useDashboard` composable for the home page data fetch.

The critical architectural constraint: **all sort-capable string fields require `.raw` keyword sub-fields**, which already exist in the Phase 4 mapping. Filter queries in `bool.filter` context benefit from OpenSearch filter cache and do not affect relevance scores. OMDB-sourced fields (`imdb_rating`, `content_rating`) are nullable — range and term filters naturally exclude null documents, which is correct for user intent. The `full_cast`/`full_crew` nested fields are NOT queried in Phase 5; the denormalized `full_cast_names`/`full_crew_names` keyword fields (with `.text` analyzed sub-fields) handle all name search and autocomplete.

The data-model.md `rating_list` entry says `flattened` but Phase 4 State.md records the actual implementation as `object` type (OpenSearch 2.x does not support `flattened`). Phase 5 does not query `rating_list` directly, so this deviation has no impact.

**Primary recommendation:** Single `POST /search` endpoint with a structured request body (query, filters, sort, page, size). Dashboard on a separate `GET /dashboard` endpoint. Recently-added films queried from Postgres (ORDER BY indexed_at DESC) rather than OpenSearch to avoid adding `indexed_at` to the OS mapping.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Free-text + filter query construction | API / Backend | — | OpenSearch query DSL lives in SearchService; no query logic in frontend |
| Search result rendering (grid/list) | Browser / Client | — | Pure display logic; data already shaped by API response |
| URL query param state (shareable links) | Browser / Client | Frontend Server (SSR) | useRoute reads params on mount + SSR initial hydration |
| Dashboard aggregation queries | API / Backend | — | Aggregations run in OpenSearch via DashboardService |
| "Movie of the day" random selection | API / Backend | — | function_score + random_score + date seed; deterministic per calendar day |
| Recently added films | API / Backend (Postgres) | — | Query MovieRepository ORDER BY indexed_at DESC LIMIT 10; no OS mapping change |
| Autocomplete suggestions | API / Backend | — | Prefix query or terms agg on OS; not computed in browser |
| Filter panel expand/collapse | Browser / Client | — | UI-only state; no server involvement |
| Pagination / load-more | Browser / Client + API | — | Frontend manages page counter; backend uses from/size |
| Auth guard for /search and / | Frontend Server (SSR) | Browser | Existing auth.global.ts middleware covers all protected routes automatically |

---

## Standard Stack

### Core (all already in build.gradle.kts — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| opensearch-java | 2.19.0 | SearchRequest, bool query, aggregations, function_score | Already used for indexing; same client bean from OpenSearchConfig |
| httpclient5 | 5.4.4 | Transport for OpenSearch client | Required by ApacheHttpClient5Transport |
| Spring Boot Web | 3.5.0 | RestController, RequestBody, GetMapping | Core framework |
| Lombok | BOM-managed | @Slf4j, @Data, @Builder for DTOs | Already used throughout |

[VERIFIED: build.gradle.kts for all versions above]

### Frontend (all already in package.json — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Nuxt 3 / Vue 3 | ^3.21.5 | Pages, composables, useRoute, useRouter | Project framework |
| Pinia | ^2.3.1 | View mode preference persistence (useSearchStore) | Already used for auth store; consistent pattern |
| lucide-vue-next | ^0.487.0 | Grid, List, SlidersHorizontal, X, ChevronDown icons | Already installed |
| radix-vue | ^1.9.13 | Collapsible primitive for filter panel | Already installed |

[VERIFIED: package.json for all versions above]

**No new packages required for Phase 5.** All dependencies are present from Phases 1-4.

### Installation
```bash
# No new installs needed — all dependencies already in place.
```

---

## Package Legitimacy Audit

No new external packages are introduced in Phase 5. All libraries are already installed and verified in prior phases.

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

---

## Architecture Patterns

### System Architecture Diagram

```
Browser
  search.vue  ─── GET /api/search?q=&genre=&sort=&page=  ──────────┐
  index.vue   ─── GET /api/dashboard                    ──────────┤
                                                                     ▼
                                                        Caddy (proxy)
                                                             │
                                                    Spring Boot Backend
                                                             │
                               ┌─────────────────────────────────────────┐
                               │  SearchController                        │
                               │    auth.getName() → email               │
                               │    → userRepository.findByEmail(email)  │
                               │    → userId → "movies-{userId}"         │
                               │    → SearchService.search(...)          │
                               │      → OpenSearch bool query            │
                               │         must: text query (if q)         │
                               │         filter: [genre, director, ...]  │
                               │         sort: [title.raw | year | ...]  │
                               │         from/size pagination            │
                               └──────────────────┬──────────────────────┘
                                                  │
                               ┌──────────────────▼──────────────────────┐
                               │  DashboardController                     │
                               │    → DashboardService                   │
                               │      → OS: aggregations (stats)         │
                               │      → OS: function_score (movie-of-day)│
                               │      → Postgres: recently added films   │
                               └─────────────────────────────────────────┘
```

### Recommended Project Structure

**Backend:**
```
backend/src/main/java/de/moviearchive/search/
  SearchController.java          # POST /search, GET /search/autocomplete
  SearchService.java             # OpenSearch query construction + execution
  DashboardController.java       # GET /dashboard
  DashboardService.java          # Aggregations + movie-of-the-day + recently added
  dto/
    SearchRequest.java           # query, filters, sort, page, size
    SearchResponse.java          # results, total, page, totalPages, hasMore
    SearchResultItem.java        # id, tmdbId, title, year, posterPath, directorList, genreList, imdbRating, runtime
    FilterCriteria.java          # genres, director, actors, yearFrom, yearTo, imdbRatingFrom, imdbRatingTo, contentRatings, runtimeMax, notYetWatched, languages, countries
    AutocompleteResponse.java    # suggestions: List<String>
    DashboardResponse.java       # totalFilms, topGenres, languageBreakdown, imdbHistogram, movieOfTheDay, recentlyAdded
    DashboardMovieItem.java      # id, title, year, posterPath (for movieOfTheDay + recentlyAdded)
    HistogramBucket.java         # label (e.g. "7-8"), count
```

**Frontend:**
```
frontend/
  pages/
    index.vue              # Dashboard (/)
    search.vue             # Search page (/search)
  composables/
    useSearch.ts           # query state, filters, sort, pagination, 300ms debounce, URL sync
    useDashboard.ts        # dashboard data fetch + computed stats
  components/
    SearchBar.vue          # Text input with clear button
    FilterPanel.vue        # Collapsible; contains all 10 filter controls
    SortSelect.vue         # Sort dropdown
    ViewToggle.vue         # Grid/List switcher
    MovieGrid.vue          # Poster grid (reuses add.vue pattern)
    MovieList.vue          # Metadata-rich list
    MovieCard.vue          # Single card (grid): poster + title + year + clickable genre/director chips
    MovieListItem.vue      # Single row (list): thumbnail + title + year + director + genres + rating + runtime
    ImdbHistogram.vue      # Bar chart for dashboard IMDB distribution
    MovieOfTheDay.vue      # Prominent daily pick: poster + title + year
  stores/
    search.ts              # viewMode: 'grid' | 'list' (persisted to localStorage via Pinia plugin or manual)
  test/
    mocks/handlers/search.ts         # MSW: GET /api/search, GET /api/dashboard, GET /api/search/autocomplete
    unit/composables/useSearch.spec.ts
    unit/pages/search.spec.ts
    unit/pages/index.spec.ts
```

### Pattern 1: Multi-Field Free-Text Search — bool/should with boosts

**What:** A `bool.should` query against multiple text fields with relevance boost weights. Title matches rank highest; Wikipedia critics rank lowest.

**When to use:** Whenever the `q` parameter is non-empty.

```java
// Source: opensearch-java SearchRequest builder (verified against IndexingIntegrationTest pattern)
// [ASSUMED] for specific boost values — standard practice, tune from user testing
private Query buildTextQuery(String query) {
    return Query.of(q -> q.bool(b -> b
        .should(List.of(
            matchQuery("title",                 query, 4.0f),
            matchQuery("original_title",        query, 3.0f),
            matchQuery("director_list.text",    query, 3.0f),  // .text = analyzed sub-field
            matchQuery("full_cast_names.text",  query, 2.5f),
            matchQuery("genre_list.text",       query, 2.0f),
            matchQuery("tagline",               query, 2.0f),
            matchQuery("overview",              query, 1.5f),
            matchQuery("keyword_list.text",     query, 1.5f),
            matchQuery("full_crew_names.text",  query, 1.0f),
            matchQuery("wikipedia_summary",     query, 1.0f),
            matchQuery("wikipedia_plot",        query, 0.8f),
            matchQuery("wikipedia_critics",     query, 0.6f),
            matchQuery("personal_notes",        query, 1.0f)   // null until Phase 6; harmless
        ))
        .minimumShouldMatch("1")));
}

private Query matchQuery(String field, String value, float boost) {
    return Query.of(q -> q.match(m -> m
        .field(field)
        .query(fv -> fv.stringValue(value))
        .boost(boost)));
}
```

Note on field selection: `director_list`, `full_cast_names`, `genre_list`, `keyword_list`, `full_crew_names` are `keyword` type. Their `.text` sub-field (type `text` with `custom_english_analyzer`) supports analyzed full-text matching. [VERIFIED: data-model.md]

**Empty query (D-05):**
```java
Query baseQuery = (query == null || query.isBlank())
    ? Query.of(q -> q.matchAll(m -> m))
    : buildTextQuery(query);
```

### Pattern 2: Filter Combination — bool.filter context

**What:** Each active filter becomes a clause in `bool.filter`. OR within a filter = `terms` query. AND across filters = multiple `filter` clauses on the same `bool`. Filter context means: no score contribution and results are cached by OpenSearch.

```java
// Source: verified against opensearch-java BoolQuery builder used in IndexingIntegrationTest
BoolQuery.Builder bool = new BoolQuery.Builder();

// Text query goes in must (contributes to _score); match_all goes in must too (no score effect)
bool.must(baseQuery);

// Genre (multi-select OR) — terms on genre_list (keyword)
if (criteria.getGenres() != null && !criteria.getGenres().isEmpty()) {
    bool.filter(Query.of(q -> q.terms(t -> t
        .field("genre_list")
        .terms(tv -> tv.value(toFieldValues(criteria.getGenres()))))));
}

// Director — match on director_list.text (analyzed; handles accent-folding + stemming)
if (hasText(criteria.getDirector())) {
    bool.filter(Query.of(q -> q.match(m -> m
        .field("director_list.text")
        .query(fv -> fv.stringValue(criteria.getDirector())))));
}

// Actors — match on full_cast_names.text
if (hasText(criteria.getActors())) {
    bool.filter(Query.of(q -> q.match(m -> m
        .field("full_cast_names.text")
        .query(fv -> fv.stringValue(criteria.getActors())))));
}

// Year range — integer field
if (criteria.getYearFrom() != null || criteria.getYearTo() != null) {
    bool.filter(buildRangeQuery("year", criteria.getYearFrom(), criteria.getYearTo()));
}

// IMDB rating range — float, OMDB-nullable
// Documents with imdb_rating=null are excluded by range automatically — correct behavior
if (criteria.getImdbRatingFrom() != null || criteria.getImdbRatingTo() != null) {
    bool.filter(buildRangeQuery("imdb_rating", criteria.getImdbRatingFrom(), criteria.getImdbRatingTo()));
}

// Content rating (multi-select OR) — keyword terms
if (criteria.getContentRatings() != null && !criteria.getContentRatings().isEmpty()) {
    bool.filter(Query.of(q -> q.terms(t -> t
        .field("content_rating")
        .terms(tv -> tv.value(toFieldValues(criteria.getContentRatings()))))));
}

// Runtime max — integer range upper bound only
if (criteria.getRuntimeMax() != null) {
    bool.filter(Query.of(q -> q.range(r -> r
        .field("runtime")
        .lte(JsonData.of(criteria.getRuntimeMax())))));
}

// Not-yet-watched — term on boolean field
// Until Phase 6 writes watched, all docs have watched=null.
// term(watched=false) matches ONLY explicit false, NOT null — so returns empty until Phase 6.
// This is intentional per D-10 "empty until Phase 6".
if (Boolean.TRUE.equals(criteria.getNotYetWatched())) {
    bool.filter(Query.of(q -> q.term(t -> t
        .field("watched")
        .value(fv -> fv.booleanValue(false)))));
}

// Language (multi-select OR) — keyword terms on language_list (ISO-639-1 codes)
if (criteria.getLanguages() != null && !criteria.getLanguages().isEmpty()) {
    bool.filter(Query.of(q -> q.terms(t -> t
        .field("language_list")
        .terms(tv -> tv.value(toFieldValues(criteria.getLanguages()))))));
}

// Production country (multi-select OR) — keyword terms on country_list (ISO-3166-1 codes)
if (criteria.getCountries() != null && !criteria.getCountries().isEmpty()) {
    bool.filter(Query.of(q -> q.terms(t -> t
        .field("country_list")
        .terms(tv -> tv.value(toFieldValues(criteria.getCountries()))))));
}

Query combinedQuery = Query.of(q -> q.bool(bool.build()));
```

Helper:
```java
private List<FieldValue> toFieldValues(List<String> values) {
    return values.stream().map(FieldValue::of).collect(Collectors.toList());
}

private boolean hasText(String s) { return s != null && !s.isBlank(); }
```

### Pattern 3: Sorting with null-safety

```java
// Source: opensearch-java SortOptions builder [VERIFIED: data-model.md .raw sub-fields]
// [ASSUMED] for missing value strategy — standard OpenSearch practice
SortOptions sort = switch (sortParam) {
    case "title_asc"   -> SortOptions.of(s -> s.field(f -> f
                            .field("title.raw").order(SortOrder.Asc)));
    case "year_desc"   -> SortOptions.of(s -> s.field(f -> f
                            .field("year").order(SortOrder.Desc)
                            .missing(JsonData.of("_last"))));
    case "rating_desc" -> SortOptions.of(s -> s.field(f -> f
                            .field("personal_rating").order(SortOrder.Desc)
                            .missing(JsonData.of("_last"))));
    case "imdb_desc"   -> SortOptions.of(s -> s.field(f -> f
                            .field("imdb_rating").order(SortOrder.Desc)
                            .missing(JsonData.of("_last"))));
    default            -> SortOptions.of(s -> s.field(f -> f
                            .field("title.raw").order(SortOrder.Asc)));
};
```

### Pattern 4: Pagination — load-more (page size 20)

```java
// [ASSUMED] page size 20 — fits 5-column poster grid × 4 rows
int PAGE_SIZE = 20;
int from = page * PAGE_SIZE;

SearchRequest searchReq = SearchRequest.of(r -> r
    .index(indexName)
    .query(combinedQuery)
    .sort(sort)
    .from(from)
    .size(PAGE_SIZE)
    // Source filter: only return fields needed by UI — keep response small
    .source(sf -> sf.filter(f -> f.includes(List.of(
        "tmdb_id", "title", "year", "poster_path",
        "director_list", "genre_list", "imdb_rating", "runtime"
    )))));

SearchResponse<Map> response = client.search(searchReq, Map.class);
long total = response.hits().total().value();
// hasMore = (from + PAGE_SIZE) < total
```

### Pattern 5: Dashboard Aggregations

```java
// All aggregations in a single OS request (size=0 — no hits needed)
// [ASSUMED] agg field names and bucket ranges — consistent with data-model.md types
SearchRequest dashReq = SearchRequest.of(r -> r
    .index(indexName)
    .size(0)
    .aggregations("total_count", Aggregation.of(a -> a
        .valueCount(vc -> vc.field("tmdb_id"))))  // count of indexed docs
    .aggregations("genres", Aggregation.of(a -> a
        .terms(t -> t.field("genre_list").size(10))))
    .aggregations("languages", Aggregation.of(a -> a
        .terms(t -> t.field("language_list").size(20))))
    .aggregations("imdb_histogram", Aggregation.of(a -> a
        .range(range -> range
            .field("imdb_rating")
            .ranges(List.of(
                AggregationRange.of(ar -> ar.key("1-2").from("1.0").to("3.0")),
                AggregationRange.of(ar -> ar.key("3-4").from("3.0").to("5.0")),
                AggregationRange.of(ar -> ar.key("5-6").from("5.0").to("7.0")),
                AggregationRange.of(ar -> ar.key("7-8").from("7.0").to("9.0")),
                AggregationRange.of(ar -> ar.key("9-10").from("9.0").to("10.1"))
            ))))));

// Parse aggregation results from response.aggregations()
```

### Pattern 6: Movie of the Day — function_score with date-based seed

```java
// Date seed: ISO date string hashCode → stable integer for the day
// [ASSUMED] _seq_no as seed field — OpenSearch recommends a stable numeric field
String today = LocalDate.now().toString(); // "2026-05-17"
long dateSeed = today.hashCode(); // stable within a day; changes at midnight

Query motdQuery = Query.of(q -> q
    .functionScore(fs -> fs
        .query(inner -> inner.matchAll(m -> m))  // all films (or watched=false once Phase 6 sets it)
        .functions(List.of(FunctionScore.of(f -> f
            .randomScore(rs -> rs
                .seed(String.valueOf(dateSeed))
                .field("_seq_no")))))
        .boostMode(FunctionBoostMode.Replace)));

SearchRequest motdReq = SearchRequest.of(r -> r
    .index(indexName)
    .query(motdQuery)
    .size(1)
    .source(sf -> sf.filter(f -> f.includes(List.of(
        "title", "year", "poster_path", "tmdb_id"
    )))));
```

### Pattern 7: Recently Added — Postgres query

Recently added is fetched from Postgres, NOT OpenSearch, to avoid modifying the OS mapping.

```java
// Add to MovieRepository:
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NOT NULL " +
       "ORDER BY m.indexedAt DESC")
List<Movie> findTop10ByUserIdOrderByIndexedAtDesc(@Param("userId") UUID userId, Pageable pageable);

// In DashboardService:
List<Movie> recent = movieRepository.findTop10ByUserIdOrderByIndexedAtDesc(
    userId, PageRequest.of(0, 10));
// Extract posterPath from rawTmdbJson.poster_path — same logic as DocumentBuilder
```

### Pattern 8: Autocomplete — match_phrase_prefix on analyzed sub-fields

```java
// Director autocomplete: prefix query on director_list.text
// Returns distinct director names from matching documents
// [ASSUMED] for implementation approach — simple and works with custom_english_analyzer
SearchRequest autocompleteReq = SearchRequest.of(r -> r
    .index(indexName)
    .query(q -> q.matchPhrasePrefix(m -> m
        .field("director_list.text")
        .query(prefix)))
    .size(20)
    .source(sf -> sf.filter(f -> f.includes(List.of("director_list")))));

// Same pattern for actor autocomplete using full_cast_names.text
```

### Pattern 9: Full SearchRequest Assembly

```java
// Source: opensearch-java SearchRequest (verified in IndexingIntegrationTest + ReindexControllerTest)
public SearchResponse<Map> search(SearchRequest searchReq) throws IOException {
    return client.search(searchReq, Map.class);
}
```

### Pattern 10: URL Query Param Strategy (Frontend)

**URL param naming convention:**
```
/search?q=inception
/search?q=&genre=Action&genre=Thriller&director=Nolan&sort=imdb_desc&page=0
/search?genre=Action&year_from=2000&year_to=2010&imdb_from=7.0&runtime_max=120&watched=false
```

**Reading params in useSearch.ts:**
```typescript
// Source: Nuxt 3 useRoute/useRouter [VERIFIED: existing auth.global.ts and middleware pattern]
const route = useRoute()
const router = useRouter()

// Read from URL on mount and when route changes
const query = computed(() => (route.query.q as string) ?? '')
const genres = computed(() => {
  const g = route.query.genre
  return Array.isArray(g) ? g : g ? [g] : []
})

// Write to URL (replaces history state — back button works)
function updateUrl(params: Record<string, string | string[] | null>) {
  router.replace({ query: { ...route.query, ...params } })
}
```

**Debounce pattern (no VueUse — not installed):**
```typescript
// Manual debounce with watchEffect cleanup
let debounceTimer: ReturnType<typeof setTimeout> | null = null

watch(searchQuery, (newVal) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    updateUrl({ q: newVal, page: '0' })  // reset page on query change
  }, 300)
})
```

### Anti-Patterns to Avoid
- **Querying `full_cast` or `full_crew` (nested type) for name search:** Use `full_cast_names.text` and `full_crew_names.text` instead — much faster. [VERIFIED: data-model.md note]
- **Sorting on `title` (analyzed text field):** Use `title.raw` — sorting on text fields throws. [VERIFIED: data-model.md]
- **Missing `missing: "_last"` on nullable numeric sort fields:** `personal_rating` and `imdb_rating` can be null. Without this, null docs sort first, pushing rated films off the first page.
- **Putting filters in `bool.must` instead of `bool.filter`:** Filters in `must` contribute to `_score` and disable filter caching. Use `bool.filter` for all structural filters.
- **Forgetting `_refresh` in integration tests:** OpenSearch is near-real-time. The `refreshIndex()` helper in `IndexingIntegrationTest` MUST be called after indexing before asserting search results. [VERIFIED: IndexingIntegrationTest.refreshIndex()]
- **Building SearchController like ReindexController (path variable for userId):** SearchController resolves userId internally from `auth.getName()` — the user never passes their own userId in the URL.
- **VueUse for debounce:** VueUse is not in package.json. Use a manual setTimeout debounce pattern consistent with the existing add.vue polling pattern.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Relevance ranking | Custom scoring logic in Java | OpenSearch bool/should with field boost weights | OpenSearch BM25 already tuned; per-field boosts are the standard knob |
| Filter caching | Manual result caching in Spring | bool.filter context (OS filter cache) | OS filter cache is automatic in filter context; free performance |
| Full-text stemming/folding | Text normalization in Java | custom_english_analyzer on `.text` sub-fields already in mapping | Analyzer already built and applied at index time |
| Debounce | External library (VueUse not installed) | Manual setTimeout/clearTimeout pattern | Consistent with existing add.vue pattern; no new dependency |
| URL state management | Custom location.search parsing | useRoute/useRouter (Nuxt built-in) | Handles encoding, back-button, SSR hydration correctly |
| View mode persistence | Component-local state | Pinia store (useSearchStore) | Consistent with useAuthStore; persists across navigation |
| Index refresh in tests | Custom polling loop | `client.generic().execute(POST /_refresh)` | Already established in IndexingIntegrationTest.refreshIndex() |

---

## Common Pitfalls

### Pitfall 1: near-real-time search in integration tests

**What goes wrong:** Integration test indexes a document then immediately searches and gets 0 results.
**Why it happens:** OpenSearch is near-real-time (1-second default refresh interval). Newly indexed documents are not visible to search until the index is refreshed.
**How to avoid:** Call `refreshIndex(indexName)` (the helper from IndexingIntegrationTest) after every indexing operation in a test, before asserting search results.
**Warning signs:** Intermittent test failures where a search returns 0 results for a known-indexed document.

### Pitfall 2: Null OMDB fields in range filters

**What goes wrong:** Range filter on `imdb_rating` throws or returns unexpected results.
**Why it happens:** Documents where `imdb_rating=null` (no OMDB data) exist in the index. OpenSearch range queries naturally exclude null-value docs — this is the correct behavior for Phase 5.
**How to avoid:** Do NOT add a null-check workaround; the exclusion of null-rated films when the user filters by rating is intentional. Document this behavior in code comments.
**Warning signs:** Thinking you need an `exists` query combined with the range — you don't.

### Pitfall 3: Sorting on text fields

**What goes wrong:** `sort by title` throws `IllegalArgumentException` or `400 Bad Request` from OpenSearch.
**Why it happens:** The `title` field has type `text` (analyzed); you cannot sort on analyzed text fields.
**How to avoid:** Always sort on `title.raw` (the keyword sub-field). Check data-model.md — every sortable string field has a `.raw` sub-field. [VERIFIED: data-model.md]
**Warning signs:** 400 response from OS with "fielddata disabled on text fields" message.

### Pitfall 4: auth.getName() returns email, not UUID

**What goes wrong:** `UUID.fromString(auth.getName())` throws `IllegalArgumentException`.
**Why it happens:** `UserDetailsServiceImpl` sets username = email. JWT subject = email. `auth.getName()` returns the email string, not the UUID.
**How to avoid:** Follow the ReindexController pattern exactly: `userRepository.findByEmail(auth.getName()).orElseThrow()` then `.getId()`. [VERIFIED: State.md accumulated context, ReindexController.java]
**Warning signs:** UUID parsing exception in SearchController on first request.

### Pitfall 5: URL params as string vs. array in Vue

**What goes wrong:** `route.query.genre` is a string when one genre is selected, and an array when multiple are selected.
**Why it happens:** `useRoute().query` returns `string | string[] | null` for repeated params.
**How to avoid:** Always normalize: `Array.isArray(g) ? g : g ? [g as string] : []`
**Warning signs:** Filter works for 2 genres but breaks for exactly 1 genre.

### Pitfall 6: Not-yet-watched filter returns nothing until Phase 6

**What goes wrong:** User enables "not yet watched" toggle, gets zero results.
**Why it happens:** `watched` field is `null` in all Phase 4/5 documents (D-05). A `term(watched=false)` query matches only explicit `false` values, not null.
**How to avoid:** This is expected behavior per D-10. Document it in the UI with a tooltip: "Requires film status to be set (coming soon)." Do NOT try to work around it with an `exists` query — that would return everything, defeating the purpose.
**Warning signs:** Treating this as a bug and spending time "fixing" it.

### Pitfall 7: Aggregations with size=0 but forgetting index might be empty

**What goes wrong:** Dashboard crashes when user has zero indexed films.
**Why it happens:** Aggregation response is valid even with 0 hits, but parsing code might NPE on empty bucket lists.
**How to avoid:** Null-check bucket lists in DashboardService before iterating. Return sensible empty-state response (totalFilms=0, empty lists).
**Warning signs:** 500 error on dashboard for a new user who hasn't indexed any films yet.

---

## Code Examples

### SearchController pattern (auth ownership)

```java
// Source: ReindexController.java (verified in codebase)
@RestController
@RequestMapping("/search")
@Slf4j
public class SearchController {

    private final SearchService searchService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<SearchResponse> search(
            @RequestBody SearchRequest request,
            Authentication auth) throws IOException {
        UUID userId = resolveUserId(auth);
        String indexName = "movies-" + userId;
        return ResponseEntity.ok(searchService.search(indexName, request));
    }

    @GetMapping("/autocomplete")
    public ResponseEntity<AutocompleteResponse> autocomplete(
            @RequestParam String field,
            @RequestParam String prefix,
            Authentication auth) throws IOException {
        UUID userId = resolveUserId(auth);
        String indexName = "movies-" + userId;
        return ResponseEntity.ok(searchService.autocomplete(indexName, field, prefix));
    }

    private UUID resolveUserId(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
                .getId();
    }
}
```

### SearchIntegrationTest skeleton

```java
// Source: ReindexControllerTest.java pattern (verified in codebase)
@AutoConfigureMockMvc
class SearchControllerTest extends AbstractOpenSearchTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private IndexingService indexingService;
    @Autowired private OpenSearchClient client;

    private User testUser;
    private String bearerToken;

    @BeforeEach
    void setUp() throws Exception {
        movieRepository.deleteAll();
        userRepository.deleteAll();
        testUser = createActiveUser("search-test@example.com");
        bearerToken = loginAndGetToken("search-test@example.com");

        String indexName = "movies-" + testUser.getId();
        deleteIndexIfExists(indexName);
    }

    @Test
    void shouldReturnAllFilms_whenQueryIsEmpty() throws Exception {
        // Index 2 films, search with empty query, expect both in title A-Z order
        indexTestMovie("Zorro", "Action");
        indexTestMovie("Amelie", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\",\"sort\":\"title_asc\",\"page\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].title").value("Amelie"))
            .andExpect(jsonPath("$.results[1].title").value("Zorro"))
            .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void shouldFilterByGenre_usingORLogic() throws Exception {
        // Genre filter: OR within group (D-11)
        indexTestMovie("Inception", "Thriller", "Sci-Fi");
        indexTestMovie("Avatar", "Sci-Fi");
        indexTestMovie("Titanic", "Romance");
        refreshIndex("movies-" + testUser.getId());

        mockMvc.perform(post("/search")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filters\":{\"genres\":[\"Thriller\",\"Sci-Fi\"]},\"page\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2)); // Inception + Avatar
    }

    // ... helpers: createActiveUser, loginAndGetToken, indexTestMovie, refreshIndex, deleteIndexIfExists
}
```

### useSearch composable skeleton (TypeScript)

```typescript
// Source: useMovies.ts pattern (verified in codebase); Nuxt 3 useRoute/useRouter [VERIFIED]
export function useSearch() {
  const route = useRoute()
  const router = useRouter()
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  // Reactive state
  const query = ref((route.query.q as string) ?? '')
  const page = ref(parseInt((route.query.page as string) ?? '0'))
  const sort = ref((route.query.sort as string) ?? 'title_asc')
  const isLoading = ref(false)
  const results = ref<SearchResultItem[]>([])
  const total = ref(0)
  const hasMore = ref(false)

  // Filter state (read from URL)
  const genres = computed(() => normalizeQueryParam(route.query.genre))
  const director = computed(() => (route.query.director as string) ?? '')
  // ... other filter params

  // Debounce: 300ms on query text changes
  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  watch(query, (val) => {
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      router.replace({ query: { ...route.query, q: val, page: '0' } })
    }, 300)
  })

  // Watch route for changes (from clickable attribute navigation D-14)
  watch(() => route.query, () => { executeSearch() }, { immediate: true })

  async function executeSearch() {
    isLoading.value = true
    try {
      const body = buildRequestBody()
      const response = await $fetch<SearchApiResponse>('/api/search', {
        method: 'POST',
        body,
        credentials: 'include',
        headers: authHeaders(),
      })
      if (page.value === 0) {
        results.value = response.results
      } else {
        results.value = [...results.value, ...response.results]  // load-more appends
      }
      total.value = response.total
      hasMore.value = response.hasMore
    } finally {
      isLoading.value = false
    }
  }

  function normalizeQueryParam(val: string | string[] | null | undefined): string[] {
    if (!val) return []
    return Array.isArray(val) ? val as string[] : [val as string]
  }

  return { query, page, sort, results, total, hasMore, isLoading, executeSearch, genres, director }
}
```

### MSW handler for search endpoint

```typescript
// Source: handlers/movies.ts pattern (verified in codebase)
export const searchHandlers = [
  http.post('/api/search', async ({ request }) => {
    const body = await request.json() as any
    return HttpResponse.json({
      results: [
        {
          id: 'test-uuid-1',
          tmdbId: 27205,
          title: 'Inception',
          year: 2010,
          posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg',
          directorList: ['Christopher Nolan'],
          genreList: ['Sci-Fi', 'Thriller'],
          imdbRating: 8.8,
          runtime: 148,
        },
      ],
      total: 1,
      page: 0,
      totalPages: 1,
      hasMore: false,
    })
  }),

  http.get('/api/dashboard', () => {
    return HttpResponse.json({
      totalFilms: 42,
      topGenres: [{ name: 'Drama', count: 12 }, { name: 'Thriller', count: 8 }],
      languageBreakdown: [{ code: 'en', count: 30 }],
      imdbHistogram: [
        { label: '7-8', count: 15 },
        { label: '9-10', count: 5 },
      ],
      movieOfTheDay: { id: 'test-uuid-2', title: 'The Godfather', year: 1972, posterPath: '/xxx.jpg' },
      recentlyAdded: [
        { id: 'test-uuid-1', title: 'Inception', year: 2010, posterPath: '/yyy.jpg' },
      ],
    })
  }),

  http.get('/api/search/autocomplete', ({ request }) => {
    const url = new URL(request.url)
    return HttpResponse.json({ suggestions: ['Christopher Nolan', 'Christopher Nolan Jr.'] })
  }),
]
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| RestClientTransport (deprecated) | ApacheHttpClient5Transport | opensearch-java 2.x | Must use ApacheHttpClient5TransportBuilder — already implemented in OpenSearchConfig.java |
| Fluent multi_match query | bool/should with per-field boosts | Modern OpenSearch practice | Better relevance control; boosts are tunable per field |
| VueUse watchDebounced | Manual setTimeout debounce | N/A (VueUse not installed) | Manual debounce is fine for a single use case |
| withJson on CreateIndexRequest.Builder | generic client PUT (bug #1510) | opensearch-java 2.19.0 | Already worked around in IndexingService.ensureIndexExists() — no change needed |

---

## Existing Codebase Patterns to Reuse

These patterns are VERIFIED in the codebase and must be followed exactly:

| Pattern | Source File | How Phase 5 Uses It |
|---------|-------------|---------------------|
| auth.getName() → email → findByEmail() → getId() | ReindexController.java:68-73 | SearchController.resolveUserId() — identical pattern |
| AbstractOpenSearchTest base class | AbstractOpenSearchTest.java | SearchControllerTest and SearchServiceTest extend this |
| refreshIndex() helper | IndexingIntegrationTest.java:237-244 | Copy to SearchIntegrationTest helper methods |
| deleteIndexIfExists() | ReindexControllerTest.java:112-122 | Copy to SearchIntegrationTest @BeforeEach cleanup |
| loginAndGetToken() / createActiveUser() | ReindexControllerTest.java:61-78 | Copy to SearchIntegrationTest helpers |
| Poster grid layout (grid-cols-2 sm:3 md:4 lg:5) | add.vue:126 | MovieGrid.vue reuses same Tailwind classes |
| posterUrl() function | add.vue:98-100 | MovieCard.vue and MovieListItem.vue use same logic |
| useCookie + authHeaders() pattern | useMovies.ts:24-29 | useSearch.ts uses identical auth header injection |
| MSW handler module export | handlers/movies.ts | handlers/search.ts follows same structure |
| @AutoConfigureMockMvc + MockMvc + @SpringBootTest | ReindexControllerTest.java | SearchControllerTest uses same test setup |
| buildSuccessMovie() / persistMovie() | IndexingIntegrationTest.java / ReindexControllerTest.java | Search tests need similar movie fixture helpers |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers (BE); Vitest 3.1.3 + @nuxt/test-utils + MSW (FE) |
| BE config file | build.gradle.kts (useJUnitPlatform()) |
| FE config file | frontend/vitest.config.ts |
| BE quick run | `cd backend && ./gradlew test --tests "de.moviearchive.search.*"` |
| BE full suite | `cd backend && ./gradlew test` |
| FE quick run | `cd frontend && pnpm test` |
| FE full suite | `cd frontend && pnpm test:coverage` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File |
|--------|----------|-----------|-------------------|------|
| SRCH-01 | Empty query returns all films sorted title A-Z | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldReturnAllFilms_whenQueryIsEmpty"` | SearchControllerTest.java (Wave 0) |
| SRCH-01 | Free-text query matches title with boost | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFindFilmByTitle"` | SearchControllerTest.java (Wave 0) |
| SRCH-01 | Free-text query matches overview (lower score) | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFindFilmByOverview"` | SearchControllerTest.java (Wave 0) |
| SRCH-01 | Accent-folded query finds accented title | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldNormalizeAccentsInSearch"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | Genre filter (single) returns matching films only | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterBySingleGenre"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | Genre filter (multi) uses OR logic | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterByMultipleGenresOR"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | Director filter matches director name | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterByDirector"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | Year range filter excludes out-of-range films | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterByYearRange"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | IMDB rating filter excludes null-rated films | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterByImdbRating"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | Combined genre+director filter uses AND logic | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldCombineGenreAndDirectorFilters"` | SearchControllerTest.java (Wave 0) |
| SRCH-02 | Watched=false filter returns nothing until Phase 6 | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldReturnEmpty_whenWatchedFilterApplied"` | SearchControllerTest.java (Wave 0) |
| SRCH-03 | Sort title A-Z returns Amelie before Zorro | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldSortByTitleAscending"` | SearchControllerTest.java (Wave 0) |
| SRCH-03 | Sort IMDB desc puts highest-rated first | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldSortByImdbRatingDescending"` | SearchControllerTest.java (Wave 0) |
| SRCH-03 | Sort personal rating desc: null values sort last | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldSortByPersonalRating_nullsLast"` | SearchControllerTest.java (Wave 0) |
| SRCH-04 | useSearch reads genre param from URL on mount | Unit (FE) | `pnpm test -- useSearch` | useSearch.spec.ts (Wave 0) |
| SRCH-04 | useSearch reads director param from URL | Unit (FE) | `pnpm test -- useSearch` | useSearch.spec.ts (Wave 0) |
| SRCH-01 | Search page executes search on mount | Component (FE) | `pnpm test -- search` | pages/search.spec.ts (Wave 0) |
| Dashboard | Dashboard loads stats and movie of the day | Component (FE) | `pnpm test -- index` | pages/index.spec.ts (Wave 0) |
| Auth | /search redirects to /login when unauthenticated | Middleware (FE) | already covered by auth.spec.ts | auth.global.ts (existing) |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "de.moviearchive.search.*"` (BE) + `pnpm test` (FE)
- **Per wave merge:** `./gradlew test` (full BE suite)
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps (files to create in first plan)

**Backend:**
- [ ] `backend/src/test/java/de/moviearchive/search/SearchControllerTest.java` — @Disabled stubs for all SRCH-01 through SRCH-04 tests listed above

**Frontend:**
- [ ] `frontend/test/mocks/handlers/search.ts` — MSW handlers for /api/search, /api/dashboard, /api/search/autocomplete
- [ ] `frontend/test/unit/composables/useSearch.spec.ts` — @Disabled / `.todo()` stubs
- [ ] `frontend/test/unit/pages/search.spec.ts` — @Disabled / `.todo()` stubs
- [ ] `frontend/test/unit/pages/index.spec.ts` — @Disabled / `.todo()` stubs

---

## Security Domain

`security_enforcement` is not explicitly set to false in config.json — treating as enabled.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | JWT via JwtAuthFilter — already implemented; SearchController needs no additional auth code beyond `Authentication auth` parameter |
| V3 Session Management | no | Sessions managed by Phase 1; search is read-only |
| V4 Access Control | yes | Index scoping: every OS query is scoped to `movies-{userId}` derived from authenticated JWT subject. No cross-user data leakage possible. |
| V5 Input Validation | yes | SearchRequest DTO uses @Valid; filter values are passed to OS query builders (parameterized, not string-interpolated — no OS injection risk) |
| V6 Cryptography | no | Search is read-only; no new cryptography |

### Known Threat Patterns for this Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| IDOR — user queries another user's index | Elevation of Privilege | userId always derived from JWT (auth.getName() → email → userId); never from request body/path |
| OS query injection via filter values | Tampering | opensearch-java query builders use parameterized FieldValue — string values are never interpolated into raw query JSON |
| Large result sets / resource exhaustion | Denial of Service | from/size capped at PAGE_SIZE=20 per request; aggregation size capped at 20; no unbounded queries |

---

## Open Questions

1. **`indexed_at` field in OS document for recently-added sort**
   - What we know: `indexed_at` is a Postgres column on the Movie entity, not in the OS mapping.
   - What's unclear: Is it better to query Postgres for recently-added, or add `indexed_at` to the OS mapping?
   - Recommendation: Query Postgres (simpler, no mapping change). `MovieRepository.findTop10ByUserIdOrderByIndexedAtDesc()` already has access to `rawTmdbJson` for poster extraction.

2. **Autocomplete case sensitivity**
   - What we know: terms agg `include` pattern is case-sensitive; `match_phrase_prefix` on `.text` sub-field goes through `custom_english_analyzer` (lowercase filter).
   - What's unclear: User input for director autocomplete — typed as "nolan" should match "Christopher Nolan".
   - Recommendation: Use `match_phrase_prefix` on `director_list.text` — the lowercase filter in the analyzer normalizes both query and field values, so case mismatch is handled automatically. [ASSUMED]

3. **Dashboard for empty archive**
   - What we know: New users have no indexed films. Aggregations return valid empty responses.
   - What's unclear: Should the dashboard show an empty state or redirect to /add?
   - Recommendation: Show an empty state (totalFilms=0, empty genre/language lists, no movie of the day) with a prominent "Add your first film" CTA linking to /add.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java (JDK 25) | Backend compilation | Yes | openjdk 25.0.3 | — |
| Node.js | Frontend build | Yes | v25.9.0 | — |
| Docker | OpenSearch Testcontainers | Yes | 29.3.1 | — |
| OpenSearch container image | Integration tests (AbstractOpenSearchTest) | Pulled on first test run | opensearchproject/opensearch:2.19.0 | — |
| PostgreSQL container image | Integration tests (AbstractIntegrationTest) | Pulled | postgres:16-alpine | — |

**Missing dependencies with no fallback:** none.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Boost values (title=4.0, director=3.0, etc.) | Code Examples / Pattern 1 | Wrong ranking — tunable after delivery; no correctness risk |
| A2 | `_seq_no` as seed field for random_score | Pattern 6 (Movie of the Day) | OS may not support this field in older 2.x versions; fallback to omitting seed field (non-deterministic) |
| A3 | `match_phrase_prefix` on `.text` sub-field for autocomplete | Pattern 8 | Might return too many results or wrong results; switchable to terms agg prefix approach |
| A4 | Load-more pagination (page size 20) preferred over infinite scroll | Pattern 4 | User might prefer infinite scroll; easy to change since pagination is isolated in useSearch |
| A5 | `hashCode()` on ISO date string as day seed | Pattern 6 | Negative hashCode values are valid for OS seed; no risk |
| A6 | Recently-added queried from Postgres rather than OS | Pattern 7 / Architecture | Works but adds a Postgres round-trip to dashboard; acceptable for personal-use scale |

---

## Sources

### Primary (HIGH confidence)
- `backend/src/main/java/de/moviearchive/indexing/IndexingService.java` — OpenSearch client query builder patterns, generic client usage, ensureIndexExists implementation
- `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` — SearchRequest builder, refreshIndex pattern, test fixture patterns
- `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — MockMvc + JWT login pattern, ownership check pattern, persistMovie helper
- `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` — GenericContainer setup for OpenSearch 2.19.0
- `.claude/data-model.md` — Complete field mapping, sub-field names (.raw, .text), type information, analyzer assignment
- `.planning/phases/05-search/05-CONTEXT.md` — All locked decisions D-01 through D-17
- `frontend/composables/useMovies.ts` — Auth header pattern, $fetch usage, composable structure
- `frontend/pages/add.vue` — Poster grid layout, posterUrl function, polling pattern
- `frontend/test/mocks/handlers/movies.ts` — MSW handler structure to replicate

### Secondary (MEDIUM confidence)
- `CLAUDE.md` §OpenSearch Java Client 2.19.0 — ApacheHttpClient5Transport, what NOT to do (deprecated RestClientTransport, spring-data-opensearch)
- `CLAUDE.md` §JWT Authentication — auth.getName() = email confirmation

### Tertiary (LOW confidence — see Assumptions Log)
- OpenSearch function_score random_score with `_seq_no` seed field (A2) — standard OS practice per training knowledge, not verified via docs in this session
- Per-field boost values for multi_match (A1) — conventional starting values

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all dependencies verified in build.gradle.kts and package.json
- Architecture patterns: HIGH — derived from existing IndexingService, ReindexController, and test infrastructure verified in codebase
- OpenSearch query structure: HIGH for query DSL structure (verified against existing search requests in test files); MEDIUM for boost values and random_score seed field (training knowledge)
- Frontend patterns: HIGH — derived from verified existing composables and pages
- Test strategy: HIGH — extends established AbstractOpenSearchTest + MockMvc + MSW patterns

**Research date:** 2026-05-17
**Valid until:** 2026-06-17 (opensearch-java 2.19.0 is stable; no fast-moving dependencies)
