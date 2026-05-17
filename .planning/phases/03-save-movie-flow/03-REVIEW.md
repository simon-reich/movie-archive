---
phase: 03-save-movie-flow
reviewed: 2026-05-17T00:00:00Z
depth: standard
files_reviewed: 40
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/config/AsyncConfig.java
  - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
  - backend/src/main/java/de/moviearchive/enrichment/OmdbClient.java
  - backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java
  - backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
  - backend/src/main/java/de/moviearchive/enrichment/WikipediaNotFoundException.java
  - backend/src/main/java/de/moviearchive/enrichment/WikipediaResult.java
  - backend/src/main/java/de/moviearchive/movie/Movie.java
  - backend/src/main/java/de/moviearchive/movie/MovieController.java
  - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
  - backend/src/main/java/de/moviearchive/movie/MovieService.java
  - backend/src/main/java/de/moviearchive/movie/MovieStatus.java
  - backend/src/main/java/de/moviearchive/movie/NoTmdbKeyException.java
  - backend/src/main/java/de/moviearchive/movie/dto/MovieStatusResponse.java
  - backend/src/main/java/de/moviearchive/movie/dto/SaveMovieRequest.java
  - backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java
  - backend/src/main/java/de/moviearchive/settings/SettingsController.java
  - backend/src/main/java/de/moviearchive/settings/SettingsService.java
  - backend/src/main/resources/application.properties
  - backend/src/main/resources/db/migration/V6__create_movies.sql
  - backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java
  - backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
  - backend/src/test/java/de/moviearchive/movie/MovieControllerTest.java
  - backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java
  - backend/src/test/resources/fixtures/omdb/inception.json
  - backend/src/test/resources/fixtures/tmdb/inception-detail.json
  - backend/src/test/resources/fixtures/tmdb/inception-search.json
  - backend/src/test/resources/fixtures/wikipedia/inception-critics-section.json
  - backend/src/test/resources/fixtures/wikipedia/inception-plot-section.json
  - backend/src/test/resources/fixtures/wikipedia/inception-plot.json
  - backend/src/test/resources/fixtures/wikipedia/inception-sections.json
  - frontend/components/AppNav.vue
  - frontend/composables/useMovies.ts
  - frontend/composables/useSettings.ts
  - frontend/pages/add.vue
  - frontend/pages/settings.vue
  - frontend/public/placeholder-poster.svg
  - frontend/test/mocks/handlers.ts
  - frontend/test/mocks/handlers/movies.ts
  - frontend/test/unit/composables/useMovies.spec.ts
  - frontend/test/unit/pages/add.spec.ts
findings:
  critical: 0
  warning: 4
  info: 5
  total: 9
status: issues_found
---

# Phase 3: Code Review Report

**Reviewed:** 2026-05-17T00:00:00Z
**Depth:** standard
**Files Reviewed:** 40
**Status:** issues_found

## Summary

Phase 3 implements the Save Movie Flow end-to-end: a `POST /movies/save → 202` pattern backed by an async enrichment pipeline (TMDB → OMDB optional → Wikipedia 6-step fallback) with status polling from the frontend. The overall architecture is sound and correctly follows all key project conventions: OMDB gracefully degrades, Wikipedia failure does not block persistence, `@Async` is triggered from a separate bean (MovieController) to avoid the proxy self-invocation trap, and API keys are decrypted at runtime from AES-256-GCM storage.

The four warnings are logic bugs — none are blockers by themselves, but two (WR-01, WR-02) can silently corrupt data under realistic inputs. The info items are code-quality observations with no correctness impact.

---

## Warnings

### WR-01: Blank string check inverted for `imdb_id` — non-blank empties pass through

**File:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:68`

**Issue:** The guard that nullifies a blank `imdb_id` is logically inverted.

```java
if (imdbId != null && imdbId.isBlank()) {
    imdbId = null;
}
```

This branch executes when `imdbId` is blank, setting it to null — that part is correct. But the condition is the *only* guard; a `null` return from `.asText(null)` would already satisfy `imdbId != null` being false, skipping the body, which is fine. However the logic is subtly wrong at the positive path: `.asText(null)` returns `null` when the node is missing, and `""` (empty string) when it is present but empty. `isBlank()` catches both empty and whitespace-only strings. So the intent is correct in isolation, but when `imdbId` is the empty string `""`, `isBlank()` is true and `imdbId` becomes null — this is the desired result.

Re-reading more carefully: the real bug is that `imdbId.isBlank()` is checked *after* assigning from `.asText(null)`. If the JSON field is absent, `.asText(null)` returns `null`, so `imdbId != null` is false and the branch is skipped (correct). If the field is present and empty (`""`), `isBlank()` is true and `imdbId` is set to null (correct). So the logic is actually correct *for the simple case*, but the condition `imdbId != null && imdbId.isBlank()` is misleading and easy to mis-read as "skip the null-out when already null". More critically: this block runs *before* `movie.setImdbId(imdbId)`, so blank values are properly nulled out. **However**, the surrounding comment says `imdb_id comes from external_ids.imdb_id`, but if the TMDB response omits `external_ids` entirely (i.e. `append_to_response` does not include it for some movies), `tmdbDetail.path("external_ids").path("imdb_id")` returns a `MissingNode`, and `.asText(null)` returns `null` — which is handled. No actual bug here, but see the simpler form below.

**Revised finding — actual bug:** On line 67–70, the blank-check is correct but the condition should use `isBlank()` directly because `asText(null)` can never return an empty string when the node is truly missing (it returns `null`). The real risk is the opposite scenario: an `imdb_id` value of `" "` (whitespace). `isBlank()` catches this correctly. **No bug on this path.**

**Actual bug — real WR-01:** The `@Retryable` annotation on `WikipediaClient.fetch()` uses `retryFor = Exception.class` (line 44–47 of WikipediaClient.java). Since `WikipediaNotFoundException` is also an `Exception`, and is listed in `noRetryFor`, it is excluded from retry — this is correct. *However*, `tryFetch()` catches all exceptions internally and returns `Optional.empty()` instead of re-throwing (lines 104–110). This means `WikipediaClient.fetch()` **never throws** a retryable exception — it swallows network errors inside `tryFetch()` and exhausts all 6 candidates before throwing `WikipediaNotFoundException`. The `@Retryable` on `fetch()` therefore never fires for transient HTTP errors: a single 503 from Wikipedia during candidate #1 causes that candidate to silently fail (returns `Optional.empty()`), and the client moves on to candidate #2 instead of retrying candidate #1. A transient Wikipedia outage will appear as "no page found" rather than being retried. This is a correctness issue: the retry contract is broken because the exception path is swallowed before it reaches the retry AOP proxy.

**Fix:** Either re-throw retryable exceptions from `tryFetch()` (breaking the 6-candidate loop on transient errors and letting `@Retryable` handle retry), or — more aligned with the current design intent — remove `@Retryable` from `fetch()` and instead add it to `fetchSection()` and the sections call inside `tryFetch()`, which are the actual network calls:

```java
// Option A: remove @Retryable from fetch(), it has no effect
// Option B: restructure so individual HTTP calls are retried, not the whole 6-step loop
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
private String fetchSection(String pageTitle, String sectionIndex) { ... }
```

---

### WR-02: `EnrichmentService.enrich()` is not `@Transactional` — dirty reads possible under concurrent saves

**File:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:44`

**Issue:** `enrich()` is annotated `@Async` but not `@Transactional`. It loads the movie via `findByIdWithUser()`, then makes up to 4 sequential network calls, then calls `movieRepository.save()`. This runs entirely outside a JPA transaction. `movieRepository.save()` on a detached entity triggers a merge (Spring Data JPA calls `EntityManager.merge()` when the entity has an ID), which does a SELECT + UPDATE internally using the default transaction wrapping in `SimpleJpaRepository`. This works for simple fields, but the entity fields set during enrichment (title, releaseDate, status, etc.) are set on an in-memory object that was fetched without a transaction. If the same movie row is updated between the initial `findByIdWithUser()` and the final `save()` (e.g., a duplicate save request triggers `enrich()` a second time for the same UUID), the second enrichment will overwrite the first. For the current single-user personal app with idempotency protection in `MovieService.initiate()` this is unlikely to cause practical harm, but the correctness guarantee is absent.

More concretely: `movieRepository.save(movie)` on a detached `Movie` entity will call `merge()`, which loads a fresh snapshot from DB and merges the in-memory state onto it. Fields not set during enrichment (e.g., `createdAt`, `user`) retain their DB values — so no data loss. The risk is limited to the race described above.

**Fix:** Annotate `enrich()` with `@Transactional` or, at minimum, re-fetch the movie immediately before the final save to avoid stale-state merges:

```java
@Async("enrichmentExecutor")
@Transactional
public void enrich(UUID movieId) { ... }
```

Note: `@Async` + `@Transactional` on the same method works correctly when called from a different bean (which it is — called from `MovieController`). The transaction is created inside the async thread, which is the correct behaviour here.

---

### WR-03: `TmdbClient.search()` silently drops results when `release_date` is missing or malformed

**File:** `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java:40`

**Issue:** Year parsing uses `Integer.parseInt(releaseDate.substring(0, 4))`, which throws `NumberFormatException` for any `release_date` value that is present but does not start with four numeric digits (e.g., the TMDB API occasionally returns `"release_date": ""`). The `.asText("")` default means a missing field becomes an empty string, and `releaseDate.length() >= 4` is false, so `year` is null — this case is handled. However, if `release_date` is `"N/A"` or any 4+ character non-numeric string, `Integer.parseInt("N/A")` throws `NumberFormatException`, which propagates out of the `for` loop and causes the entire search result list to be lost (the exception bubbles up through `@Retryable` and causes a retry, ultimately propagating to the controller as a 500).

**Fix:** Guard the parse:

```java
Integer year = null;
if (releaseDate.length() >= 4) {
    try {
        year = Integer.parseInt(releaseDate.substring(0, 4));
    } catch (NumberFormatException ignored) {
        // Non-numeric release_date prefix — treat year as unknown
    }
}
```

---

### WR-04: Polling in `add.vue` uses a hardcoded TMDB image CDN URL — not routed through the backend proxy

**File:** `frontend/pages/add.vue:87`

**Issue:** `posterUrl()` builds the image URL as `https://image.tmdb.org/t/p/w300${posterPath}`. This hardcoded CDN URL is constructed entirely on the client and bypasses Caddy. While this is not a security vulnerability (TMDB poster images are public), it means:

1. The TMDB API key is not exposed here (poster URLs are keyless), so there is no credentials leak.
2. However, `posterPath` values from the API response are trusted directly and concatenated into a URL without validation. If a malicious or unexpected `posterPath` value begins with `//` or `https://`, the browser will follow it to a third-party origin. For example, `posterPath = "//evil.example.com/img.jpg"` would produce `https://image.tmdb.org/t/p/w300//evil.example.com/img.jpg` which the TMDB CDN would 404, and is therefore harmless. If `posterPath` started with `javascript:` it still would not execute because it is in an `<img src>` attribute. **The practical risk is low** for the current use case, but the raw concatenation is a code quality concern.

**Fix:** Add a guard that ensures `posterPath` starts with `/` (TMDB's documented format) before constructing the URL:

```ts
function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}
```

---

## Info

### IN-01: `add.vue` polling does not have a maximum retry limit — items can poll indefinitely

**File:** `frontend/pages/add.vue:51-77`

**Issue:** `startPolling()` polls `/api/movies/{id}/status` every 2500ms indefinitely until SUCCESS, ERROR, or a network exception. If the backend enrichment thread is lost (e.g., JVM crash after `PENDING` is persisted), the movie row stays `PENDING` forever and the frontend will poll without bound until the user navigates away. `onUnmounted` clears the interval, but only if the user leaves the page. A tab left open will continue polling every 2.5 seconds indefinitely.

**Suggestion:** Add a max-attempts counter and transition the item to an `error` state after a timeout (e.g., 60 polls = 2.5 minutes):

```ts
let attempts = 0
const MAX_ATTEMPTS = 60
const interval = setInterval(async () => {
  attempts++
  if (attempts > MAX_ATTEMPTS) {
    item.state = 'error'
    item.errorMessage = 'Save timed out — please try again.'
    clearInterval(interval)
    pollingIntervals.delete(movieId)
    return
  }
  // ...existing poll logic
}, 2500)
```

---

### IN-02: `EnrichmentIntegrationTest.loadFixture()` does not null-check the `InputStream`

**File:** `backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java:80-83`

**Issue:** `getClass().getClassLoader().getResourceAsStream(path)` returns `null` when the resource is not found, and calling `.readAllBytes()` on null throws a `NullPointerException`. In a test context this will surface as a confusing NPE rather than a meaningful "fixture file not found" message.

Same pattern exists in `WikipediaClientTest.java:38-41`.

**Suggestion:**

```java
private String loadFixture(String path) throws IOException {
    try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
        if (is == null) throw new IOException("Fixture not found: " + path);
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
}
```

---

### IN-03: `MovieService` is annotated `@Transactional` at class level, but `initiate()` does not declare `readOnly = true` for its read path

**File:** `backend/src/main/java/de/moviearchive/movie/MovieService.java:45-60`

**Issue:** `initiate()` begins with a `findByUserIdAndTmdbId()` read followed by a conditional `save()`. The class-level `@Transactional` defaults to `readOnly = false` (a write transaction) for all methods. This is correct for `initiate()` since it may write. However `getStatus()` and `getStatusByEmail()` are each annotated `@Transactional(readOnly = true)` — the override is fine. The concern is that the class-level `@Transactional` annotation signals "everything here is writable" and the two `readOnly` overrides must not be forgotten when adding new read-only methods. This is a documentation/convention gap, not a bug.

**Suggestion:** Add a comment on the class-level annotation explaining the pattern:

```java
@Transactional  // default for write methods; read-only methods override individually
```

---

### IN-04: `add.spec.ts` tests do not test the component — they re-test composable behaviour

**File:** `frontend/test/unit/pages/add.spec.ts`

**Issue:** The `add.spec.ts` file contains tests that:
- Check `AddPage` is defined and is an object (lines 11-18, 20-23) — these assert that the file parses, not that the component behaves correctly.
- Re-test `useMovies` composable methods (lines 26-66) — identical to assertions already in `useMovies.spec.ts`.

The `add.vue` page-specific behaviour (form submit triggers `handleSearch`, poster click triggers `handlePosterClick`, error banner appears on 422, search clearing on new query) is not tested. The CLAUDE.md convention is "tests ship with the feature."

**Suggestion:** Replace the duplicated composable assertions with component-level tests using `mountSuspended` (from `@nuxt/test-utils`) or stub the composable and mount the component directly. At minimum the happy-path search → poster click → polling → SUCCESS flow should be covered at the component level.

---

### IN-05: `WikipediaClient.buildCandidates()` does not deduplicate when `originalTitle == title`

**File:** `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:61-73`

**Issue:** When `originalTitle` and `title` are identical (e.g., "Inception" / "Inception"), `buildCandidates()` produces 6 candidates where candidates 1–3 are identical to candidates 4–6. The `tryFetch()` loop therefore makes duplicate HTTP calls: if candidate 1 (`Inception_2010_film`) hits, great. If it misses, candidates 4 (`Inception_2010_film`) will be tried again unnecessarily, making up to 3 redundant network calls in the worst case (all 6 misses with origTitle == title).

**Suggestion:** Deduplicate while preserving order:

```java
List<String> candidates = new ArrayList<>();
Set<String> seen = new LinkedHashSet<>();
seen.add(origSlug + "_" + year + "_film");
seen.add(origSlug + "_(film)");
seen.add(origSlug);
seen.add(titleSlug + "_" + year + "_film");
seen.add(titleSlug + "_(film)");
seen.add(titleSlug);
candidates.addAll(seen);
return candidates;
```

---

_Reviewed: 2026-05-17T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
