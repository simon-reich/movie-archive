---
phase: 05-search
reviewed: 2026-05-17T10:00:00Z
depth: standard
files_reviewed: 37
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
  - backend/src/main/java/de/moviearchive/search/DashboardController.java
  - backend/src/main/java/de/moviearchive/search/DashboardService.java
  - backend/src/main/java/de/moviearchive/search/SearchController.java
  - backend/src/main/java/de/moviearchive/search/SearchService.java
  - backend/src/main/java/de/moviearchive/search/dto/AutocompleteResponse.java
  - backend/src/main/java/de/moviearchive/search/dto/DashboardMovieItem.java
  - backend/src/main/java/de/moviearchive/search/dto/DashboardResponse.java
  - backend/src/main/java/de/moviearchive/search/dto/FilterCriteria.java
  - backend/src/main/java/de/moviearchive/search/dto/HistogramBucket.java
  - backend/src/main/java/de/moviearchive/search/dto/SearchRequest.java
  - backend/src/main/java/de/moviearchive/search/dto/SearchResponse.java
  - backend/src/main/java/de/moviearchive/search/dto/SearchResultItem.java
  - backend/src/test/java/de/moviearchive/search/SearchControllerTest.java
  - frontend/components/AppNav.vue
  - frontend/components/DashboardStats.vue
  - frontend/components/FilterPanel.vue
  - frontend/components/ImdbHistogram.vue
  - frontend/components/MovieCard.vue
  - frontend/components/MovieGrid.vue
  - frontend/components/MovieList.vue
  - frontend/components/MovieListItem.vue
  - frontend/components/MovieOfTheDay.vue
  - frontend/components/SearchBar.vue
  - frontend/components/SortSelect.vue
  - frontend/components/ViewToggle.vue
  - frontend/composables/useDashboard.ts
  - frontend/composables/useSearch.ts
  - frontend/pages/index.vue
  - frontend/pages/search.vue
  - frontend/stores/search.ts
  - frontend/test/mocks/handlers.ts
  - frontend/test/mocks/handlers/search.ts
  - frontend/test/unit/components/AppHome.spec.ts
  - frontend/test/unit/composables/useSearch.spec.ts
  - frontend/test/unit/pages/index.spec.ts
  - frontend/test/unit/pages/search.spec.ts
findings:
  critical: 0
  warning: 5
  info: 4
  total: 9
status: issues_found
---

# Phase 05: Code Review Report

**Reviewed:** 2026-05-17T10:00:00Z
**Depth:** standard
**Files Reviewed:** 37
**Status:** issues_found

## Summary

Phase 5 delivers search (POST /search with filters/sort/pagination), autocomplete (GET /search/autocomplete), and dashboard (GET /dashboard). The backend implementation is solid: index-per-user scoping is enforced server-side, query injection is prevented via typed FieldValue builders, and the OpenSearch client is used correctly with ApacheHttpClient5Transport. The frontend state management through URL params is a clean design choice.

Five warnings and four info items were found. No critical (security or data-loss) issues exist. The most impactful warning is an integer overflow risk in `SearchService` when computing `from` for deep pagination. Three additional warnings relate to a type mismatch between what `FilterPanel` sends for `actors` versus what the backend expects, a missing error state in `useDashboard`, and an unvalidated `page` upper bound that could cause large OpenSearch `from` values. The info items are dead imports, a redundant import block, a duplicated JSON builder helper, and missing test coverage for the autocomplete endpoint.

## Warnings

### WR-01: Integer overflow when computing `from` for deep pagination

**File:** `backend/src/main/java/de/moviearchive/search/SearchService.java:75`

**Issue:** `int from = page * PAGE_SIZE` multiplies two `int` values. When `page` is large (e.g., `page = 107_374_183`, `PAGE_SIZE = 20`) the product overflows `int` silently and produces a negative or wrong `from` value that is passed directly to OpenSearch. OpenSearch rejects negative `from` with an exception, which surfaces as a 500 to the caller. The `@Min(0)` constraint on `SearchRequest.page` prevents negative values but puts no upper bound on `page`.

**Fix:**
```java
// Use long arithmetic, then cast — fails fast with a meaningful 400 before hitting OpenSearch
long from = (long) page * PAGE_SIZE;
if (from > 10_000) {
    throw new IllegalArgumentException("Page number exceeds maximum depth (10 000 results).");
}
// pass (int) from to .from()
```
OpenSearch itself enforces a default `index.max_result_window` of 10 000, so capping early produces a clean 400 rather than a 500.

---

### WR-02: `actors` filter sent as comma-joined string but backend expects a single text-match field

**File:** `frontend/composables/useSearch.ts:107`

**Issue:** `actors` is read from the URL as an array (`normalizeQueryParam(route.query.actors)`) and then joined with a comma before being placed in the filter body:
```ts
if (actors.value.length > 0) f.actors = actors.value.join(',')
```
The backend `FilterCriteria.actors` is a `String` field matched against `full_cast_names.text` using a single `match` query. Joining multiple actor names with a comma (`"Cate Blanchett,Brad Pitt"`) will produce an unexpected match query against `full_cast_names.text` using that literal comma-delimited string. For single-actor searches this is harmless, but the data model is inconsistent: the URL treats `actors` as multi-value while the backend filter only supports one value per query execution. This will produce wrong results if a user ever navigates via a URL containing multiple `actors` params.

**Fix:** Either (a) restrict the `actors` URL param to a single value (consistent with how `director` works — `paramAsString` rather than `normalizeQueryParam`), or (b) change `FilterCriteria.actors` to `List<String>` on the backend and build a separate `match` filter per actor (AND logic). Option (a) is the simpler fix for Phase 5:

```ts
// useSearch.ts — treat actors as a single string, consistent with director
const actors = computed(() => paramAsString(route.query.actors))
// ...
if (actors.value) f.actors = actors.value
```
And update the return value and the `FilterPanel` template accordingly so `actorsInput` writes a single string, not an array.

---

### WR-03: `useDashboard` swallows fetch errors silently — no error state exposed

**File:** `frontend/composables/useDashboard.ts:35-45`

**Issue:** `fetchDashboard` has a `try/finally` block but no `catch`. If the `/api/dashboard` request fails (network error, 401 token expiry, 500), `isLoading` is reset to `false` and `data` stays `null`. The `index.vue` page renders the "Your archive is empty" empty-state instead of an error message, and the user has no way to distinguish "failed fetch" from "truly empty archive".

**Fix:**
```ts
const error = ref<string | null>(null)

async function fetchDashboard(): Promise<void> {
  isLoading.value = true
  error.value = null
  try {
    data.value = await $fetch<DashboardResponse>('/api/dashboard', {
      credentials: 'include',
      headers: authHeaders(),
    })
  } catch (e) {
    error.value = 'Failed to load dashboard. Please refresh.'
  } finally {
    isLoading.value = false
  }
}

return { data, isLoading, error, fetchDashboard }
```
Then in `index.vue` add a `v-else-if="error"` branch to display the error before the empty-state branch.

---

### WR-04: `FilterPanel` `clearAllFilters` uses wrong URL param key for genres

**File:** `frontend/components/FilterPanel.vue:87`

**Issue:** In `clearAllFilters()`, genres are cleared with:
```ts
updateFilter('genre', null)
```
But the URL key used everywhere else for genres is also `'genre'` (checked via `route.query.genre` in `useSearch.ts:75`). This is consistent. However, the `actors` URL param key used in `clearAllFilters` is `'actors'` (line 89):
```ts
updateFilter('actors', null)
```
But the URL query param read by `useSearch.ts` is `route.query.actors` — that part is fine. The real mismatch is that `FilterPanel` reads `actors` from `useSearch()` as a computed ref that returns an **array** (via `normalizeQueryParam`), yet `FilterPanel` binds the actors input as a plain text `<input>` whose `@change` handler calls:
```ts
@change="updateFilter('actors', actorsInput || null)"
```
where `actorsInput` is a plain `string`. The `updateFilter` function accepts `string | string[] | null`, so this is type-safe, but it means actors is sometimes treated as a string (text input) and sometimes as an array (the `actors` computed ref used in `hasActiveFilters`). `actors.value.length > 0` on line 104 will throw a runtime error if `actors.value` is a string (strings have `.length` but the comparison against `> 0` is semantically wrong for non-empty single-char strings being falsy).

**Fix:** Make the `actors` URL param consistent — treat it as a single string both in `useSearch.ts` and `FilterPanel.vue`. See WR-02 fix above.

---

### WR-05: `DashboardService` is annotated `@Transactional` but performs no database writes

**File:** `backend/src/main/java/de/moviearchive/search/DashboardService.java:35`

**Issue:** `@Transactional` on a read-only service that calls OpenSearch (I/O outside any transaction) and one JPA read query is not harmful, but it incorrectly opens a read-write transaction for every dashboard request. The JPA call `movieRepository.findRecentlyIndexedByUserId` is a read; wrapping it in a read-write transaction holds a connection from the pool for the entire duration of the two OpenSearch network calls (stats + MOTD), which can be 50-200ms each. Under load this unnecessarily exhausts the connection pool.

**Fix:** Replace with `@Transactional(readOnly = true)` to correctly signal read-only intent and allow the JPA provider to optimize accordingly, and to release the connection before the OpenSearch I/O if connection-per-method-call is ever adopted:
```java
@Transactional(readOnly = true)
public class DashboardService {
```

---

## Info

### IN-01: Autocomplete endpoint is not covered by any backend test

**File:** `backend/src/test/java/de/moviearchive/search/SearchControllerTest.java`

**Issue:** `SearchControllerTest` covers 14 search scenarios (title, overview, genre, director, year, IMDB rating, combined filters, watched toggle, sort, pagination) but has zero tests for `GET /search/autocomplete`. The autocomplete field whitelist (`director` / `actors` → 400 for anything else) and the deduplication/cap-at-10 logic in `SearchService.autocomplete` are untested. A regression in the field mapping (e.g., `director_list` → `director_list.text`) would not be caught.

**Fix:** Add at minimum two test scenarios:
1. Index a movie with a known director, call `GET /search/autocomplete?field=director&prefix=No`, assert the suggestion appears.
2. Call `GET /search/autocomplete?field=invalid&prefix=No`, assert 400 with a descriptive message.

---

### IN-02: Duplicate `posterUrl` helper defined in three separate files

**Files:**
- `frontend/pages/index.vue:12`
- `frontend/components/MovieCard.vue:10`
- `frontend/components/MovieListItem.vue:10`
- `frontend/components/MovieOfTheDay.vue:13`

**Issue:** The `posterUrl(posterPath)` function (guard for non-`/` paths → placeholder, otherwise prefix with TMDB base URL) is copy-pasted across four files with only the image size token differing (`w300`, `w92`, `w500`). This is pure duplication — if the TMDB base URL or the placeholder path changes, all four copies must be updated.

**Fix:** Extract a shared utility, e.g., `frontend/utils/tmdb.ts`:
```ts
export function posterUrl(posterPath: string | null, size: 'w92' | 'w300' | 'w500' = 'w300'): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/${size}${posterPath}`
}
```
Then import and call `posterUrl(movie.posterPath, 'w300')` in each component.

---

### IN-03: `ref` imported explicitly in `FilterPanel.vue` but is auto-imported by Nuxt

**File:** `frontend/components/FilterPanel.vue:2`

**Issue:** Line 2 is `import { ref } from 'vue'`. In Nuxt 3, `ref`, `computed`, and other Vue Composition API primitives are auto-imported. This explicit import is redundant and inconsistent with the rest of the frontend codebase (other components use `ref` without importing it).

**Fix:** Remove line 2: `import { ref } from 'vue'`

---

### IN-04: `SearchRequest.sort` accepts any string and silently falls through to the default sort

**File:** `backend/src/main/java/de/moviearchive/search/dto/SearchRequest.java:24`

**Issue:** The `sort` field has no `@Pattern` or validation annotation. Any arbitrary string for `sort` (e.g., `"malformed"`) silently falls through the `switch` default in `SearchService.buildSort` and resolves to `title_asc`. This is safe but inconsistent with the validation applied to `page` (`@Min(0)`). A developer sending a typo'd sort param gets no feedback.

**Fix:** Add a `@Pattern` constraint or a custom validator:
```java
@Pattern(regexp = "title_asc|year_desc|rating_desc|imdb_desc",
         message = "must be one of: title_asc, year_desc, rating_desc, imdb_desc")
private String sort;
```
The existing `@ExceptionHandler(MethodArgumentNotValidException.class)` in `SearchController` will automatically return a 400 with the constraint message.

---

_Reviewed: 2026-05-17T10:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
