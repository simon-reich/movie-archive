# Phase 9: Manual Wiki Retry - Research

**Researched:** 2026-08-23
**Domain:** Spring Boot REST endpoint + Nuxt/Vue detail-page UI, reusing an existing transactional service method (`WikiReloadService.retryWikipedia`)
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Cooldown & Concurrency**
- **D-01:** The manual per-film Retry button always bypasses the 30-day cooldown — it is clickable any time a film has no Wikipedia data (`wikipediaUrl` is null in the DTO), regardless of `wiki_last_attempted_at`. This differs deliberately from batch-reload's cooldown-filtered eligibility (Phase 8 D-03): a manual click is a deliberate one-off user action, not a bulk sweep that needs rate-limit protection.
- **D-02:** No coordination/blocking logic between a manual retry and an in-flight batch-reload run. The two can rarely overlap (batch is paced at 1 call/second; manual retry is a single call), and the worst case is one extra near-simultaneous Wikipedia request — negligible next to the ~630-simultaneous-call incident that motivated Phase 8's pacing. Overlap risk is explicitly accepted.

**Detail Page — Retry Button & Feedback**
- **D-03:** When `movie.wikipediaUrl` is null, the detail page shows "No Wikipedia data found" plus a Retry button in the same full-width area where the Wikipedia Plot/Critical Response sections would otherwise render (`frontend/pages/movies/[id].vue` ~line 329, currently `v-if="movie.wikipediaPlot || movie.wikipediaCritics"` hides the whole section) — the retry prompt replaces that hidden section rather than living elsewhere (e.g. the hero). No `wiki_last_attempted_at` timestamp is surfaced — just the plain "no data found" message.
- **D-04:** While the retry request is in flight (synchronous Wikipedia call, can take a few seconds), the button shows a spinner (reuse `frontend/components/SpinnerIcon.vue`, same pattern used for the page's loading state) and is disabled.
- **D-05:** No toast component (none exists in this app). On success, the `movie` ref updates from the response and the Plot/Critical Response sections render inline in place of the retry prompt. On failure, the "No Wikipedia data found" message stays; Claude's discretion on whether to add a brief inline note (e.g. "Still no page found") distinguishing "never tried" from "just retried and failed again."

**Batch-Reload Trigger Button (folded from todo)**
- **D-06:** Add a lightweight `GET /users/me` endpoint returning the authenticated user's id, reusing the existing `resolveUserId(auth)` pattern from `MovieDetailController` (JWT subject → email → `UserRepository.findByEmail` → id). The Settings page fetches this once on load, then uses the id to call the existing `POST /admin/wiki-reload/{userId}` (Phase 8's contract is unchanged — no path/method changes to that endpoint). — Reversibility: reversible — purely additive endpoint, no existing contract touched.
- **D-07:** Settings page button: on `202 Accepted`, show an inline acknowledgement — "Reload started — this runs in the background and may take a few minutes." On `503` (a batch is already running, per Phase 8's `TaskRejectedException` handler), show "A reload is already in progress." No live progress tracking (explicitly out of scope, consistent with Phase 8's CONTEXT.md rejection of a progress UI for this endpoint).

### Claude's Discretion
- Exact response shape of the new per-film retry endpoint (e.g. return the full updated `MovieDetailResponse`, or just the changed wiki fields + a success/failure flag) — planner's call, informed by what's simplest for the frontend to merge into the existing `movie` ref.
- Exact wording/placement of the optional "retried and still not found" distinction (D-05).
- Whether the new per-film retry endpoint lives on `MovieDetailController` (as `POST /movies/{id}/retry-wiki` or similar, following its `resolveUserId(auth)` + `findByIdAndUserId` convention) or as a new small controller — planner's call; `MovieDetailController` is the closer structural analog since it already does per-movie ownership-scoped operations, unlike `WikiReloadController` which is batch/admin-styled with a path-param userId.
- Exact naming and response shape of the new `GET /users/me` endpoint (e.g. `{ "id": "..." }` vs a fuller user DTO).
- Whether the Settings page's batch-reload button needs any disabled/cooldown state of its own, or is always clickable (no cooldown was discussed for this button specifically — Phase 8's cooldown filtering happens server-side per eligible film, not on whether the button itself can be clicked).

### Deferred Ideas (OUT OF SCOPE)
- Batch-reload running status indicator (e.g. a `GET /admin/wiki-reload/status` endpoint + a banner on the detail page while a batch is in flight) — raised during discussion of the concurrency overlap, but deferred: the overlap risk itself was accepted as low, so the extra status-tracking surface wasn't judged worth building now. Revisit if manual-retry-during-batch overlap turns out to cause real confusion in practice.
- Surfacing `wiki_last_attempted_at` timestamp in the UI — considered for the "no wiki data found" message, but the user chose the simpler no-timestamp version. Could resurface if users want to know "when was this last checked" later.
- Live/incremental progress tracking for the batch-reload run — it stays fire-and-forget (202 Accepted); only a simple acknowledgement message is shown.
- Cooldown enforcement on the manual per-film retry — deliberately bypassed (D-01).
- Any change to TMDB/OMDB data or `movie.status` — this phase only ever touches the Wikipedia fields, reusing Phase 8's `retryWikipedia(Movie)` exactly as built.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-------------------|
| ENRICH-04 | User kann auf der Film-Detailseite eines Films ohne Wikipedia-Daten einen Retry-Button klicken, der einen einzelnen Enrichment-Versuch auslöst | Pattern 1 (ownership-scoped `POST /movies/{id}/retry-wiki`), Pattern 2 (synchronous call into existing `retryWikipedia`), Pattern 4 (frontend retry-button placement at line 329) |
| ENRICH-05 | Retry-Button zeigt Ergebnis an (Erfolg: Wiki-Daten erscheinen; Fehlschlag: Hinweis, `wiki_last_attempted_at` aktualisiert) | Pattern 1 (full `MovieDetailResponse` reuse makes success/failure self-evident via existing `v-if`), Pattern 2 (confirms `wikiLastAttemptedAt` is set unconditionally by the reused method), Code Examples §Full-detail-response reuse |
</phase_requirements>

## Summary

Phase 9 is almost entirely a **compositional** phase: every piece of backend logic it needs (`WikiReloadService.retryWikipedia(Movie)`) already exists, fully built and tested in Phase 8. Nothing in `WikipediaClient`, the 6-step Wikipedia fallback, or the retry/cooldown data model changes. The work is two thin additive slices:

1. **Per-film retry (ENRICH-04/05):** a new `POST /movies/{id}/retry-wiki` endpoint on `MovieDetailController` that (a) resolves `userId` from the JWT the same way `getDetail`/`updatePersonal`/`deleteMovie` already do, (b) loads the movie via the existing `MovieRepository.findByIdAndUserId`, (c) calls `wikiReloadService.retryWikipedia(movie)` synchronously (no `@Async`, no self-invocation concern — the existing method is already `@Transactional` and is called from `WikiReloadService.batchReload` via the same "call it from a different bean/proxy" pattern this new controller will also use), and (d) returns the movie's fresh detail. The simplest response shape is the existing full `MovieDetailResponse` (call `movieDetailService.getDetail(userId, movieId)` after the retry) — this needs **zero new response DTO** and lets the frontend do exactly what it already does on initial load: `movie.value = data`. Because the existing Wikipedia section on the detail page is gated on `movie.wikipediaPlot || movie.wikipediaCritics`, a failed retry (both still null) automatically falls through to the "not found" branch with no extra flag needed.
2. **Batch-reload trigger button (folded todo, D-06/D-07):** a new `GET /users/me` endpoint (first controller in the empty `de.moviearchive.user` package) returning the caller's id, consumed once by the Settings page, which then calls the **existing, unchanged** `POST /admin/wiki-reload/{userId}` and displays one of two static strings depending on `202`/`503`.

Both slices follow conventions that are already fully established and verified in this codebase — ownership-scoped `resolveUserId(auth)` + `findByIdAndUserId` (the `MovieDetailController` convention, not the older path-param `assertOwnership` convention used by `WikiReloadController`/`ReindexController`), `$fetch` + `authHeaders()` + `credentials: 'include'` composables, and `SpinnerIcon` for loading state. No new third-party library, no new Flyway migration, no `SecurityConfig` change (both new endpoints fall under the existing `anyRequest().authenticated()` catch-all).

**Primary recommendation:** Add `POST /movies/{id}/retry-wiki` to `MovieDetailController` (call `wikiReloadService.retryWikipedia(movie)` then return the full `MovieDetailResponse` via `movieDetailService.getDetail`), add `GET /users/me` as a new tiny `UserController` in `de.moviearchive.user`, and drive both frontend additions off the same `ref` + `$fetch` composable pattern already used by `useMovieDetail.ts`/`useSettings.ts` — no new architecture, only new endpoints and template branches.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Single-film Wikipedia retry trigger | API / Backend (`MovieDetailController` → `WikiReloadService.retryWikipedia`) | — | Ownership-scoped, synchronous, reuses Phase 8's transactional service method exactly; no new business logic |
| Retry result feedback (success/failure) | Frontend Server / Browser (`movies/[id].vue`) | API / Backend (response shape) | UI derives success/failure purely from whether `wikipediaPlot`/`wikipediaCritics` are populated in the returned DTO — no new backend flag needed |
| `wiki_last_attempted_at` update on manual retry | Database / Storage (via existing `retryWikipedia`) | — | Already set unconditionally by `WikiReloadService.retryWikipedia` — Phase 9 adds no new write path |
| Authenticated user id lookup (`GET /users/me`) | API / Backend (new `UserController`) | — | First controller in `de.moviearchive.user`; reuses `UserRepository.findByEmail` already used by 3 other controllers |
| Batch-reload trigger button | Browser / Client (`settings.vue`) | API / Backend (existing `POST /admin/wiki-reload/{userId}`, unchanged) | Purely a new UI trigger for an endpoint that already has full server-side logic; button owns only request/response state |

## Standard Stack

No new libraries required. This phase composes existing framework features already locked in `CLAUDE.md` and used by Phase 8:

| Library | Version | Purpose | Why Standard (already in this repo) |
|---------|---------|---------|--------------------------------------|
| Spring Boot / Spring MVC | 3.5.0 [VERIFIED: CLAUDE.md] | REST controller (`@RestController`, `@PostMapping`, `@GetMapping`) | Existing convention across all 9 controllers in the codebase |
| Spring Security (JWT filter) | managed by Spring Boot BOM [VERIFIED: CLAUDE.md] | `Authentication auth` → `auth.getName()` → email → `UserRepository.findByEmail` | Identical pattern in `MovieDetailController`, `WikiReloadController`, `ReindexController` |
| Nuxt 3 / Vue 3 `$fetch` | project-locked [VERIFIED: CLAUDE.md] | Frontend composable HTTP calls | Identical pattern in `useMovieDetail.ts`, `useSettings.ts` |

No `npm install` / `pip install` / new Gradle dependency is required for this phase — see Package Legitimacy Audit below.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Reuse full `MovieDetailResponse` as the retry-endpoint response | A small purpose-built DTO (`{wikipediaUrl, wikipediaPlot, wikipediaCritics, wikipediaSummary}`) | Smaller payload, but requires new DTO + new frontend merge logic (`Object.assign(movie.value, partial)`) instead of the existing `movie.value = data` pattern already proven by `fetchDetail()`; CONTEXT.md explicitly leaves this as Claude's discretion and flags "simplest for the frontend to merge" as the deciding factor — the full-response reuse wins on that criterion |
| New `UserController` in `de.moviearchive.user` for `GET /users/me` | Add `/me` to existing `SettingsController` (`de.moviearchive.settings`) | `SettingsController` already handles account-mutation concerns (email/password/API keys); `GET /users/me` is a read-only identity lookup that fits the `user` package's existing scope (`User`, `UserRepository`) better and keeps `SettingsController` from growing an unrelated identity endpoint |

**Installation:** none — no new dependencies.

**Version verification:** N/A — no new packages.

## Package Legitimacy Audit

**Not applicable — this phase installs no new external packages.** Every backend and frontend symbol used (`WikiReloadService`, `MovieRepository`, `UserRepository`, `$fetch`, `SpinnerIcon`, Vue reactivity primitives) already exists in the repository or ships with a dependency already locked in `CLAUDE.md`/`build.gradle.kts`. The Package Legitimacy Gate is skipped per its own trigger condition ("Every phase that installs external packages").

## Architecture Patterns

### System Architecture Diagram

```
Browser (movies/[id].vue)                         Browser (settings.vue)
   │ click "Retry"                                    │ click "Reload Wikipedia data"
   ▼                                                   ▼
useMovieDetail.ts: retryWiki()                    useSettings.ts: triggerWikiReload()
   │ POST /api/movies/{id}/retry-wiki                 │ 1) GET /api/users/me  (once, cached in ref)
   │ (spinner shown, button disabled)                 │ 2) POST /api/admin/wiki-reload/{userId}
   ▼                                                   ▼
Caddy /api/* → strip prefix → backend:8080        Caddy /api/* → strip prefix → backend:8080
   │                                                   │
   ▼                                                   ▼
MovieDetailController                             UserController          WikiReloadController (UNCHANGED, Phase 8)
  resolveUserId(auth)                               resolveUserId(auth)     assertOwnership(auth, userId)
  findByIdAndUserId(id, userId) → 404 if absent      → { id }               wikiReloadService.batchReload(userId)  [@Async]
  wikiReloadService.retryWikipedia(movie)  [sync,                             → 202 Accepted  or  503 (TaskRejectedException)
     @Transactional, silent-fail-with-log,
     sets wikiLastAttemptedAt on every attempt,
     re-indexes to OpenSearch on success]
  movieDetailService.getDetail(userId, id)
     → full MovieDetailResponse (wiki fields
       reflect success or remain null on failure)
   │
   ▼
200 OK, MovieDetailResponse
   │
   ▼
Frontend: movie.value = data
   → v-if="movie.wikipediaPlot || movie.wikipediaCritics" now true → sections render
   → still false → "No Wikipedia data found" prompt stays (D-05 optional "still not found" note)
```

### Recommended Project Structure

No new directories. New files land in existing packages:

```
backend/src/main/java/de/moviearchive/
├── movie/
│   └── MovieDetailController.java   # MODIFY: add POST /{id}/retry-wiki
│   └── MovieDetailService.java      # MODIFY: add retryWiki(userId, movieId) orchestration
├── user/
│   └── UserController.java          # NEW: GET /me
frontend/
├── composables/
│   ├── useMovieDetail.ts            # MODIFY: add retryWiki()
│   └── useSettings.ts               # MODIFY: add getCurrentUserId() + triggerWikiReload()
├── pages/
│   ├── movies/[id].vue              # MODIFY: v-else branch for no-wiki-data + retry button
│   └── settings.vue                 # MODIFY: new button in a section (Import & Export, or new section)
```

### Pattern 1: Ownership-scoped per-resource retry endpoint

**What:** JWT → email → `UserRepository.findByEmail` → `userId`, then `MovieRepository.findByIdAndUserId(movieId, userId)` to both authorize and fetch in one query, `404` if absent.
**When to use:** Any new per-movie endpoint (this is the codebase's current preferred convention for movie-scoped operations, per `08-CONTEXT.md` D-04/discretion note — newer than the path-param `assertOwnership` style used by `WikiReloadController`/`ReindexController`).
**Example (verbatim from existing code, to copy near-identically):**
```java
// Source: backend/src/main/java/de/moviearchive/movie/MovieDetailController.java:33-38,58-63 (VERIFIED, read this session)
@GetMapping("/{id}")
public ResponseEntity<MovieDetailResponse> getDetail(
        @PathVariable UUID id, Authentication auth) {
    UUID userId = resolveUserId(auth);
    return ResponseEntity.ok(movieDetailService.getDetail(userId, id));
}

private UUID resolveUserId(Authentication auth) {
    String email = auth.getName();
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
            .getId();
}
```
The new endpoint follows the identical shape:
```java
@PostMapping("/{id}/retry-wiki")
public ResponseEntity<MovieDetailResponse> retryWiki(
        @PathVariable UUID id, Authentication auth) {
    UUID userId = resolveUserId(auth);
    return ResponseEntity.ok(movieDetailService.retryWiki(userId, id));
}
```
And in `MovieDetailService` (mirrors the `findByIdAndUserId` 404 pattern already used by `updatePersonal`/`deleteMovie`, `MovieDetailService.java:73-75,110-112`, VERIFIED — `movieRepository.findByIdAndUserId(movieId, userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));`):
```java
public MovieDetailResponse retryWiki(UUID userId, UUID movieId) {
    Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
    wikiReloadService.retryWikipedia(movie);
    return getDetail(userId, movieId);
}
```

### Pattern 2: Synchronous call into an already-`@Transactional` service method — no self-invocation risk

**What:** `WikiReloadService.retryWikipedia(Movie)` is `@Transactional` but plain (not `@Async`). Calling it from `MovieDetailService` (a different Spring bean) goes through the normal Spring AOP proxy — this is exactly the same "call it from a different bean" rule the codebase already documents for `@Async`/`@Retryable`, and it applies cleanly here because the caller is external to `WikiReloadService`.
**When to use:** Whenever invoking `retryWikipedia` from outside `WikiReloadService` (this phase's only use case).
**Example (verbatim, VERIFIED — the exact method the new endpoint calls):**
```java
// Source: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:72-103 (read this session)
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
No cooldown check inside this method — the cooldown filter lives only in `MovieRepository.findEligibleForWikiReload` (used by `batchReload`), never in `retryWikipedia` itself. This confirms D-01 (manual retry bypasses cooldown) requires **zero** new gating code — simply never call the cooldown-filtered query path.

### Pattern 3: Frontend `ref` + `$fetch` composable, full-object replace on success

**What:** Existing composables never merge partial fields into `movie.value` — they either replace the whole ref (`fetchDetail`) or call a void endpoint and mutate local component state directly (`updatePersonal`, which patches `localWatched`/`localRating` before the network call even resolves).
**When to use:** The new `retryWiki()` function in `useMovieDetail.ts`.
**Example (source pattern to extend, VERIFIED):**
```typescript
// Source: frontend/composables/useMovieDetail.ts:74-88 (read this session)
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
New function, same shape, returning the same `MovieDetail` type (no new TS interface needed):
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
    // leave movie.value as-is; template shows a generic failure via wikiRetryAttempted flag
  } finally {
    wikiRetrying.value = false
  }
}
```

### Pattern 4: Retry prompt replaces the hidden Wikipedia section (D-03)

**What:** The existing `v-if` on the Wikipedia section is the exact anchor point named in CONTEXT.md D-03.
**Verbatim current code (VERIFIED — `frontend/pages/movies/[id].vue:329`):**
```html
<div v-if="movie.wikipediaPlot || movie.wikipediaCritics" class="max-w-7xl mx-auto px-4 pb-8 space-y-8 border-t border-border pt-8">
```
**Required change:** add a sibling `v-else` block in the same position containing the "No Wikipedia data found" message and the Retry button, using the existing `SpinnerIcon` component (already imported at `frontend/pages/movies/[id].vue:5`, VERIFIED — `import SpinnerIcon from '@/components/SpinnerIcon.vue'`) for the in-flight state:
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
`wikiRetryAttempted` is a local page-level `ref(false)` set to `true` after the first `retryWiki()` call resolves (regardless of outcome) — this implements the D-05 discretion item ("Still no page found") without any new backend field, matching the deferred idea "no timestamp surfaced."

### Pattern 5: New `UserController` — first controller in `de.moviearchive.user`

**What:** `de.moviearchive.user` currently holds only `User.java`, `UserRepository.java`, `UserStatus.java` — no controller (VERIFIED by directory listing this session: `find backend/src/main/java/de/moviearchive/user` returned exactly those 3 files, no `*Controller.java`).
**Example, following the `resolveUserId` convention verbatim (same as Pattern 1):**
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
No `SecurityConfig` change required — `/users/me` is not in the `permitAll()` list (`/auth/**`, `/actuator/health`, `/settings/confirm-email`, `/test/**`; VERIFIED, `SecurityConfig.java:30` — `.requestMatchers("/auth/**", "/actuator/health", "/settings/confirm-email", "/test/**").permitAll()`) so it correctly falls under `.anyRequest().authenticated()` (`SecurityConfig.java:31`, VERIFIED).

### Anti-Patterns to Avoid
- **Do not add cooldown/eligibility logic to the new retry endpoint.** D-01 is explicit: the manual button is always clickable. Do not reuse `findEligibleForWikiReload` or check `wikiLastAttemptedAt` before calling `retryWikipedia`.
- **Do not add `@Async` to the new controller method or to any new service method that wraps `retryWikipedia`.** The UAT requirement is synchronous ("immediately see whether it succeeded") — `@Async` would return before the result is known, contradicting ENRICH-05.
- **Do not build a new response DTO unless the planner deliberately chooses the lightweight-payload alternative.** Reusing `MovieDetailResponse` via `movieDetailService.getDetail` avoids a second JSON-parsing/extraction code path that would have to be kept in sync with `MovieDetailService`'s many `textOrNull`/`extract*` helpers.
- **Do not add `hasRole("ADMIN")` or any new authority check.** Confirmed (again, this session) no `ROLE_ADMIN`/authority concept exists anywhere in `SecurityConfig` or `User`.
- **Do not add coordination/locking between the new endpoint and `WikiReloadService.batchReload`.** D-02 explicitly accepts the overlap risk; the two are unrelated code paths (batch uses the dedicated `wikiReloadExecutor` thread pool; the new endpoint runs on the request thread).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Single Wikipedia retry logic | A new Wikipedia-fetch-and-save method | `WikiReloadService.retryWikipedia(Movie)` (existing, tested) | CONTEXT.md D-boundary explicitly forbids touching `WikipediaClient`/the 6-step fallback in this phase — reuse is mandatory, not just convenient |
| "Was this the user's first attempt" tracking | A new DB column/flag | Page-local `wikiRetryAttempted` ref (session-only) | Deferred idea explicitly rejects surfacing `wiki_last_attempted_at` in the UI; a client-side-only flag satisfies D-05's discretion without new backend state |
| Batch-running status detection | `GET /admin/wiki-reload/status` + polling/banner | Nothing — out of scope | Explicitly deferred; D-02 accepts the overlap risk as negligible |
| userId resolution on the Settings page | Decoding the JWT client-side | New `GET /users/me` endpoint | The access-token cookie holds only `email`/`accessToken` client-side (`stores/auth.ts`, VERIFIED — `const accessToken = computed(...); const userEmail = computed(...); const isAuthenticated = computed(...)`, no id); decoding a JWT client-side to extract a subject/claim is exactly the kind of hand-rolled auth logic to avoid — a dedicated backend endpoint is simpler and keeps token parsing server-side only |

**Key insight:** Every "hard part" of this phase (Wikipedia fetch, fallback cascade, cooldown timestamp, rate-limit protection, silent-failure logging) was solved in Phase 8. Phase 9's entire job is wiring — new call sites into `retryWikipedia`, one new trivial identity endpoint, and template branching. Resist the temptation to add new abstractions.

## Common Pitfalls

### Pitfall 1: Returning a lean DTO but forgetting the frontend still needs `wikipediaSummary`/full detail for other page sections
**What goes wrong:** If the planner picks the "small DTO" alternative instead of the recommended full-`MovieDetailResponse` reuse, the frontend must merge fields into `movie.value` (e.g. `Object.assign`) rather than replace it — forgetting this and doing `movie.value = partialData` would null out all the non-wiki fields (cast, crew, ratings) and break the rest of the page.
**Why it happens:** The existing `fetchDetail()`/`retryWiki()` pattern trains on "replace `movie.value` wholesale," which is only safe if the response actually contains the full object.
**How to avoid:** Either return the full `MovieDetailResponse` (recommended — makes replace-the-whole-ref safe) or, if a lean DTO is chosen, explicitly merge (`movie.value = { ...movie.value, ...data }`) — never a plain assignment.
**Warning signs:** Cast/crew/rating sections disappearing after a successful retry in manual testing.

### Pitfall 2: Adding a redundant transaction wrapper around `retryWikipedia`
**What goes wrong:** Wrapping the call to `wikiReloadService.retryWikipedia(movie)` in a second `@Transactional` method in `MovieDetailService` is unnecessary — `retryWikipedia` is already `@Transactional` and manages its own `save()` calls on both success and every failure branch.
**Why it happens:** Habit of wrapping service-orchestration methods in `@Transactional` "to be safe."
**How to avoid:** Let `retryWikipedia`'s own transaction boundary stand; `MovieDetailService.retryWiki(userId, movieId)` itself does not need `@Transactional` (its own `findByIdAndUserId` read and the subsequent `getDetail` read are each independently fine as non-transactional reads, matching how `updatePersonal`/`deleteMovie` are also not `@Transactional` at the orchestration level — verified in `MovieDetailService.java`, no `@Transactional` annotation present on the class or its public methods).
**Warning signs:** None functionally (extra `@Transactional` wouldn't break correctness here), but it adds an unjustified nested-transaction dependency that diverges from the established pattern for no reason.

### Pitfall 3: Forgetting `movies/{id}/retry-wiki` needs a `POST`, not a `PUT`/`PATCH`
**What goes wrong:** The action is a command ("retry now"), not an idempotent resource update — using `PATCH` (which this controller already uses for `/personal`) would be semantically wrong and could confuse future maintainers about idempotency (retrying twice legitimately re-fetches Wikipedia twice, unlike `PATCH /personal` which is a pure state update).
**Why it happens:** `MovieDetailController` already has a `PATCH` verb in scope (`/personal`), inviting pattern-matching to the wrong HTTP verb.
**How to avoid:** Use `POST`, matching `POST /movies/save` and `POST /admin/wiki-reload/{userId}` — the codebase's existing convention for "trigger an action" endpoints.
**Warning signs:** None at runtime (Spring doesn't enforce this), but it's a code-review-catchable convention drift.

### Pitfall 4: `GET /users/me` returning the full `User` entity instead of a minimal shape
**What goes wrong:** Returning the JPA `User` entity directly from the controller risks serializing `passwordHash` (a BCrypt hash) into the JSON response — a real security leak, not just a style issue.
**Why it happens:** `User` has public getters via Lombok `@Getter` and no `@JsonIgnore` on `passwordHash` (VERIFIED — `User.java` has no Jackson annotations at all).
**How to avoid:** Return `Map.of("id", id)` (as shown in Pattern 5) or a purpose-built record — never the entity itself.
**Warning signs:** `passwordHash` field visible in the browser Network tab response body.

### Pitfall 5: Settings page fetching `/users/me` on every render instead of once
**What goes wrong:** If `getCurrentUserId()` is called inside the click handler for the reload button every time, it adds an avoidable extra round-trip before every trigger click; if it's fetched in a `computed`/reactive context without caching, it may re-fire unexpectedly.
**Why it happens:** No existing precedent in this codebase for "fetch identity once, reuse."
**How to avoid:** Fetch once in `onMounted` (mirroring the existing `onMounted(async () => { ...; keysLoading.value = true; const keys = await loadApiKeys(); ... })` block already in `settings.vue`, VERIFIED lines 66-89) and store in a local `ref<string | null>(null)`; reuse that ref's value on every button click, only re-fetching if it's still `null`.
**Warning signs:** Network tab showing a `GET /api/users/me` call on every reload-button click.

## Code Examples

### Full-detail-response reuse for the retry endpoint
```java
// New method in MovieDetailService.java, sibling to getDetail/updatePersonal/deleteMovie
public MovieDetailResponse retryWiki(UUID userId, UUID movieId) {
    Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
    wikiReloadService.retryWikipedia(movie);
    return getDetail(userId, movieId);
}
```

### Settings-page batch-reload trigger (D-07 messages)
```typescript
// frontend/composables/useSettings.ts — new additions
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
The two D-07 messages ("Reload started — this runs in the background and may take a few minutes." / "A reload is already in progress.") map directly to the `'started'`/`'already-running'` return values — matches the existing `WikiReloadController` 503 body (VERIFIED, `admin/WikiReloadController.java:88-89` — `return ResponseEntity.status(503).body(Map.of("message", "A wiki-reload batch is already in progress; try again shortly."));`), though the frontend does not need to read that exact message string since D-07 specifies its own UI copy.

## State of the Art

Not applicable — this phase makes no framework-version or dependency changes. All patterns reused are already the current, most-recent convention in this codebase (Phase 8, dated 2026-08-22, one day before this research).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The new retry endpoint should live at `POST /movies/{id}/retry-wiki` (exact path string) rather than some other name/verb | Pattern 1, Pitfall 3 | Low — CONTEXT.md already names this exact path as an example ("`POST /movies/{id}/retry-wiki` or similar") and leaves final naming to the planner; any consistent REST-ish name works equally well functionally |
| A2 | Recommending full-`MovieDetailResponse` reuse over a lean DTO for the retry endpoint's response shape | Standard Stack (Alternatives), Pattern 1, Pitfall 1 | Low-Medium — this is explicitly left as Claude's/planner's discretion in CONTEXT.md; if the planner instead prefers the lean-DTO route for payload-size reasons, the frontend merge logic (not a plain replace) must be added — flagged in Pitfall 1 |
| A3 | `GET /users/me` best lives in a new `UserController` under `de.moviearchive.user` rather than on `SettingsController` | Standard Stack (Alternatives), Pattern 5 | Low — purely an organizational choice; either location produces working, secure code, and CONTEXT.md leaves exact naming/shape as discretion |

**All other claims in this research were verified by reading the actual source files this session** (see Sources) — no unverified library versions, no unverified API contracts.

## Open Questions

1. **Should `retryWiki` in `MovieDetailService` also be `@Transactional`?**
   - What we know: `updatePersonal`/`deleteMovie`/`getDetail` are not `@Transactional` at the orchestration level; `retryWikipedia` itself already is.
   - What's unclear: Whether calling a `@Transactional` method from a non-transactional caller, then immediately calling a second non-transactional read (`getDetail`), could observe stale data in any edge case (it should not, since `retryWikipedia`'s transaction commits before returning).
   - Recommendation: Follow the existing `MovieDetailService` convention (no class-level or method-level `@Transactional` outside `retryWikipedia` itself) — this exactly mirrors how `batchReload` already calls `self.retryWikipedia(movie)` without wrapping that call in an outer transaction.

## Environment Availability

Skipped — this phase has no new external tool/service dependency. It reuses PostgreSQL, OpenSearch, and the Wikipedia API integration exactly as configured for Phase 8 (already verified working in that phase's test suite).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 + Mockito + Testcontainers (Spring Boot Test), [VERIFIED: CLAUDE.md, and confirmed live in `WikiReloadServiceTest.java`/`MovieDetailControllerTest.java` read this session] |
| Backend config file | `backend/build.gradle.kts` (test deps already present, no changes needed) |
| Frontend framework | Vitest + Vue Test Utils + MSW, [VERIFIED: CLAUDE.md, confirmed live in `frontend/test/unit/pages/movies-id.spec.ts` and `frontend/test/mocks/handlers/movieDetail.ts` read this session] |
| Quick run command (backend) | `./gradlew test --tests "*MovieDetail*"` (targeted) |
| Quick run command (frontend) | `pnpm vitest run test/unit/pages/movies-id.spec.ts test/unit/composables/useMovieDetail.spec.ts` |
| Full suite command (backend) | `./gradlew test` |
| Full suite command (frontend) | `pnpm vitest run` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| ENRICH-04 | Retry button visible only when `wikipediaUrl` is null; click triggers single-film call | unit (frontend component) | `pnpm vitest run test/unit/pages/movies-id.spec.ts` | ❌ Wave 0 — extend existing `movies-id.spec.ts` with new cases |
| ENRICH-04 | `POST /movies/{id}/retry-wiki` calls `retryWikipedia` exactly once, is ownership-scoped (404/403 on mismatch) | integration (backend, MockMvc + Testcontainers) | `./gradlew test --tests "MovieDetailControllerTest"` | ❌ Wave 0 — extend existing `MovieDetailControllerTest.java` |
| ENRICH-05 | On success, `wikipediaPlot`/`wikipediaCritics` render; on failure, "No Wikipedia data found" message shown, `wiki_last_attempted_at` updated | unit (frontend) + integration (backend, reusing `WikiReloadServiceTest` assertions on `wikiLastAttemptedAt`) | `./gradlew test --tests "WikiReloadServiceTest"` + `pnpm vitest run test/unit/pages/movies-id.spec.ts` | ✅ backend assertions pattern exists (`WikiReloadServiceTest.java:73-103`) — reuse; ❌ frontend cases — Wave 0 |
| folded todo | `GET /users/me` returns caller's id, ownership-safe (JWT-derived, no cross-user leak possible by construction) | integration (backend, new small test class) | `./gradlew test --tests "UserControllerTest"` | ❌ Wave 0 — new test file, mirrors `MovieDetailControllerTest`'s `createActiveUser`/`loginAndGetToken` helpers |
| folded todo | Settings button shows correct message for 202 vs 503 | unit (frontend) | `pnpm vitest run test/unit/pages/settings.spec.ts` | Check — confirm existing `settings.vue` test file name/location before Wave 0 (not located during this research session; grep `frontend/test/unit/pages` for `settings*` before planning tests) |

### Sampling Rate
- **Per task commit:** targeted `./gradlew test --tests "<TestClass>"` / `pnpm vitest run <file>`
- **Per wave merge:** `./gradlew test` (backend) + `pnpm vitest run` (frontend)
- **Phase gate:** Full suite green (both backend and frontend) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/user/UserControllerTest.java` — new file, covers `GET /users/me`
- [ ] Extend `backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java` — covers `POST /{id}/retry-wiki` (success, failure/not-found-on-Wikipedia, 404 wrong movie, 403/404 wrong user)
- [ ] Extend `frontend/test/unit/pages/movies-id.spec.ts` — covers retry button visibility, click → spinner → success/failure rendering
- [ ] Extend `frontend/test/mocks/handlers/movieDetail.ts` — add MSW handler for `POST /api/movies/:id/retry-wiki`
- [ ] Extend `frontend/test/unit/composables/useMovieDetail.spec.ts` — covers new `retryWiki()` function
- [ ] Locate or create `frontend/test/unit/pages/settings.spec.ts` — planner must confirm during Wave 0 whether a settings page test file already exists (not found in this research session's targeted search — a broader search of `frontend/test/unit/pages/` is needed before writing new tests, to avoid creating a duplicate file)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | yes | Existing JWT filter (`JwtAuthFilter`) — no change; both new endpoints require a valid bearer token via `anyRequest().authenticated()` |
| V3 Session Management | no | No new session/cookie handling introduced |
| V4 Access Control | yes | `resolveUserId(auth)` + `findByIdAndUserId` (IDOR protection) for the retry endpoint; `GET /users/me` is inherently self-scoped (returns only the caller's own id, derived from their own JWT — cannot be parameterized to another user) |
| V5 Input Validation | yes | `@PathVariable UUID id` — Spring's built-in UUID path-variable binding rejects malformed UUIDs with a 400 before the controller method runs (same as all 3 existing `@PathVariable UUID` uses in `MovieDetailController`) |
| V6 Cryptography | no | No new cryptographic operation; `passwordHash` must NOT be exposed by the new `GET /users/me` response (see Pitfall 4) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| IDOR — retrying/reading another user's movie via `id` path param | Elevation of Privilege | `findByIdAndUserId(movieId, userId)` — a movie owned by a different user simply is not found (404), never a 403-with-data-leak; this is the exact pattern already used by `getDetail`/`updatePersonal`/`deleteMovie` |
| Sensitive field leakage via `GET /users/me` | Information Disclosure | Return a minimal `Map.of("id", id)` (or purpose-built record) — never the raw `User` entity, which has an unguarded `passwordHash` field |
| Abuse of the manual retry button as an unthrottled Wikipedia-call amplifier | Denial of Service (external, against Wikipedia — the scenario Phase 8 was built to prevent for the batch path) | Explicitly accepted risk per D-01/D-02 — a human clicking a UI button one movie at a time cannot approach the ~630-simultaneous-call incident scale; no rate-limiting added in this phase, consistent with CONTEXT.md's explicit scope boundary |

## Sources

### Primary (HIGH confidence — all read directly this session)
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — `retryWikipedia(Movie)` full method body, `batchReload` self-invocation pattern
- `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` — `resolveUserId` convention, full controller
- `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` — `getDetail`/`updatePersonal`/`deleteMovie`, `findByIdAndUserId` 404 pattern
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — `findByIdAndUserId`, `findEligibleForWikiReload` (cooldown query, confirms no cooldown logic in `retryWikipedia` itself)
- `backend/src/main/java/de/moviearchive/movie/dto/MovieDetailResponse.java` — full field list, confirms `wikipediaPlot`/`wikipediaCritics`/`wikipediaSummary`/`wikipediaUrl` JSON names
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — existing batch endpoint, 503 handler message, unchanged by this phase
- `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` — `permitAll()` list, confirms new endpoints need no security config change
- `backend/src/main/java/de/moviearchive/user/User.java`, `UserRepository.java` — confirms no `/users` controller exists yet, confirms no `passwordHash` guard
- `frontend/composables/useMovieDetail.ts` — `fetchDetail`/`updatePersonal`/`deleteMovie` composable pattern, `MovieDetail` TS interface
- `frontend/composables/useSettings.ts` — `authHeaders`/`$fetch` pattern for settings-page calls
- `frontend/stores/auth.ts` — confirms no `userId` available client-side (only `accessToken`/`userEmail`), justifying `GET /users/me`
- `frontend/pages/movies/[id].vue` — exact line 329 `v-if` condition to branch on
- `frontend/pages/settings.vue` — existing section structure/styling and `onMounted` fetch-once pattern
- `frontend/nuxt.config.ts` — `/api` proxy config confirming `/api/movies/...` request paths reach the backend correctly
- `.planning/phases/08-wiki-enrichment-tracking-batch-reload/08-PATTERNS.md` — Phase 8's full pattern map, confirms `retryWikipedia` is the canonical reusable unit
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java`, `MovieDetailControllerTest.java` — existing test conventions to extend
- `frontend/test/mocks/handlers/movieDetail.ts`, `frontend/test/unit/pages/movies-id.spec.ts` — existing frontend test/mock conventions to extend

### Secondary (MEDIUM confidence)
- None used — all findings verified directly against source in this session.

### Tertiary (LOW confidence)
- None.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all reused APIs read directly from source this session
- Architecture: HIGH — every pattern cited is a verbatim read of existing, working code from Phase 8/6
- Pitfalls: HIGH — derived from direct comparison of existing conventions vs. the two design-discretion points CONTEXT.md leaves open

**Research date:** 2026-08-23
**Valid until:** Effectively indefinite for this specific phase (no external API/version dependency); re-verify only if Phase 8's `WikiReloadService` or `MovieDetailController` changes before Phase 9 execution begins.
