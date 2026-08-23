---
phase: 09-manual-wiki-retry
reviewed: 2026-08-23T15:11:48Z
depth: standard
files_reviewed: 15
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/movie/MovieDetailController.java
  - backend/src/main/java/de/moviearchive/movie/MovieDetailService.java
  - backend/src/main/java/de/moviearchive/user/UserController.java
  - backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java
  - backend/src/test/java/de/moviearchive/user/UserControllerTest.java
  - frontend/composables/useMovieDetail.ts
  - frontend/composables/useSettings.ts
  - frontend/pages/movies/[id].vue
  - frontend/pages/settings.vue
  - frontend/test/mocks/handlers/movieDetail.ts
  - frontend/test/mocks/handlers/settings.ts
  - frontend/test/unit/composables/useMovieDetail.spec.ts
  - frontend/test/unit/composables/useSettings.spec.ts
  - frontend/test/unit/pages/movies-id.spec.ts
  - frontend/test/unit/pages/settings.spec.ts
findings:
  critical: 0
  warning: 5
  info: 3
  total: 8
status: issues_found
---

# Phase 9: Code Review Report

**Reviewed:** 2026-08-23T15:11:48Z
**Depth:** standard
**Files Reviewed:** 15
**Status:** issues_found

## Summary

Phase 9 adds `POST /movies/{id}/retry-wiki` (reusing Phase 8's `WikiReloadService.retryWikipedia`) and `GET /users/me` (first controller in `de.moviearchive.user`), plus the Settings-page batch-reload trigger button that depends on it.

**Both threat models called out in the plan are correctly implemented as claimed:**
- **IDOR:** `MovieDetailService.retryWiki(userId, movieId)` loads via `movieRepository.findByIdAndUserId(movieId, userId)` (a genuine derived-query, verified in `MovieRepository.java:15`) and throws 404 before ever touching `WikiReloadService`. `MovieDetailControllerTest.retryWiki_returns404WhenMovieNotOwnedByUser` and `..._returns404WhenMovieDoesNotExist` cover this correctly.
- **Info disclosure:** `UserController.me()` returns `ResponseEntity.ok(Map.of("id", id))` — never the `User` entity. `UserControllerTest.me_responseContainsOnlyIdField` is a real regression guard that explicitly asserts `passwordHash`/`email`/`status`/`createdAt` are absent from the JSON. `/users/**` is confirmed to fall under `anyRequest().authenticated()` in `SecurityConfig.java` (not in the `permitAll()` list), so `T-09-05`'s claim holds.

No blockers were found. The issues below are all quality/robustness gaps: a misleading error state on retry failure, a stale-state bug on SPA navigation between movie pages, a fragile/self-fulfilling test for the 503 branch, an unbounded synchronous external-call endpoint with no throttling, and a couple of pre-existing (not phase-9-introduced, but present in the reviewed files) input-validation gaps.

## Warnings

### WR-01: `retryWiki()` misreports network/server errors as "still no page found" — FIXED (commit 3006964)

**File:** `frontend/composables/useMovieDetail.ts:113-127`, consumed at `frontend/pages/movies/[id].vue:20-25,347-362`

**Resolution:** Added a `wikiRetryError` ref, set `true` only when the POST itself throws (left `false` on a completed 200 response, success or genuine not-found). The page now shows "Something went wrong — try again." when `wikiRetryError` is set, and only shows "Still no page found." for a real completed-but-empty response. Covered by new tests in `useMovieDetail.spec.ts` and `movies-id.spec.ts`. This is exactly the bug that caused a live-testing false negative: a stale backend process meant the endpoint 403'd, and the old swallow-all-errors code silently rendered it as "no page found."

**Issue:** `retryWiki()` catches *all* rejections from the POST identically:
```ts
} catch {
  // leave movie.value as-is; the page tracks "attempt happened" separately
} finally {
  wikiRetrying.value = false
}
```
The page's `onRetryWiki()` then unconditionally sets `wikiRetryAttempted.value = true` after `await retryWiki()`, regardless of whether the call actually succeeded, returned a genuine "Wikipedia not found" (200 OK with null wiki fields — no exception at all), or failed with a 500/network error. A backend 500 (DB failure, unexpected exception in `WikiReloadService`) or a dropped connection will render the exact same "Still no page found." message as a real Wikipedia miss, misleading the user into thinking Wikipedia genuinely has no page when the request never actually completed.

**Fix:** Track success/failure explicitly and only set `wikiRetryAttempted` on an actual completed (200) response; surface a distinct error message on catch:
```ts
async function retryWiki(): Promise<boolean> {
  wikiRetrying.value = true
  try {
    const data = await $fetch<MovieDetail>(`/api/movies/${movieId}/retry-wiki`, {
      method: 'POST', credentials: 'include', headers: authHeaders(),
    })
    movie.value = data
    return true
  } catch {
    return false
  } finally {
    wikiRetrying.value = false
  }
}
```
```ts
async function onRetryWiki() {
  const ok = await retryWiki()
  wikiRetryAttempted.value = ok  // or a separate `wikiRetryFailed` ref with its own message
}
```

### WR-02: `wikiRetryAttempted` state is stale across client-side navigation between movie pages

**File:** `frontend/pages/movies/[id].vue:14-25`

**Issue:** `const id = route.params.id as string` is read once at `setup()`. `NuxtPage` in `frontend/app.vue:5` has no `:key` binding, so Vue Router's default behavior reuses the same component instance (and therefore the same `useMovieDetail(id)` closure and the same `wikiRetryAttempted` ref) when navigating from `/movies/{A}` to `/movies/{B}` via client-side navigation (e.g. clicking an actor/genre chip that lands back on a movie detail page, or any in-app link between two detail pages). If a user retries wiki for movie A, gets "Still no page found.", then navigates to movie B (which has never been retried), `wikiRetryAttempted` is still `true` from A and B's page will incorrectly show "Still no page found." on first render, even though no retry has happened for B yet. (Note: `id` itself being frozen at setup is a broader pre-existing issue for data fetching in general — this finding is scoped to the new phase-9 `wikiRetryAttempted` state, which is the newly introduced surface that visibly breaks.)

**Fix:** Reset `wikiRetryAttempted` whenever the underlying movie changes, e.g.:
```ts
watch(() => id, () => { wikiRetryAttempted.value = false })
```
or, more robustly, key the page on the route so the whole component (and composable) remounts per movie:
```html
<!-- app.vue -->
<NuxtPage :page-key="route => route.fullPath" />
```

### WR-03: `useSettings.spec.ts` 503-mapping test only exercises a hand-shaped mock, not real `ofetch` error shape

**File:** `frontend/test/unit/composables/useSettings.spec.ts:138-144`, implementation at `frontend/composables/useSettings.ts:41-45`

**Issue:** `triggerWikiReload()` checks `e?.response?.status === 503`. The unit test mocks `$fetch` directly and rejects with a hand-built `{ response: { status: 503 } }` object — it never runs through real `ofetch`, so the test would pass even if the implementation's assumption about the error shape were wrong (it happens to be correct: `ofetch`'s `FetchError.response` is the real `Response` object, which does expose `.status`). No MSW-level test exists for the 503 branch either — `frontend/test/mocks/handlers/settings.ts:45-48` only ever returns 202 for `POST /api/admin/wiki-reload/:userId`, so the full `$fetch` → error → branch-selection path for the "already-running" case is never exercised end-to-end, only simulated with a mock shaped to match the assumption under test.

**Fix:** Add an MSW handler variant (or a second handler registered per-test via `server.use(...)`) that returns a real 503 response, and assert `triggerWikiReload()` resolves to `'already-running'` through the actual `ofetch` error-normalization path, not a hand-rolled object.

### WR-04: `POST /movies/{id}/retry-wiki` has no per-request throttling despite performing a synchronous external HTTP call

**File:** `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java:65-70`, `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java:120-125`

**Issue:** The endpoint's own javadoc correctly discloses that it "never checks the batch-reload cooldown" and "no coordination with the batch-reload executor is added" as accepted risks (D-01/D-02). What isn't covered by that disclosure is basic abuse/self-DoS protection: nothing stops a client from firing this endpoint repeatedly for the same movie (or many movies) back-to-back, each call blocking a request-handling thread for the full duration of a synchronous Wikipedia round-trip (up to a 10-candidate fallback cascade per `WikiReloadService`'s javadoc). `Bucket4j` is already a project dependency and used on `/auth/*`; this endpoint has none. Low severity for the current single-user deployment (per CLAUDE.md), but worth tracking before any multi-user rollout, and even single-user a client bug (e.g. a rapid double-click without the `wikiRetrying` disable taking effect race, or a script) could tie up worker threads or trip Wikipedia's own rate limits.

**Fix:** Apply a lightweight per-user or per-movie Bucket4j rate limit to this endpoint, consistent with the `/auth/*` pattern already established in the codebase.

### WR-05: `updatePersonal` accepts unbounded `personalRating` with silent truncation, no server-side range validation

**File:** `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java:83-91` (pre-existing from plan 06-02, present in the file under review)

**Issue:**
```java
movie.setPersonalRating(val instanceof Number n ? n.shortValue() : null);
```
`Map<String, Object> fields` is deserialized generically with no `@Valid`/DTO/bean-validation layer at all. Any `Number` is accepted and silently narrowed via `.shortValue()` — e.g. a client sending `personalRating: 100000` gets silently wrapped to a negative `short` value and persisted without error, rather than being rejected. There's no check that the rating falls in the intended 1–10 (or whatever the UI's `StarRating` range is) domain.

**Fix:** Validate the numeric range explicitly before assigning, and reject out-of-range values with a 400:
```java
if (val instanceof Number n) {
    short rating = n.shortValue();
    if (rating < 1 || rating > 10) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "personalRating must be 1-10");
    }
    movie.setPersonalRating(rating);
} else {
    movie.setPersonalRating(null);
}
```

## Info

### IN-01: `resolveUserId` duplicated a fourth time instead of extracted to a shared helper

**File:** `backend/src/main/java/de/moviearchive/user/UserController.java:31-34`

**Issue:** The exact "resolve `UUID` from `Authentication` via `UserRepository.findByEmail`" pattern already exists as a private `resolveUserId()` method in `MovieDetailController`, `SearchController`, and `DashboardController`. `UserController.me()` inlines the same logic a fourth time rather than reusing/extracting a shared component (e.g. a `CurrentUserResolver` bean). Not a bug — each copy is functionally correct and consistent with the existing (already-duplicated) codebase convention — but it's a growing DRY violation that this phase's new controller had the opportunity to consolidate.

**Fix:** Extract a shared `@Component CurrentUserResolver` (or a static utility) used by all four controllers.

### IN-02: `e?.response?.status === 503` check is stylistically inconsistent with the rest of the codebase

**File:** `frontend/composables/useSettings.ts:41-45`

**Issue:** Functionally correct (`ofetch`'s `FetchError.response` is the real `Response` object exposing `.status`), but every other status-code check in the frontend uses the shorthand `error.status` (see `frontend/pages/signup.vue:47`, `frontend/pages/login.vue:58,61,63`, `frontend/pages/add.vue:41`, `frontend/pages/forgot-password.vue:29`). This is the only place in the codebase using `error.response.status` instead.

**Fix:** Use `e?.status === 503` for consistency with the established pattern.

### IN-03: `settings.spec.ts` contains assertion-light tests whose names overstate what they verify

**File:** `frontend/test/unit/pages/settings.spec.ts:24-30,57-62`

**Issue:** `'renders Account section heading'` and `'settings page exports a default component (CSV placeholder section exists in source)'` both only assert `expect(SettingsPage).toBeDefined()` — they don't mount the component or check any DOM content, despite their names implying they verify rendered output. The file's own comments acknowledge this ("kept as module existence check for this phase"). Unlike `movies-id.spec.ts`, which does full `mount()`-based assertions for the equivalent new-feature UI (the Retry button), `settings.spec.ts` has no component-mount coverage at all for the new Wikipedia Data trigger button (loading state, success/already-running message text, disabled state) — only composable-level behavior is exercised.

**Fix:** Either rename these tests to reflect what they actually check, or add real `mount()`-based coverage of the `#wikipedia-data` section (button disabled state during `wikiReloadTriggering`, message text for `started`/`already-running`/generic-failure) mirroring the thoroughness already present in `movies-id.spec.ts`.

---

_Reviewed: 2026-08-23T15:11:48Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
