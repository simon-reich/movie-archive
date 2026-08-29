# Phase 9: Manual Wiki Retry - Pattern Map

**Mapped:** 2026-08-23
**Files analyzed:** 8 (2 backend modify, 1 backend new, 1 backend new-test, 3 frontend modify, 1 frontend test/mock modify)
**Analogs found:** 8 / 8

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` (MODIFY: add `POST /{id}/retry-wiki`) | controller | request-response | itself (`getDetail`/`deleteMovie` methods, same file) | exact |
| `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` (MODIFY: add `retryWiki(userId, movieId)`) | service | CRUD (read-modify-read) | itself (`updatePersonal`/`deleteMovie` methods, same file) | exact |
| `backend/src/main/java/de/moviearchive/user/UserController.java` (NEW) | controller | request-response | `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (structure) + `MovieDetailController.resolveUserId` (auth logic) | role-match |
| `backend/src/test/java/de/moviearchive/user/UserControllerTest.java` (NEW) | test | request-response | `backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java` | role-match |
| `frontend/composables/useMovieDetail.ts` (MODIFY: add `retryWiki()` + `wikiRetrying` ref) | hook/composable | request-response | itself (`fetchDetail` function, same file) | exact |
| `frontend/composables/useSettings.ts` (MODIFY: add `getCurrentUserId()` + `triggerWikiReload()`) | hook/composable | request-response | itself (`saveApiKey`/`loadApiKeys`, same file) | exact |
| `frontend/pages/movies/[id].vue` (MODIFY: `v-else` branch at line 329 for retry prompt) | component | request-response | itself (existing `v-if` wiki section + `SpinnerIcon` loading pattern at line 112-113) | exact |
| `frontend/pages/settings.vue` (MODIFY: new button, likely in `import-export` or new section) | component | request-response | itself (Import & Export section, lines 372-385; `onMounted` fetch-once pattern, lines 66-89) | exact |

## Pattern Assignments

### `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` (controller, request-response)

**Analog:** same file, `getDetail`/`deleteMovie` methods (VERIFIED, read this session)

**Imports pattern** (already present, no new imports needed beyond what's there):
```java
import de.moviearchive.movie.dto.MovieDetailResponse;
import de.moviearchive.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
```

**Auth pattern** (lines 61-65, VERIFIED — `resolveUserId`, reuse verbatim, no changes needed):
```java
private UUID resolveUserId(Authentication auth) {
    String email = auth.getName();
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
            .getId();
}
```

**Core pattern to copy** (based on `getDetail` lines 34-38 and `deleteMovie` lines 49-54, VERIFIED):
```java
@PostMapping("/{id}/retry-wiki")
public ResponseEntity<MovieDetailResponse> retryWiki(
        @PathVariable UUID id, Authentication auth) {
    UUID userId = resolveUserId(auth);
    return ResponseEntity.ok(movieDetailService.retryWiki(userId, id));
}
```

**Error handling pattern:** none needed at controller level — `movieDetailService.retryWiki` throws `ResponseStatusException(HttpStatus.NOT_FOUND, ...)` which Spring MVC translates automatically, same as `getDetail`/`updatePersonal`/`deleteMovie` (no try/catch anywhere in this controller).

---

### `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` (service, CRUD/read-modify-read)

**Analog:** same file, `updatePersonal`/`deleteMovie` methods (lines 73-75, 110-112, VERIFIED)

**Imports pattern** (add one new import; rest already present):
```java
import de.moviearchive.enrichment.WikiReloadService;   // NEW — inject alongside existing fields
```

**404 ownership-lookup pattern** (lines 73-75, VERIFIED, reused verbatim):
```java
Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
```

**Core pattern to copy** (new method, sibling to `getDetail`):
```java
public MovieDetailResponse retryWiki(UUID userId, UUID movieId) {
    Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
    wikiReloadService.retryWikipedia(movie);
    return getDetail(userId, movieId);
}
```

**No `@Transactional` needed on this orchestration method** — matches the class-wide convention (no method in `MovieDetailService` is `@Transactional`; the injected `wikiReloadService.retryWikipedia(Movie)` already carries its own `@Transactional` and commits before this method's subsequent `getDetail` read runs). Constructor injection via `@RequiredArgsConstructor` (Lombok) — just add `private final WikiReloadService wikiReloadService;` as a new field.

---

### `backend/src/main/java/de/moviearchive/user/UserController.java` (NEW controller, request-response)

**Analog A (auth pattern):** `MovieDetailController.resolveUserId` (lines 61-65, VERIFIED — same logic, copy verbatim)
**Analog B (controller shape/package structure):** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (VERIFIED, read this session — constructor injection style, `@RestController` + `@RequestMapping`, no builder-style Lombok annotation on this one; either constructor style or `@RequiredArgsConstructor` is fine, `MovieDetailController` uses `@RequiredArgsConstructor` which is the newer/preferred style)

**Full pattern to copy** (first controller in `de.moviearchive.user` package — no existing analog in that package):
```java
package de.moviearchive.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<Map<String, UUID>> me(Authentication auth) {
        String email = auth.getName();
        UUID id = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
                .getId();
        return ResponseEntity.ok(Map.of("id", id));
    }
}
```

**Security note (Pitfall 4, confirmed):** `User.java` has no Jackson annotation guarding `passwordHash` — never return the entity directly; always return `Map.of("id", id)` or a purpose-built record. No `SecurityConfig` change needed — `/users/me` is not in the `permitAll()` list (`/auth/**`, `/actuator/health`, `/settings/confirm-email`, `/test/**`) so it falls under `.anyRequest().authenticated()`.

---

### `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — REUSED, NOT MODIFIED

**Method being called by the new endpoint** (lines 71-102, VERIFIED, read this session — do not modify):
```java
@Transactional
public void retryWikipedia(Movie movie) {
    movie.setWikiLastAttemptedAt(Instant.now());
    try {
        int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
        String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
        String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
        WikipediaResult wiki = wikipediaClient.fetch(origTitle, movieTitle, year);
        movie.setWikiUrl(wiki.url());
        movie.setWikiSummary(wiki.summary());
        movie.setWikiPlot(wiki.plot());
        movie.setWikiCritics(wiki.critics());
        movieRepository.save(movie);
        log.info("Wiki retry succeeded movieId={}", movie.getId());

        try {
            indexingService.index(movie);
            movie.setIndexedAt(Instant.now());
            movieRepository.save(movie);
        } catch (Exception e) {
            log.warn("Wiki retry: OpenSearch re-index failed movieId={}: {}",
                    movie.getId(), e.getMessage());
        }
    } catch (WikipediaNotFoundException e) {
        movieRepository.save(movie);
        log.warn("Wiki retry: still not found movieId={}", movie.getId());
    } catch (Exception e) {
        movieRepository.save(movie);
        log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
    }
}
```
No cooldown check inside this method — cooldown filtering lives only in `MovieRepository.findEligibleForWikiReload` (used by `batchReload`), never here. Confirms D-01 requires zero new gating code.

---

### `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — REUSED, NOT MODIFIED

Only relevant for the 503 message the Settings page reacts to (lines 82-88, VERIFIED):
```java
@ExceptionHandler(TaskRejectedException.class)
public ResponseEntity<Map<String, String>> handleTaskRejected(TaskRejectedException ex) {
    return ResponseEntity.status(503).body(Map.of(
            "message", "A wiki-reload batch is already in progress; try again shortly."));
}
```
Frontend does not need to read this exact message string — D-07 specifies its own UI copy ("A reload is already in progress."), it only needs to detect the `503` status code.

---

### `frontend/composables/useMovieDetail.ts` (hook, request-response)

**Analog:** same file, `fetchDetail` (lines 74-88, VERIFIED, read this session)

**Imports pattern** (already present, no changes):
```typescript
import { ref } from 'vue'
```

**Core pattern to copy** (`fetchDetail`, verbatim structure to extend):
```typescript
async function fetchDetail(): Promise<void> {
  isLoading.value = true
  error.value = null
  try {
    const data = await $fetch<MovieDetail>(`/api/movies/${movieId}`, {
      credentials: 'include',
      headers: authHeaders(),
    })
    movie.value = data
  } catch {
    error.value = 'Failed to load film.'
  } finally {
    isLoading.value = false
  }
}
```

**New function to add** (same shape, no new TS interface needed — reuses `MovieDetail`):
```typescript
const wikiRetrying = ref(false)

async function retryWiki(): Promise<void> {
  wikiRetrying.value = true
  try {
    const data = await $fetch<MovieDetail>(`/api/movies/${movieId}/retry-wiki`, {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders(),
    })
    movie.value = data
  } catch {
    // leave movie.value as-is; template distinguishes "never tried" vs "still not found"
    // via a page-local wikiRetryAttempted flag, set regardless of outcome
  } finally {
    wikiRetrying.value = false
  }
}
```
Remember to add `wikiRetrying` and `retryWiki` to the composable's return object (mirrors how `fetchDetail`/`updatePersonal`/`deleteMovie` are already exported — check the `return { ... }` block at the bottom of the file before finalizing).

**Auth headers pattern** (lines 63-68, VERIFIED, unchanged, reused by `retryWiki`):
```typescript
const accessTokenCookie = useCookie<string | null>('access_token')

function authHeaders(): Record<string, string> {
  return accessTokenCookie.value
    ? { Authorization: `Bearer ${accessTokenCookie.value}` }
    : {}
}
```

---

### `frontend/composables/useSettings.ts` (hook, request-response)

**Analog:** same file, `saveApiKey`/`loadApiKeys` (lines 12-24, VERIFIED, read this session)

**Auth headers pattern** (identical to `useMovieDetail.ts`, lines 1-9, VERIFIED):
```typescript
const accessTokenCookie = useCookie<string | null>('access_token')

function authHeaders(): Record<string, string> {
  return accessTokenCookie.value
    ? { Authorization: `Bearer ${accessTokenCookie.value}` }
    : {}
}
```

**Core pattern to copy** (matches `saveApiKey`'s `$fetch` + method + credentials + headers shape):
```typescript
const currentUserId = ref<string | null>(null)

async function getCurrentUserId(): Promise<string> {
  if (!currentUserId.value) {
    const data = await $fetch<{ id: string }>('/api/users/me', {
      credentials: 'include',
      headers: authHeaders(),
    })
    currentUserId.value = data.id
  }
  return currentUserId.value
}

async function triggerWikiReload(): Promise<'started' | 'already-running'> {
  const userId = await getCurrentUserId()
  try {
    await $fetch(`/api/admin/wiki-reload/${userId}`, {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders(),
    })
    return 'started'
  } catch (err: unknown) {
    const e = err as { response?: { status?: number } }
    if (e?.response?.status === 503) return 'already-running'
    throw err
  }
}
```
Fetch `currentUserId` once and cache — do not re-fetch on every button click (Pitfall 5). Add both functions to the composable's `return { ... }` block.

---

### `frontend/pages/movies/[id].vue` (component, request-response)

**Analog:** same file, existing `v-if` wiki section (line 329, VERIFIED) + `SpinnerIcon` loading pattern (lines 5, 112-113, VERIFIED)

**Existing imports** (line 5, VERIFIED, already present — reuse, no new import needed):
```typescript
import SpinnerIcon from '@/components/SpinnerIcon.vue'
```

**Existing anchor `v-if` to branch off of** (line 329, VERIFIED, do not remove — add sibling `v-else`):
```html
<div v-if="movie.wikipediaPlot || movie.wikipediaCritics" class="max-w-7xl mx-auto px-4 pb-8 space-y-8 border-t border-border pt-8">
  <section v-if="movie.wikipediaPlot" class="space-y-2"> ... </section>
  <section v-if="movie.wikipediaCritics" class="space-y-2"> ... </section>
</div>
```

**SpinnerIcon loading-state pattern to reuse** (lines 112-113, VERIFIED):
```html
<div v-if="isLoading" class="flex items-center justify-center py-24">
  <SpinnerIcon />
</div>
```

**New `v-else` block to add:**
```html
<div v-else class="max-w-7xl mx-auto px-4 pb-8 space-y-4 border-t border-border pt-8">
  <p class="text-sm text-muted-foreground">
    No Wikipedia data found.
    <span v-if="wikiRetryAttempted"> Still no page found.</span>
  </p>
  <button
    type="button"
    :disabled="wikiRetrying"
    class="h-10 px-4 text-sm font-medium bg-primary text-primary-foreground rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-2"
    @click="onRetryWiki"
  >
    <SpinnerIcon v-if="wikiRetrying" class="w-4 h-4" />
    {{ wikiRetrying ? 'Retrying...' : 'Retry' }}
  </button>
</div>
```
`wikiRetryAttempted` is a new page-level `ref(false)`; a `<script setup>` handler `onRetryWiki` calls `useMovieDetail`'s `retryWiki()` then sets `wikiRetryAttempted.value = true` regardless of outcome — no new backend field needed (client-side-only flag per Pattern 4/D-05 discretion).

---

### `frontend/pages/settings.vue` (component, request-response)

**Analog:** same file — `onMounted` fetch-once pattern (lines 66-89, VERIFIED) + Import & Export section as the closest existing "action button + inline status text" section (lines 372-385, VERIFIED)

**Existing section styling to match** (lines 372-385, VERIFIED — copy this shape for the new button, whether placed here or a new section):
```html
<section id="import-export">
  <h1 class="text-xl font-semibold tracking-wide mb-6">Import &amp; Export</h1>
  <div class="flex gap-4">
    <ButtonPrimary type="button" :disabled="true">
      Export CSV
    </ButtonPrimary>
    <ButtonPrimary type="button" :disabled="true">
      Import CSV
    </ButtonPrimary>
  </div>
  <p class="text-sm text-muted-foreground mt-2">
    Coming soon — available after your first films are saved.
  </p>
</section>
```
`ButtonPrimary` (already imported/used in this file) is the button component to reuse for the new "Reload Wikipedia data" trigger — do not hand-roll a new raw `<button>` here; other sections in this file already use `<ButtonPrimary>`.

**`onMounted` fetch-once pattern** (lines 66-89 region, VERIFIED via grep — structure confirmed, not fully quoted since not directly load-bearing beyond "fetch once in onMounted, cache in a ref"):
```typescript
onMounted(async () => {
  // existing: keysLoading.value = true; const keys = await loadApiKeys(); ...
})
```
Follow the same fetch-once-cache-in-ref idiom for `getCurrentUserId()` — call it once in `onMounted` (or lazily on first click, caching in `currentUserId`), never on every render.

**New button handler to add** (uses `useSettings()`'s new `triggerWikiReload()`):
```typescript
const wikiReloadMessage = ref<string | null>(null)
const wikiReloadTriggering = ref(false)

async function onTriggerWikiReload() {
  wikiReloadTriggering.value = true
  wikiReloadMessage.value = null
  try {
    const result = await triggerWikiReload()
    wikiReloadMessage.value = result === 'started'
      ? 'Reload started — this runs in the background and may take a few minutes.'
      : 'A reload is already in progress.'
  } finally {
    wikiReloadTriggering.value = false
  }
}
```

---

## Shared Patterns

### Ownership-scoped userId resolution (JWT → email → UserRepository → id)
**Source:** `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` lines 61-65
**Apply to:** `MovieDetailController.retryWiki` (implicitly, via existing `resolveUserId`), `UserController.me`
```java
private UUID resolveUserId(Authentication auth) {
    String email = auth.getName();
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
            .getId();
}
```
This is the newer, preferred convention over `WikiReloadController`'s path-param `assertOwnership(auth, userId)` style — do not copy the older pattern for the new per-film endpoint or `UserController`.

### `findByIdAndUserId` 404-on-mismatch (IDOR protection)
**Source:** `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` (used identically in `getDetail`, `updatePersonal`, `deleteMovie`)
**Apply to:** `MovieDetailService.retryWiki`
```java
Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
```

### Frontend `$fetch` + `authHeaders()` + `credentials: 'include'`
**Source:** `frontend/composables/useMovieDetail.ts` lines 63-68, identically duplicated in `frontend/composables/useSettings.ts`
**Apply to:** All new/modified composable functions (`retryWiki`, `getCurrentUserId`, `triggerWikiReload`)
```typescript
const accessTokenCookie = useCookie<string | null>('access_token')

function authHeaders(): Record<string, string> {
  return accessTokenCookie.value
    ? { Authorization: `Bearer ${accessTokenCookie.value}` }
    : {}
}
```

### SpinnerIcon for in-flight button state
**Source:** `frontend/pages/movies/[id].vue` line 5 (import), lines 112-113 (usage)
**Apply to:** `movies/[id].vue` retry button (D-04); optionally `settings.vue` reload button for consistency
```html
<SpinnerIcon v-if="wikiRetrying" class="w-4 h-4" />
```

## No Analog Found

None — every file in scope has at least a same-file or same-directory strong analog; this phase is purely compositional (see RESEARCH.md summary).

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/{movie,user,admin,enrichment}/`, `backend/src/test/java/de/moviearchive/movie/`, `frontend/{composables,pages,components}/`
**Files scanned:** `MovieDetailController.java`, `MovieDetailService.java`, `WikiReloadService.java`, `WikiReloadController.java`, `useMovieDetail.ts`, `useSettings.ts`, `movies/[id].vue`, `settings.vue`, `user/` package listing
**Pattern extraction date:** 2026-08-23
