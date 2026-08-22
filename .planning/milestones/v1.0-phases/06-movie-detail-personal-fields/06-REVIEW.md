---
phase: 06-movie-detail-personal-fields
reviewed: 2026-05-18T00:00:00Z
depth: standard
files_reviewed: 20
files_reviewed_list:
  - backend/build.gradle.kts
  - backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java
  - backend/src/main/java/de/moviearchive/indexing/IndexingService.java
  - backend/src/main/java/de/moviearchive/movie/Movie.java
  - backend/src/main/java/de/moviearchive/movie/MovieDetailController.java
  - backend/src/main/java/de/moviearchive/movie/MovieDetailService.java
  - backend/src/main/java/de/moviearchive/movie/dto/CastMember.java
  - backend/src/main/java/de/moviearchive/movie/dto/CrewMember.java
  - backend/src/main/java/de/moviearchive/movie/dto/MovieDetailResponse.java
  - backend/src/main/java/de/moviearchive/movie/dto/Rating.java
  - backend/src/main/resources/db/migration/V7__add_personal_fields_to_movies.sql
  - frontend/components/MovieCard.vue
  - frontend/components/MovieListItem.vue
  - frontend/components/StarRating.vue
  - frontend/components/TrailerEmbed.vue
  - frontend/composables/useMovieDetail.ts
  - frontend/pages/movies/[id].vue
  - frontend/test/mocks/handlers/movieDetail.ts
  - frontend/test/unit/components/TrailerEmbed.spec.ts
  - frontend/test/unit/composables/useMovieDetail.spec.ts
  - frontend/test/unit/pages/movies-id.spec.ts
findings:
  critical: 0
  warning: 5
  info: 4
  total: 9
status: issues_found
---

# Phase 06: Code Review Report

**Reviewed:** 2026-05-18
**Depth:** standard
**Files Reviewed:** 20
**Status:** issues_found

## Summary

Phase 06 adds a movie detail page, personal fields (watched/rating/notes), and a delete flow. The overall implementation is solid: ownership checks are consistently enforced via `findByIdAndUserId`, the personal fields PATCH accepts a flexible Map which is handled defensively, and the OpenSearch sync silently degrades as designed.

Five warnings were found: two logic bugs (no validation on `personalRating` range in the backend, a missing error handler on the notes `updatePersonal` call in the frontend), one data inconsistency between how writers are extracted in `DocumentBuilder` vs `MovieDetailService`, and two missing-null-guard issues in the Vue template and composable. Four info items cover minor code quality and test coverage gaps.

No critical security issues were found. `userId` is always resolved from the JWT, never from the request body, and the Flyway migration is correct.

---

## Warnings

### WR-01: No server-side validation of `personalRating` range — out-of-range values persist to DB

**File:** `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java:86-88`

**Issue:** The PATCH handler reads `personalRating` from a raw `Map<String, Object>` and converts it directly to `Short` without any range check. The database column is `SMALLINT` (−32768–32767), but the domain constraint is 1–10 stars. A client can send `{"personalRating": 999}` and it will be stored verbatim, then shown in the UI as 999 filled stars (rendered by `StarRating` which iterates 1–10, so the display would be wrong but the value would stay).

**Fix:**
```java
if (fields.containsKey("personalRating")) {
    Object val = fields.get("personalRating");
    if (val == null) {
        movie.setPersonalRating(null);
    } else {
        short rating = val instanceof Number n ? n.shortValue() : -1;
        if (rating < 1 || rating > 10) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "personalRating must be between 1 and 10");
        }
        movie.setPersonalRating(rating);
    }
}
```
Alternatively, introduce a typed `UpdatePersonalRequest` DTO with `@Min(1) @Max(10)` and enable `@Validated` on the controller.

---

### WR-02: `updatePersonal` errors are silently swallowed in the frontend — watched/rating/notes changes can fail invisibly

**File:** `frontend/pages/movies/[id].vue:57-69`

**Issue:** `onWatchedChange`, `onRatingChange`, and `onNotesInput` all call `updatePersonal(...)` without `await` and without a `.catch()` or try/catch. If the PATCH request fails (network error, 401 re-auth needed, 5xx), the user receives no feedback and believes the change was saved when it was not. `$fetch` throws on non-2xx by default.

**Fix:** Add error handling at minimum for the watched and rating paths, which have no debounce buffer:
```ts
async function onWatchedChange() {
  try {
    await updatePersonal({ watched: localWatched.value })
  } catch {
    // revert optimistic local state
    localWatched.value = !localWatched.value
    // show toast / error indicator
  }
}

async function onRatingChange(rating: number | null) {
  localRating.value = rating
  try {
    await updatePersonal({ personalRating: rating })
  } catch {
    // revert or show error
  }
}
```
The debounced notes path should similarly wrap the inner `updatePersonal` call.

---

### WR-03: Writer extraction logic diverges between `DocumentBuilder` and `MovieDetailService`

**File:** `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java:185-198`
**Also:** `backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java:141-148`

**Issue:** In `DocumentBuilder`, writers are identified by `job` value: `"Writer"`, `"Screenplay"`, or `"Story"`. In `MovieDetailService.extractWriters`, the filter uses `department == "Writing"` (which is broader — it includes all crew in the Writing department, e.g. "Executive Story Editor", "Script Coordinator"). This means the `writerList` displayed on the detail page can differ from what is indexed in OpenSearch. A search by writer that matched on the index would not correspond to the same name list shown in the UI.

**Fix:** Align both to the same predicate. The more precise `job`-based match from `DocumentBuilder` is preferable for display accuracy:
```java
// MovieDetailService.extractWriters — use same job filter as DocumentBuilder
private static final Set<String> WRITER_JOBS = Set.of("Writer", "Screenplay", "Story");

private List<String> extractWriters(JsonNode tmdb) {
    if (tmdb == null || !tmdb.has("credits")) return List.of();
    JsonNode crew = tmdb.get("credits").get("crew");
    if (crew == null) return List.of();
    List<String> result = new ArrayList<>();
    for (JsonNode member : crew) {
        String job = member.has("job") ? member.get("job").asText() : "";
        if (WRITER_JOBS.contains(job)) {
            String name = member.has("name") ? member.get("name").asText(null) : null;
            if (name != null && !result.contains(name)) result.add(name);
        }
    }
    return result;
}
```

---

### WR-04: `parseBoxOffice` in `DocumentBuilder` silently overflows for large box office values

**File:** `backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java:267-276`

**Issue:** `parseBoxOffice` returns `Integer`. For blockbuster films, OMDB's `BoxOffice` field can exceed `Integer.MAX_VALUE` (2,147,483,647). For example, "Avengers: Endgame" grossed ~$2.8 billion. `Integer.parseInt("2800000000")` throws `NumberFormatException`, which is caught and returns `null` — so the box office value is silently lost for any film that grossed more than $2.1B. Notably, `MovieDetailService.parseLongField` correctly uses `Long`, so there is a type mismatch in indexed documents vs. detail responses.

**Fix:** Change the return type to `Long` (matching `MovieDetailService`):
```java
private Long parseBoxOffice(String value) {
    if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) return null;
    try {
        return Long.parseLong(value.replaceAll("[^0-9]", "").trim());
    } catch (NumberFormatException e) {
        return null;
    }
}
```
Update the `doc.put("box_office", ...)` call accordingly.

---

### WR-05: `deleteMovie` in `useMovieDetail` does not handle errors — navigation to `/search` is skipped on failure

**File:** `frontend/composables/useMovieDetail.ts:103-110`

**Issue:** `deleteMovie` calls `$fetch` with `DELETE` and then navigates unconditionally via `await router.push('/search')`. If the DELETE request throws (network error, 404, 5xx), the unhandled rejection propagates to the caller (`confirmDelete` in `[id].vue`) which also does not have a try/catch. The error is silently swallowed by Vue's global handler, the user sees no feedback, and they remain on the detail page with the movie still present in Postgres.

**Fix:**
```ts
async function deleteMovie(): Promise<void> {
  await $fetch(`/api/movies/${movieId}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: authHeaders(),
  })
  // Only navigate after confirmed deletion
  await router.push('/search')
}
```
And in `[id].vue`:
```ts
async function confirmDelete() {
  deleteModalOpen.value = false
  try {
    await deleteMovie()
  } catch {
    // re-open modal or show error toast
    deleteModalOpen.value = true
  }
}
```

---

## Info

### IN-01: `updatePersonal` uses `Map<String, Object>` instead of a typed DTO — field names are weakly typed

**File:** `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java:42-47`

**Issue:** The PATCH endpoint accepts `Map<String, Object>` and checks for keys `"watched"`, `"personalRating"`, `"personalNotes"` by string comparison. The file `UpdatePersonalRequest.java` was noted as optional in the file list but does not exist. Using a typed DTO with `@Valid` would make the contract explicit and enable Bean Validation.

**Fix:** Create a `UpdatePersonalRequest` DTO:
```java
public record UpdatePersonalRequest(
    Boolean watched,
    @Min(1) @Max(10) Short personalRating,
    String personalNotes
) {}
```
Change the controller to accept `@Valid @RequestBody UpdatePersonalRequest req`.

---

### IN-02: `TrailerEmbed` uses non-null assertion `!` on computed values that are only non-null when `trailerKey` is truthy

**File:** `frontend/components/TrailerEmbed.vue:33,48`

**Issue:** `thumbnailUrl!` and `embedUrl!` use TypeScript non-null assertions in the template. Both computed values return `null` when `trailerKey` is null. Since the outer `v-if="trailerKey"` guards the block, these assertions are logically safe at runtime, but they suppress the TypeScript type check and would cause a runtime error if the guard were ever relaxed. The `!` is unnecessary given that the template is only rendered when `trailerKey` is truthy.

**Fix:** Remove the assertions — the values will never be null inside the guarded block, but the assertions hide that reasoning from the type system:
```ts
const thumbnailUrl = computed(() =>
  props.trailerKey
    ? `https://img.youtube.com/vi/${props.trailerKey}/hqdefault.jpg`
    : ''
)

const embedUrl = computed(() =>
  props.trailerKey
    ? `https://www.youtube.com/embed/${props.trailerKey}?autoplay=1`
    : ''
)
```
Return an empty string instead of `null` so the type is `string` and no assertion is needed.

---

### IN-03: CSS typo in `[id].vue` — `tracking-widests` is not a valid Tailwind class

**File:** `frontend/pages/movies/[id].vue:314`

**Issue:** The Notes label uses `tracking-widests` (extra `s`). The valid Tailwind class is `tracking-widest`. This is a cosmetic defect — the label renders without letter-spacing.

**Fix:**
```html
<label class="text-xs font-semibold tracking-widest uppercase text-muted-foreground">Notes</label>
```

---

### IN-04: `useMovieDetail` test mocks `#app/composables/router` but production code uses `useRouter()` from Nuxt auto-imports — mock path may be fragile

**File:** `frontend/test/unit/composables/useMovieDetail.spec.ts:8-10`

**Issue:** The router is mocked via `vi.mock('#app/composables/router', ...)`. Nuxt's `useRouter` is typically auto-imported from `#imports` or `vue-router`. If the Nuxt build aliases change between versions, this mock path could silently stop intercepting `useRouter`, causing `deleteMovie` tests to fail or pass vacuously. The test file has no assertion that `mockRouterPush` was called with the right argument for the error path.

**Fix:** This is a low-risk fragility rather than a current bug. Consider using `@nuxt/test-utils` `mountSuspense` helpers for composable tests, or verify the mock path against the actual Nuxt alias in `nuxt.config.ts`. At minimum, assert the mock is being reached:
```ts
it('deleteMovie sends DELETE then navigates to /search', async () => {
  mockFetch.mockResolvedValue(null)
  const { deleteMovie } = useMovieDetail('test-id')
  await deleteMovie()
  expect(mockFetch).toHaveBeenCalledWith('/api/movies/test-id', expect.objectContaining({ method: 'DELETE' }))
  expect(mockRouterPush).toHaveBeenCalledTimes(1)  // add this assertion
  expect(mockRouterPush).toHaveBeenCalledWith('/search')
})
```

---

_Reviewed: 2026-05-18_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
