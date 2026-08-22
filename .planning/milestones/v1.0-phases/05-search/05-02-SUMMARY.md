---
phase: 05-search
plan: 02
subsystem: search-backend
tags: [wave-1, opensearch, search, dashboard, integration-tests, srch-01, srch-02, srch-03, srch-04]
dependency_graph:
  requires: [05-01]
  provides: [SearchController, DashboardController, SearchService, DashboardService, search-dto-package]
  affects: [05-03-PLAN, 05-04-PLAN]
tech_stack:
  added: []
  patterns:
    - bool/should multi-field text query with per-field boost weights
    - bool.filter context for structural filters (no score, cached)
    - FieldSort with .raw keyword sub-field for string sort; missing=FieldValue(_last) for nullable numerics
    - function_score + random_score + date hashCode seed for movie-of-the-day
    - DashboardService aggregations (valueCount, terms, range) in single size=0 request
    - RateLimitService.resetAll() in @BeforeEach for integration tests with many login calls
    - Locale.US in String.format for decimal numbers in test JSON (German JVM locale uses comma)
key_files:
  created:
    - backend/src/main/java/de/moviearchive/search/dto/SearchRequest.java
    - backend/src/main/java/de/moviearchive/search/dto/FilterCriteria.java
    - backend/src/main/java/de/moviearchive/search/dto/SearchResultItem.java
    - backend/src/main/java/de/moviearchive/search/dto/SearchResponse.java
    - backend/src/main/java/de/moviearchive/search/dto/AutocompleteResponse.java
    - backend/src/main/java/de/moviearchive/search/dto/DashboardMovieItem.java
    - backend/src/main/java/de/moviearchive/search/dto/HistogramBucket.java
    - backend/src/main/java/de/moviearchive/search/dto/DashboardResponse.java
    - backend/src/main/java/de/moviearchive/search/SearchService.java
    - backend/src/main/java/de/moviearchive/search/SearchController.java
    - backend/src/main/java/de/moviearchive/search/DashboardService.java
    - backend/src/main/java/de/moviearchive/search/DashboardController.java
  modified:
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/test/java/de/moviearchive/search/SearchControllerTest.java
decisions:
  - SearchService uses PAGE_SIZE=20 constant (hard cap on from/size per T-05-02-03)
  - FieldSort.missing() takes FieldValue, not JsonData — use builder lambda mv -> mv.stringValue("_last")
  - DashboardResponse uses nested records GenreCount/LanguageCount (not raw Map) for typed JSON keys
  - RateLimitService.resetAll() required in @BeforeEach — 14 tests x 1 login each exceeds 10/min Bucket4j limit
  - String.format(Locale.US, ...) required for OMDB imdb_rating test JSON — German JVM locale produces "8,5" not "8.5"
  - movie.setReleaseDate() must be set on entity in test helpers — DocumentBuilder derives year from entity field, not raw TMDB JSON
metrics:
  duration: "~17 minutes"
  completed: "2026-05-17T21:20:42Z"
  tasks_completed: 3
  tasks_total: 3
  files_created: 12
  files_modified: 2
---

# Phase 05 Plan 02: Backend Search + Dashboard API Summary

Full backend search and dashboard implementation: bool/should query with 13-field boosts, 12-filter advanced filtering in bool.filter context, four sort options with null-safe missing values, PAGE_SIZE=20 pagination, match_phrase_prefix autocomplete whitelisted to director/actors, and dashboard aggregations with function_score movie-of-the-day and Postgres recently-added. All 14 SearchControllerTest integration tests enabled and passing; full backend suite green.

## What Was Built

**Task 1 — Search + dashboard DTOs and MovieRepository recently-added query** (commit `dd4fc63`)

Created `de.moviearchive.search.dto` package with 8 types:
- `SearchRequest` (`@Data @Builder`): `query`, `@Valid filters`, `sort`, `@Min(0) page`
- `FilterCriteria` (`@Data @Builder`): all 12 D-10 fields (genres, director, actors, yearFrom, yearTo, imdbRatingFrom, imdbRatingTo, contentRatings, runtimeMax, notYetWatched, languages, countries)
- `SearchResultItem`, `SearchResponse`, `AutocompleteResponse` (records)
- `DashboardMovieItem`, `HistogramBucket` (records)
- `DashboardResponse` record with nested `GenreCount(name, count)` and `LanguageCount(code, count)` types for typed JSON keys matching the MSW contract

Added `findRecentlyIndexedByUserId(UUID userId, Pageable pageable)` to `MovieRepository` using `@Query` + `ORDER BY m.indexedAt DESC` pattern.

**Task 2 — SearchService + SearchController** (commit `6a8c099`)

`SearchService` (`@Service @Slf4j`):
- `search()`: match_all for empty query; 13-field bool/should with boosts (title 4.0 → wikipedia_critics 0.6) for non-empty query; all 12 FilterCriteria fields applied as `bool.filter` clauses (OR within group via `terms`, AND across groups); four sort options using `FieldSort` with `.raw` keyword sub-field for strings and `missing(mv -> mv.stringValue("_last"))` for nullable numerics; PAGE_SIZE=20 cap
- `autocomplete()`: `match_phrase_prefix` on `.text` analyzed sub-field; field whitelisted to `director`/`actors` only (T-05-02-04); deduplicated, capped at 10 suggestions

`SearchController` (`@RestController @RequestMapping("/search")`):
- `POST /search` + `GET /search/autocomplete`
- `resolveUserId` via `userRepository.findByEmail(auth.getName())` — no `UUID.fromString` anywhere
- `@ExceptionHandler` for `MethodArgumentNotValidException` (400) and `IllegalArgumentException` (400)

**Task 3 — DashboardService + DashboardController + enable SearchControllerTest stubs** (commit `496f64c`)

`DashboardService` (`@Service @Transactional @Slf4j`):
- Single size=0 OS request for stats: `total_count` (valueCount on `tmdb_id`), `genres` (terms, size 10), `languages` (terms, size 20), `imdb_histogram` (range agg with 5 buckets 1-2, 3-4, 5-6, 7-8, 9-10)
- Null-safe bucket iteration on every agg result — empty archive returns `totalFilms=0` with empty lists and `movieOfTheDay=null` (Pitfall 7)
- Separate `function_score` request for movie-of-the-day: date-seeded `random_score` with `_seq_no` field, `boostMode=Replace`, `size=1`
- `movieRepository.findRecentlyIndexedByUserId(userId, PageRequest.of(0, 10))` for recently-added

`DashboardController` (`@RestController @RequestMapping("/dashboard")`):
- `GET /dashboard` → resolves userId from JWT email, delegates to DashboardService

`SearchControllerTest` — all 14 stubs enabled with real integration test bodies:
- Added `RateLimitService` injection + `rateLimitService.resetAll()` in `@BeforeEach` (14 logins exceed 10/min limit)
- Added `indexTestMovieWithCrew()`, `indexTestMovieWithYear()`, `indexTestMovieWithImdbRating()` helpers
- Used `Locale.US` in OMDB JSON format strings (German JVM locale produces `"8,5"` not `"8.5"`)
- Set `movie.setReleaseDate()` on entity in year helper (DocumentBuilder derives year from entity, not raw JSON)
- All 14 tests covering: empty query match_all, title search, overview search, accent normalization, single/multi genre filter, director filter, year range, IMDB rating filter, genre+director AND, watched=null returns empty, title A-Z sort, IMDB desc sort, personal_rating nulls-last sort

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | `dd4fc63` | feat(05-02): add search/dashboard DTOs and MovieRepository recently-added query |
| 2 | `6a8c099` | feat(05-02): implement SearchService + SearchController (SRCH-01/02/03) |
| 3 | `496f64c` | feat(05-02): implement DashboardService + DashboardController; enable 14 SearchControllerTest stubs |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] FieldSort.missing() API mismatch**
- **Found during:** Task 2 — compile error
- **Issue:** Research pattern used `JsonData.of("_last")` but `FieldSort.Builder.missing()` takes `FieldValue`, not `JsonData`
- **Fix:** Used lambda builder: `missing(mv -> mv.stringValue("_last"))`
- **Files modified:** `SearchService.java`
- **Commit:** `6a8c099`

**2. [Rule 1 - Bug] SearchResponse name clash with OS client type**
- **Found during:** Task 2 — compile error: "type SearchResponse already defined by single-type-import"
- **Issue:** Both `de.moviearchive.search.dto.SearchResponse` and `org.opensearch.client.opensearch.core.SearchResponse` imported as `SearchResponse`
- **Fix:** Removed OS `SearchResponse` import; used fully-qualified `org.opensearch.client.opensearch.core.SearchResponse<Map>` inline
- **Files modified:** `SearchService.java`
- **Commit:** `6a8c099`

**3. [Rule 1 - Bug] Bucket4j rate limit blocks logins beyond 10 per minute**
- **Found during:** Task 3 — 4 of 14 tests returned HTTP 429 on login
- **Issue:** 14 tests each logging in once exceeds the 10/min Bucket4j limit on `/auth/login`
- **Fix:** Injected `RateLimitService` into test; called `rateLimitService.resetAll()` in `@BeforeEach` (method already exists, was designed for tests)
- **Files modified:** `SearchControllerTest.java`
- **Commit:** `496f64c`

**4. [Rule 1 - Bug] German JVM locale: `%.1f` produces `"8,5"` not `"8.5"`**
- **Found during:** Task 3 — `shouldFilterByImdbRating` and `shouldSortByImdbRatingDescending` returned 0 results
- **Issue:** `String.format("%.1f", 8.5)` on JVM with `Locale.de_DE` produces `"8,5"`; `DocumentBuilder.parseDoubleOrNull("8,5")` throws `NumberFormatException`, returns `null`, so `imdb_rating` is never indexed
- **Fix:** Used `String.format(Locale.US, "%.1f", imdbRating)` in `indexTestMovieWithImdbRating` helper
- **Files modified:** `SearchControllerTest.java`
- **Commit:** `496f64c`

**5. [Rule 1 - Bug] `indexTestMovieWithYear` did not set entity `releaseDate`**
- **Found during:** Task 3 — `shouldFilterByYearRange` returned 0 results
- **Issue:** `DocumentBuilder.build()` derives `year` from `movie.getReleaseDate()` (entity field), not from the TMDB JSON `release_date` key. The helper set the JSON but not the entity field.
- **Fix:** Added `movie.setReleaseDate(LocalDate.of(year, 6, 15))` in `indexTestMovieWithYear`
- **Files modified:** `SearchControllerTest.java`
- **Commit:** `496f64c`

## Known Stubs

None — all search and dashboard backend behavior is fully implemented with real OpenSearch queries. Personal fields (`personal_rating`, `watched`) are null in all Phase 4/5 docs per D-05; the `rating_desc` sort handles this correctly via `missing=_last` and the `notYetWatched` filter correctly returns empty (Pitfall 6 — expected behavior until Phase 6).

## Threat Flags

None — all STRIDE mitigations from the plan's threat register were implemented:
- T-05-02-01 (IDOR): userId always from `resolveUserId(auth)` via email lookup; verified by grep returning 0 actual calls to `UUID.fromString(auth.getName())`
- T-05-02-02 (query injection): all filter values through typed `FieldValue.of()` builders; no string interpolation into raw query JSON
- T-05-02-03 (DoS): PAGE_SIZE=20 hard cap; aggregation sizes capped at 10/20; autocomplete capped at 10; movie-of-day size=1
- T-05-02-04 (field enumeration): autocomplete `field` param whitelisted to `director`/`actors`; `IllegalArgumentException` → 400 for any other value

## Self-Check: PASSED

Files verified:
- FOUND: backend/src/main/java/de/moviearchive/search/dto/SearchRequest.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/FilterCriteria.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/SearchResultItem.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/SearchResponse.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/AutocompleteResponse.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/DashboardMovieItem.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/HistogramBucket.java
- FOUND: backend/src/main/java/de/moviearchive/search/dto/DashboardResponse.java
- FOUND: backend/src/main/java/de/moviearchive/search/SearchService.java
- FOUND: backend/src/main/java/de/moviearchive/search/SearchController.java
- FOUND: backend/src/main/java/de/moviearchive/search/DashboardService.java
- FOUND: backend/src/main/java/de/moviearchive/search/DashboardController.java

Commits verified:
- FOUND: dd4fc63 (DTOs + MovieRepository)
- FOUND: 6a8c099 (SearchService + SearchController)
- FOUND: 496f64c (DashboardService + DashboardController + 14 tests)

Acceptance criteria verified:
- 0 @Disabled annotations in SearchControllerTest
- 0 Assertions.fail() calls in SearchControllerTest
- 0 UUID.fromString(auth.getName()) in search/*.java (actual calls)
- findRecentlyIndexedByUserId present in MovieRepository with Pageable parameter
- Full backend suite: BUILD SUCCESSFUL
