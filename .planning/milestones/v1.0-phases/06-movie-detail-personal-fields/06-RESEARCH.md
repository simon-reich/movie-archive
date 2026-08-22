# Phase 6: Movie Detail & Personal Fields - Research

**Researched:** 2026-05-18
**Domain:** Spring Boot REST endpoints, OpenSearch partial update, Nuxt 3 detail page, Tailwind two-column layout, lazy YouTube embed
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Detail page route `/movies/{id}` (UUID). Linked from MovieCard.vue and MovieListItem.vue — Phase 6 activates those links.
- **D-02:** Full-width backdrop image as hero background. Poster image overlaid on the left. Title, year, tagline on the right side of the hero. Cinematic layout.
- **D-03:** Two-column layout below hero. Left/main column: primary facts + Wikipedia content. Right sidebar: personal fields + trailer embed. Column ratio at Claude's discretion — must fit a 16:9 trailer embed.
- **D-04:** Left column content order: (1) primary facts, (2) overview/synopsis, (3) Wikipedia plot + critics.
- **D-05:** Full cast & crew at the very bottom of the page, spanning full width. Credits-style layout, 2–3 columns. Claude decides column count and grouping.
- **D-06:** Rating widget: 10-star rating (1–10, integer). Clickable stars.
- **D-07:** Auto-save on each change. Watched saves immediately. Star rating saves on click. Notes auto-save after ~1s debounce. No explicit save button.
- **D-08:** Backend: `PATCH /movies/{id}/personal` — accepts `{ watched, personalRating, personalNotes }` (all optional). After Postgres write, upsert the OS document so personal fields are searchable.
- **D-09:** Postgres migration V7: add `watched BOOLEAN DEFAULT FALSE NOT NULL`, `personal_rating SMALLINT`, `personal_notes TEXT`.
- **D-10:** When `raw_omdb_json` is null: hide OMDB-sourced fields individually and silently. No placeholder. Only show fields with data.
- **D-11:** YouTube thumbnail as lazy play button. Clicking thumbnail loads the iframe. No YouTube request until user clicks. Omit trailer section when no `trailer_key`.
- **D-12:** Clickable actors/directors/genres navigate to `/search?director=X`, `/search?genre=X`, `/search?actor=X` — same format as Phase 5 D-14.
- **D-13:** Delete button — Claude's discretion on placement (hero area or dedicated action bar). Confirmation modal: "Remove from archive? This cannot be undone." + Confirm (terracotta) / Cancel. On confirmation: DELETE to backend, redirect to `/search`.
- **D-14:** Backend: `DELETE /movies/{id}`. Ownership check via JWT → email → userId. OpenSearch removal first, then Postgres delete. Idempotent: 404 if not found.

### Claude's Discretion

- Column width ratio for the two-column layout
- Delete button placement (hero or sidebar top)
- Full cast & crew column count (2 or 3) and grouping
- Exact star rating component implementation (lucide-vue-next icons or SVG)
- Debounce duration for notes auto-save (~1s)
- Poster/backdrop fallback when image is unavailable

### Deferred Ideas (OUT OF SCOPE)

- Average personal rating on dashboard — after Phase 6 writes personal_rating
- Watched vs. unwatched count on dashboard
- Edit TMDB/OMDB data — snapshot strategy (frozen at save time)
- Mobile polish for detail page — Phase 7 (QLTY-01)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DETAIL-01 | Film detail page shows all TMDB + OMDB fields; nullable OMDB fields hidden when absent | GET /movies/{id} DTO covers all 40+ fields; conditional rendering pattern for OMDB |
| DETAIL-02 | Film detail page shows Wikipedia plot and critics sections when available | wiki_plot, wiki_critics, wiki_summary stored in Movie entity; conditional render when non-null |
| DETAIL-03 | User can set watched status, personal rating (0–10), and free-text notes; notes are indexed for search | PATCH /movies/{id}/personal + V7 Flyway migration + OS partial update via update API |
| DETAIL-04 | Film detail page shows YouTube trailer embed via TMDB trailer_key when available | Lazy YouTube embed pattern (thumbnail + iframe swap on click) |
| DETAIL-05 | Clicking actor/director/genre opens filtered search results list | Router.push pattern already established in MovieCard.vue and Phase 5 |
</phase_requirements>

---

## Summary

Phase 6 delivers the film detail page — the richest view in the application — and writes the first user-generated data into the archive (watched status, personal rating, notes). The backend requires two new endpoints (`GET /movies/{id}` and `PATCH /movies/{id}/personal`) plus a `DELETE /movies/{id}` endpoint, and a Flyway V7 migration to add personal columns to the movies table. The frontend requires a new Nuxt page at `/movies/[id].vue`, a `useMovieDetail.ts` composable, and several new components (hero, personal fields panel, trailer embed, delete modal, cast/crew credits).

The existing codebase provides strong patterns to follow. `SearchController.java` demonstrates the exact ownership resolution pattern (`resolveUserId` via `userRepository.findByEmail(auth.getName())`). `IndexingService.java` provides the OpenSearch client usage. `MovieCard.vue` and `MovieListItem.vue` already have clickable director/genre navigation via `router.push` — Phase 6 extends this to actors and wraps the card itself with `<NuxtLink>`. The `useSearch.ts` composable establishes the pattern for `useMovieDetail.ts`.

The most novel work is the OpenSearch partial update for personal fields — this uses the OS `_update` API (not the index/replace API used elsewhere). Careful DTO design is needed: the detail response must include all 40+ fields from the OS document plus the personal fields now stored in Postgres. The planner must decide whether to read from Postgres (authoritative) or from OpenSearch for the detail endpoint — reading from Postgres via the Movie entity is simpler and more correct.

**Primary recommendation:** Read movie detail from Postgres (Movie entity + raw JSON parse), not from OpenSearch. OS is used for search, not as the source of truth. Use the OS `_update` API to keep personal fields in sync after each PATCH.

---

## Standard Stack

### Core (existing — no new installs)
| Library | Version | Purpose | Notes |
|---------|---------|---------|-------|
| Spring Boot | 3.5.0 | HTTP layer, DI | [VERIFIED: build.gradle.kts] |
| Spring Security | BOM-managed | JWT auth filter | [VERIFIED: build.gradle.kts] |
| opensearch-java | 2.19.0 | OS update API | [VERIFIED: build.gradle.kts] |
| Jackson | BOM-managed | JsonNode access from rawTmdbJson/rawOmdbJson | [VERIFIED: existing DocumentBuilder] |
| Flyway | BOM-managed | V7 migration | [VERIFIED: V6 pattern in repo] |
| Nuxt 3 | installed | SSR/CSR page router | [VERIFIED: nuxt.config.ts] |
| Pinia | installed | search store (already used) | [VERIFIED: frontend/stores/] |
| lucide-vue-next | 0.487.x | icons (star rating, trash icon for delete) | [VERIFIED: UI-SPEC.md] |

**Installation:** No new packages required. All dependencies already on classpath.

---

## Architecture Patterns

### Backend: Data Source for Detail Endpoint

**Decision: Read from Postgres, not OpenSearch.**

The Movie entity stores `raw_tmdb_json` (JSONB) and `raw_omdb_json` (JSONB), plus all scalar fields. This is the source of truth. The detail endpoint reads one row by `(id, userId)` and parses the JSON blobs inline — no OpenSearch round-trip needed. [VERIFIED: Movie.java + MovieRepository.java]

```
GET /movies/{id}
  → MovieDetailController
  → MovieDetailService.getDetail(email, movieId)
      → userRepository.findByEmail(email)  // resolve userId
      → movieRepository.findByIdAndUserId(movieId, userId)  // ownership check
      → parse rawTmdbJson + rawOmdbJson with Jackson
      → assemble MovieDetailResponse DTO
  → 200 OK with full DTO
```

### Backend: PATCH /movies/{id}/personal

After writing to Postgres, upsert the OS document personal fields using the `_update` API (partial doc update). This does NOT replace the entire document — only the changed fields.

```java
// OpenSearch partial update — only personal fields
client.update(UpdateRequest.of(r -> r
    .index(indexName)
    .id(movieId.toString())
    .doc(Map.of(
        "watched", watched,
        "personal_rating", personalRating,
        "personal_notes", personalNotes
    ))), Map.class);
```

**Note on UpdateRequest:** `opensearch-java 2.x` uses `UpdateRequest.of(...)` with `.doc(Map)`. The typed builder's generic parameter for doc type can be `Map.class`. [ASSUMED — verify against opensearch-java 2.19.0 client API, but pattern consistent with IndexRequest usage in IndexingService]

### Backend: DELETE /movies/{id}

Order matters: remove from OpenSearch first (idempotent on failure), then delete from Postgres.

```
DELETE /movies/{id}
  → MovieDetailController
  → MovieDetailService.deleteMovie(email, movieId)
      → resolve userId
      → movieRepository.findByIdAndUserId(movieId, userId) → 404 if not found
      → indexingService.deleteDocument(indexName, movieId)  // new method
      → movieRepository.deleteById(movieId)
  → 204 No Content
```

OpenSearch delete uses: `client.delete(DeleteRequest.of(r -> r.index(indexName).id(movieId.toString())))`. Index-not-found exception on delete should be swallowed (same pattern as fullReindex). [VERIFIED: IndexingService.fullReindex swallows index_not_found_exception]

### Backend: DTO Design

`MovieDetailResponse` must expose all fields the frontend needs. Source mapping:

| DTO Field | Source | Notes |
|-----------|--------|-------|
| id | movie.id | UUID |
| tmdbId | movie.tmdbId | |
| imdbId | movie.imdbId | nullable |
| title | movie.title | |
| originalTitle | movie.originalTitle | nullable |
| tagline | rawTmdbJson.tagline | nullable |
| overview | rawTmdbJson.overview | nullable |
| releaseDate | movie.releaseDate | ISO date |
| year | movie.releaseDate.year | computed |
| runtime | movie.runtime | nullable |
| posterPath | rawTmdbJson.poster_path | nullable |
| backdropPath | rawTmdbJson.backdrop_path | nullable |
| voteAverage | rawTmdbJson.vote_average | nullable |
| voteCount | rawTmdbJson.vote_count | nullable |
| trailerKey | extracted from rawTmdbJson.videos.results | nullable |
| genreList | rawTmdbJson.genres[].name | |
| directorList | extracted from rawTmdbJson.credits.crew | |
| writerList | extracted from rawTmdbJson.credits.crew | |
| mainCast | rawOmdbJson.Actors | nullable (OMDB absent) |
| fullCast | rawTmdbJson.credits.cast[] | list of {name, character, order, profilePath} |
| fullCrew | rawTmdbJson.credits.crew[] | list of {name, job, department, profilePath} |
| countryList | rawTmdbJson.production_countries[] | |
| languageList | rawTmdbJson.spoken_languages[] | |
| imdbRating | rawOmdbJson.imdbRating | nullable |
| imdbVotes | rawOmdbJson.imdbVotes | nullable |
| contentRating | rawOmdbJson.Rated | nullable |
| boxOffice | rawOmdbJson.BoxOffice | nullable |
| ratingList | rawOmdbJson.Ratings[] | nullable (RT, Metacritic, IMDB) |
| imdbLink | computed: imdbId != null → URL | nullable |
| wikipediaPlot | movie.wikiPlot | nullable |
| wikipediaCritics | movie.wikiCritics | nullable |
| wikipediaSummary | movie.wikiSummary | nullable |
| wikipediaUrl | movie.wikiUrl | nullable |
| watched | movie.watched | boolean, always present (DEFAULT FALSE) |
| personalRating | movie.personalRating | nullable SMALLINT |
| personalNotes | movie.personalNotes | nullable TEXT |

**Important:** `DocumentBuilder` already does the rawTmdbJson/rawOmdbJson parsing logic for all these fields. The service can reuse the same Jackson traversal patterns. [VERIFIED: DocumentBuilder.java — all field paths confirmed]

### Frontend: Page Architecture — CSR or SSR?

**Recommendation: CSR (client-side fetch via `useAsyncData` with `server: false`).**

Rationale: The detail page requires a valid JWT access token cookie for the API call. In SSR context, the Nuxt proxy forwards cookies, but this adds complexity. The existing authenticated pages (`/search`, `/add`, `/settings`) all use client-side `$fetch` within composables (no `useAsyncData`). For consistency, `useMovieDetail.ts` should follow the same pattern: `$fetch` on mount with `authHeaders()`. [VERIFIED: useSearch.ts pattern — $fetch with headers, no useAsyncData]

However, note that Nuxt's `useFetch`/`useAsyncData` with `credentials: 'include'` would also work for SSR if the access_token cookie is forwarded. For this phase, client-side fetch is sufficient and consistent with the existing pattern.

### Frontend: Page Route

Create `frontend/pages/movies/[id].vue`. Nuxt 3 file-based routing maps this to `/movies/:id`. The `useRoute().params.id` gives the UUID string. [VERIFIED: nuxt.config.ts — file-based routing, no custom router config]

The global auth middleware `auth.global.ts` already protects all non-public routes, so `/movies/[id]` is protected automatically without any additional middleware. [VERIFIED: auth.global.ts]

### Frontend: Composable Pattern

`useMovieDetail.ts` follows the same structure as `useSearch.ts`:

```typescript
export function useMovieDetail(movieId: string) {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const movie = ref<MovieDetail | null>(null)
  const isLoading = ref(true)
  const error = ref<string | null>(null)

  async function fetchDetail(): Promise<void> { ... }

  async function updatePersonal(fields: Partial<PersonalFields>): Promise<void> { ... }

  async function deleteMovie(): Promise<void> { ... }

  onMounted(() => fetchDetail())

  return { movie, isLoading, error, updatePersonal, deleteMovie }
}
```

### Frontend: Two-Column Layout

Tailwind grid for the two-column layout below hero. The sidebar must fit a 16:9 embed — at typical screen widths (1280px with px-4 padding = 1248px usable), a 2/3 + 1/3 grid works:
- Left column: `col-span-2` (≈832px) — primary facts + Wikipedia
- Right sidebar: `col-span-1` (≈416px) — personal fields + trailer

A 16:9 trailer embed in a 416px-wide sidebar: `416 × 9/16 ≈ 234px` height. Perfectly usable.

```html
<div class="grid grid-cols-3 gap-8">
  <div class="col-span-2"> <!-- main column --> </div>
  <div class="col-span-1"> <!-- sidebar --> </div>
</div>
```

[VERIFIED: Tailwind CSS grid pattern — standard practice]

### Frontend: Hero Section

```html
<div class="relative w-full h-72 overflow-hidden">
  <!-- Backdrop -->
  <img :src="backdropUrl" class="absolute inset-0 w-full h-full object-cover" />
  <!-- Dark gradient overlay for readability -->
  <div class="absolute inset-0 bg-gradient-to-r from-black/80 via-black/50 to-transparent" />
  <!-- Content -->
  <div class="relative z-10 flex items-end gap-6 h-full px-8 pb-6">
    <img :src="posterUrl" class="w-32 aspect-[2/3] object-cover border border-border flex-shrink-0" />
    <div>
      <h1 class="text-2xl font-semibold tracking-wide text-white">{{ movie.title }}</h1>
      <p class="text-sm text-white/70">{{ movie.year }}</p>
      <p class="text-sm text-white/60 italic mt-1">{{ movie.tagline }}</p>
    </div>
  </div>
</div>
```

No `rounded-*` per UI-SPEC. [VERIFIED: UI-SPEC.md — rounded-none everywhere]

### Frontend: Lazy YouTube Embed (D-11)

```html
<template v-if="movie.trailerKey">
  <div v-if="!trailerActive" class="relative cursor-pointer" @click="trailerActive = true">
    <img
      :src="`https://img.youtube.com/vi/${movie.trailerKey}/hqdefault.jpg`"
      class="w-full aspect-video object-cover"
    />
    <!-- Play button overlay -->
    <div class="absolute inset-0 flex items-center justify-center">
      <div class="w-12 h-12 bg-primary flex items-center justify-center">
        <PlayIcon class="w-6 h-6 text-primary-foreground ml-1" />
      </div>
    </div>
  </div>
  <iframe
    v-else
    :src="`https://www.youtube.com/embed/${movie.trailerKey}?autoplay=1`"
    class="w-full aspect-video"
    allow="autoplay; encrypted-media"
    frameborder="0"
  />
</template>
```

YouTube thumbnail URL: `https://img.youtube.com/vi/{key}/hqdefault.jpg` [VERIFIED: api-contracts.md]
YouTube embed URL: `https://www.youtube.com/embed/{key}` [VERIFIED: api-contracts.md]

### Frontend: Star Rating Widget

10 clickable stars. Using `lucide-vue-next` `Star` icon for filled and `StarOff` or empty Star for unfilled. Clicking a selected star resets to null (unrated).

```typescript
// In component script
function handleStarClick(star: number) {
  const newRating = props.modelValue === star ? null : star  // toggle off if already selected
  emit('update:modelValue', newRating)
}
```

[VERIFIED: lucide-vue-next installed per UI-SPEC.md]

### Frontend: Notes Debounce

Manual debounce (VueUse NOT installed — per Phase 5 research pattern). Use `setTimeout` same as `useSearch.ts`:

```typescript
let notesDebounce: ReturnType<typeof setTimeout> | null = null
watch(localNotes, (val) => {
  if (notesDebounce) clearTimeout(notesDebounce)
  notesDebounce = setTimeout(() => {
    updatePersonal({ personalNotes: val })
  }, 1000)
})
```

[VERIFIED: useSearch.ts — manual debounce with setTimeout, no VueUse]

### Frontend: Delete Confirmation Modal

Use shadcn-vue `AlertDialog` (or `Dialog`) component. Per CONTEXT.md code context: "Use for modal dialog (Dialog / AlertDialog component from shadcn-vue for the delete confirmation)." Per UI-SPEC, these are available via radix-vue. [VERIFIED: UI-SPEC.md component library = radix-vue 1.9.x]

```html
<AlertDialog :open="deleteModalOpen">
  <AlertDialogContent class="rounded-none">
    <AlertDialogTitle>Remove from archive?</AlertDialogTitle>
    <AlertDialogDescription>This cannot be undone.</AlertDialogDescription>
    <div class="flex gap-2 justify-end mt-4">
      <button @click="deleteModalOpen = false" class="...">Cancel</button>
      <button @click="confirmDelete" class="bg-primary text-primary-foreground ...">Confirm</button>
    </div>
  </AlertDialogContent>
</AlertDialog>
```

### Frontend: Activating MovieCard and MovieListItem Links

`MovieCard.vue` currently renders cards without links. Phase 6 wraps the card's poster image (or the entire card) with a `<NuxtLink>`:

```html
<NuxtLink :to="`/movies/${movie.id}`" class="block overflow-hidden">
  <!-- existing card content -->
</NuxtLink>
```

[VERIFIED: MovieCard.vue — currently no NuxtLink, just div wrappers]
[VERIFIED: MovieListItem.vue — same pattern, no NuxtLink]

### Frontend: Clickable Attributes (D-12)

Actor clicks navigate to `/search?actors=Name`. Pattern exactly matches existing director/genre navigation in `MovieCard.vue`:

```typescript
function navigateToActor(actor: string) {
  router.push({ path: '/search', query: { actors: actor } })
}
```

URL param name: `actors` (matches Phase 5 `FilterCriteria.actors` field and `useSearch.ts` `actors` computed). [VERIFIED: useSearch.ts line 92 — `const actors = computed(() => paramAsString(route.query.actors))`]

### Backend: Ownership Pattern (Critical)

Per CLAUDE.md and SearchController.java — `auth.getName()` returns email, NOT UUID:

```java
private UUID resolveUserId(Authentication auth) {
    String email = auth.getName();
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
            .getId();
}
```

The `MovieDetailController` MUST use this exact pattern. [VERIFIED: SearchController.java resolveUserId(), CLAUDE.md §JWT Authentication]

### Backend: OpenSearch Update API

For `PATCH /movies/{id}/personal`, use the OS `_update` endpoint (partial document update), not the full index/replace:

```java
// Partial document update — only sends changed fields to OS
Map<String, Object> partialDoc = new HashMap<>();
if (request.getWatched() != null) partialDoc.put("watched", request.getWatched());
if (request.getPersonalRating() != null) partialDoc.put("personal_rating", request.getPersonalRating());
if (request.getPersonalNotes() != null) partialDoc.put("personal_notes", request.getPersonalNotes());

client.update(UpdateRequest.of(u -> u
    .index(indexName)
    .id(movieId.toString())
    .doc(partialDoc)), Map.class);
```

`UpdateRequest` is in `org.opensearch.client.opensearch.core.UpdateRequest`. [ASSUMED — opensearch-java 2.19.0 API signature; consistent with IndexRequest usage pattern in IndexingService but not directly verified in codebase]

**Alternative — safer fallback:** Re-index the full document via `IndexRequest` (same as `IndexingService.index()`). This avoids any UpdateRequest API questions at the cost of re-sending all fields. Given that `DocumentBuilder.build(movie)` already does the full build, this is simpler: save to Postgres → load Movie entity → `IndexingService.index(movie)`. This pattern is already tested and proven. Recommend this approach.

### Backend: V7 Migration

```sql
-- V7__add_personal_fields_to_movies.sql
ALTER TABLE movies ADD COLUMN watched BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE movies ADD COLUMN personal_rating SMALLINT;
ALTER TABLE movies ADD COLUMN personal_notes TEXT;
```

**Entity update:** Add three new fields to `Movie.java`:

```java
@Column(name = "watched", nullable = false)
private Boolean watched = false;

@Column(name = "personal_rating")
private Short personalRating;

@Column(name = "personal_notes", columnDefinition = "text")
private String personalNotes;
```

[VERIFIED: V6__create_movies.sql — migration syntax pattern; Movie.java — column annotation patterns]

### Backend: DocumentBuilder update

After V7, `DocumentBuilder.build(movie)` must read the new entity fields instead of hardcoding null:

```java
// Personal fields (Phase 6: now real values)
doc.put("watched", movie.getWatched());
doc.put("personal_rating", movie.getPersonalRating() != null
    ? movie.getPersonalRating().doubleValue() : null);  // OS field is float
doc.put("personal_notes", movie.getPersonalNotes());
```

[VERIFIED: DocumentBuilder.java lines 235-238 — currently always null, Phase 6 makes them real]

### Recommended Project Structure (new files)

```
backend/src/main/java/de/moviearchive/movie/
├── MovieDetailController.java     # GET /movies/{id}, PATCH /movies/{id}/personal, DELETE /movies/{id}
├── MovieDetailService.java        # business logic for detail, personal update, delete
└── dto/
    ├── MovieDetailResponse.java   # full 40+ field response record
    ├── UpdatePersonalRequest.java # { watched?, personalRating?, personalNotes? }
    └── CastMember.java           # { name, character, order, profilePath }
    └── CrewMember.java           # { name, job, department, profilePath }

backend/src/main/resources/db/migration/
└── V7__add_personal_fields_to_movies.sql

backend/src/test/java/de/moviearchive/movie/
└── MovieDetailControllerTest.java  # extends AbstractOpenSearchTest

frontend/pages/movies/
└── [id].vue                        # detail page

frontend/composables/
└── useMovieDetail.ts               # fetch, updatePersonal, deleteMovie

frontend/components/
├── StarRating.vue                  # 10-star interactive widget
├── TrailerEmbed.vue               # lazy YouTube thumbnail + iframe swap
├── DeleteMovieModal.vue           # shadcn AlertDialog with confirmation
└── CastCrewCredits.vue            # full cast/crew credits at page bottom

frontend/test/mocks/handlers/
└── movieDetail.ts                  # MSW handlers: GET, PATCH, DELETE /api/movies/:id

frontend/test/unit/
├── composables/useMovieDetail.spec.ts
└── pages/movies-id.spec.ts
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Lazy YouTube embed | Custom video player | HTML thumbnail + iframe swap on click | Simpler, no library needed, avoids YouTube tracking on load |
| Debounce for notes | VueUse | Manual setTimeout (per existing pattern) | VueUse not installed; 1-line setTimeout is sufficient |
| Auth modal / Dialog | Custom modal | radix-vue Dialog (via shadcn-vue) | Already installed, handles focus trapping and a11y |
| OS partial update | Custom document merge | OpenSearch `_update` API or full re-index via IndexingService | Re-indexing via IndexingService.index() is already tested and proven |
| JSON parsing for detail DTO | New parser | Reuse DocumentBuilder field extraction patterns | All JSON paths already proven in DocumentBuilder.build() |
| Actor parameter routing | Custom nav system | router.push({ query: { actors } }) same as director | useSearch.ts already handles `actors` query param |

---

## Common Pitfalls

### Pitfall 1: auth.getName() Returns Email Not UUID
**What goes wrong:** Calling `UUID.fromString(auth.getName())` throws `IllegalArgumentException` at runtime.
**Why it happens:** `UserDetailsServiceImpl` sets the username to email — JWT subject is email.
**How to avoid:** Use `resolveUserId(auth)` pattern from `SearchController.java` — email → `userRepository.findByEmail()` → `.getId()`.
**Warning signs:** `IllegalArgumentException: Invalid UUID string:` in logs.
[VERIFIED: SearchController.java resolveUserId() + CLAUDE.md §JWT Authentication]

### Pitfall 2: OS Document Not Found on PATCH
**What goes wrong:** `PATCH /movies/{id}/personal` calls OS `_update` but the movie hasn't been indexed yet (indexed_at is null). OS returns 404.
**Why it happens:** Movies can be saved but not yet indexed (enrichment still in progress, or enrichment failed).
**How to avoid:** In `MovieDetailService.updatePersonal()`: catch OS 404 and silently skip — Postgres update still succeeds. The movie will be indexed correctly by the next reindex with current personal field values.

### Pitfall 3: OpenSearch Update vs Replace
**What goes wrong:** Using `IndexRequest` (replace) for personal field update overwrites the entire document.
**Why it happens:** Both operations use the same document ID, but `IndexRequest` replaces; `UpdateRequest` merges.
**How to avoid:** If using `UpdateRequest`, only pass the changed fields in `.doc()`. Alternatively, use the safer full-reindex pattern: load Movie entity from Postgres → call `indexingService.index(movie)` (this builds the full document including new personal fields). This is the recommended approach because it reuses proven code.

### Pitfall 4: Missing `<NuxtLink>` Wrapper in MovieCard
**What goes wrong:** Cards appear clickable but navigation doesn't work, or the detail link conflicts with the genre/director click buttons inside the card.
**Why it happens:** A `<NuxtLink>` wrapping the entire card will intercept all inner clicks including genre chips.
**How to avoid:** Wrap only the poster image (not the entire card div) with `<NuxtLink>`. Keep genre/director chips as separate `<button>` elements outside the link. Or use `event.stopPropagation()` on inner buttons.

### Pitfall 5: OMDB Fields Rendered When Null
**What goes wrong:** UI shows empty string or "null" text for OMDB fields when no OMDB data was fetched.
**Why it happens:** Template renders `{{ movie.imdbRating }}` without null guard.
**How to avoid:** Use `v-if="movie.imdbRating !== null"` for every OMDB-sourced field. D-10 is explicit: individual field hiding, no placeholder sections.

### Pitfall 6: Star Rating Deselect Not Handled
**What goes wrong:** User clicks already-selected star but rating doesn't clear.
**Why it happens:** Logic only sets new value, doesn't check if current value equals clicked star.
**How to avoid:** `const newRating = props.modelValue === star ? null : star` — deselect if same star clicked.

### Pitfall 7: backdrop_path vs poster_path Image Sizes
**What goes wrong:** Backdrop images look pixelated or poster images are too small.
**Why it happens:** TMDB provides multiple image sizes; using the wrong `w` parameter.
**How to avoid:** Backdrop: `https://image.tmdb.org/t/p/w1280{backdrop_path}` or `original`. Poster (hero overlay): `https://image.tmdb.org/t/p/w342{poster_path}`. [VERIFIED: api-contracts.md — TMDB poster/backdrop URL construction pattern documented]

### Pitfall 8: DeleteIndexRequest for Non-Existent OS Document
**What goes wrong:** `DELETE /movies/{id}` throws when the OS document doesn't exist (e.g., movie was never successfully indexed).
**Why it happens:** `client.delete()` returns 404 response which the OS client may convert to exception.
**How to avoid:** Catch `OpenSearchException` with type `document_missing_exception` or check response status — the delete is a no-op if document doesn't exist. Postgres delete still proceeds.

---

## Code Examples

### Backend: MovieDetailController structure
```java
// Source: SearchController.java pattern (VERIFIED)
@RestController
@RequestMapping("/movies")
@Slf4j
public class MovieDetailController {

    private final MovieDetailService movieDetailService;
    private final UserRepository userRepository;

    @GetMapping("/{id}")
    public ResponseEntity<MovieDetailResponse> getDetail(
            @PathVariable UUID id, Authentication auth) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(movieDetailService.getDetail(userId, id));
    }

    @PatchMapping("/{id}/personal")
    public ResponseEntity<Void> updatePersonal(
            @PathVariable UUID id,
            @RequestBody UpdatePersonalRequest request,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        movieDetailService.updatePersonal(userId, id, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMovie(
            @PathVariable UUID id, Authentication auth) {
        UUID userId = resolveUserId(auth);
        movieDetailService.deleteMovie(userId, id);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveUserId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"))
                .getId();
    }
}
```

### Backend: Full reindex after personal update (safer pattern)
```java
// Source: IndexingService.index() pattern (VERIFIED from IndexingService.java)
// In MovieDetailService.updatePersonal():
public void updatePersonal(UUID userId, UUID movieId, UpdatePersonalRequest request) {
    Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
            .orElseThrow(() -> new AccessDeniedException("Movie not found or access denied"));

    // Update Postgres
    if (request.getWatched() != null) movie.setWatched(request.getWatched());
    if (request.getPersonalRating() != null) movie.setPersonalRating(request.getPersonalRating());
    if (request.getPersonalNotes() != null) movie.setPersonalNotes(request.getPersonalNotes());
    movieRepository.save(movie);

    // Sync to OpenSearch — re-index full document (proven pattern)
    if (movie.getIndexedAt() != null) {
        try {
            indexingService.index(movie);  // full document re-index
        } catch (IOException e) {
            log.warn("OS sync failed for personal update movieId={}: {}", movieId, e.getMessage());
            // silent fail — Postgres is source of truth
        }
    }
}
```

### Frontend: useMovieDetail.ts skeleton
```typescript
// Source: useSearch.ts pattern (VERIFIED)
export interface MovieDetail {
  id: string
  tmdbId: number
  title: string
  originalTitle: string | null
  tagline: string | null
  overview: string | null
  releaseDate: string | null
  year: number | null
  runtime: number | null
  posterPath: string | null
  backdropPath: string | null
  voteAverage: number | null
  voteCount: number | null
  trailerKey: string | null
  genreList: string[]
  directorList: string[]
  writerList: string[]
  mainCast: string | null
  fullCast: CastMember[]
  fullCrew: CrewMember[]
  countryList: string[]
  languageList: string[]
  imdbRating: number | null
  imdbVotes: number | null
  contentRating: string | null
  boxOffice: number | null
  ratingList: Rating[] | null
  imdbLink: string | null
  wikipediaPlot: string | null
  wikipediaCritics: string | null
  wikipediaSummary: string | null
  wikipediaUrl: string | null
  watched: boolean
  personalRating: number | null
  personalNotes: string | null
}

export function useMovieDetail(movieId: string) {
  const accessTokenCookie = useCookie<string | null>('access_token')
  function authHeaders() {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const movie = ref<MovieDetail | null>(null)
  const isLoading = ref(true)
  const error = ref<string | null>(null)

  async function fetchDetail() {
    isLoading.value = true
    try {
      const data = await $fetch<MovieDetail>(`/api/movies/${movieId}`, {
        credentials: 'include',
        headers: authHeaders(),
      })
      movie.value = data
    } catch (e) {
      error.value = 'Failed to load film.'
    } finally {
      isLoading.value = false
    }
  }

  async function updatePersonal(fields: Partial<{ watched: boolean; personalRating: number | null; personalNotes: string }>) {
    await $fetch(`/api/movies/${movieId}/personal`, {
      method: 'PATCH',
      body: fields,
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  const router = useRouter()
  async function deleteMovie() {
    await $fetch(`/api/movies/${movieId}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: authHeaders(),
    })
    router.push('/search')
  }

  onMounted(() => fetchDetail())

  return { movie, isLoading, error, updatePersonal, deleteMovie }
}
```

### Frontend: MSW handlers for movieDetail
```typescript
// Source: existing MSW handler pattern (VERIFIED: test/mocks/handlers/search.ts)
// New file: frontend/test/mocks/handlers/movieDetail.ts
import { http, HttpResponse } from 'msw'

export const movieDetailHandlers = [
  http.get('/api/movies/:id', ({ params }) => {
    return HttpResponse.json({ id: params.id, title: 'Inception', ... })
  }),
  http.patch('/api/movies/:id/personal', () => HttpResponse.json(null, { status: 204 })),
  http.delete('/api/movies/:id', () => HttpResponse.json(null, { status: 204 })),
]
```

---

## State of the Art

| Old Approach | Current Approach | Notes |
|--------------|------------------|-------|
| VueUse `useDebounce` | Manual `setTimeout` | VueUse not installed in this project; manual debounce is the established pattern |
| RestClientTransport (deprecated) | ApacheHttpClient5Transport | Already in place — no change needed |
| Spring Data OpenSearch | Direct opensearch-java client | Already in place — no change needed |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `UpdateRequest.of(u -> u.index().id().doc(map), Map.class)` is the correct opensearch-java 2.19.0 API for partial document update | Architecture Patterns: OpenSearch Update API | May need to use different builder signature; the alternative (full re-index via IndexingService.index()) eliminates this risk entirely — recommend full re-index approach |
| A2 | OS `client.delete()` for a non-existent document throws `OpenSearchException` with type `document_missing_exception` | Common Pitfalls #8 | Exception type may differ; wrap in generic IOException catch as fallback |

**Recommendation:** Use the full re-index approach for personal field OS sync (load Movie from Postgres → call `indexingService.index(movie)`) to avoid any UpdateRequest API uncertainty. This reuses 100% proven code.

---

## Open Questions

1. **MovieDetailController vs extending MovieController**
   - What we know: `MovieController` already exists with `@RequestMapping("/movies")`. Phase 6 adds `GET /movies/{id}`, `PATCH /movies/{id}/personal`, `DELETE /movies/{id}`.
   - What's unclear: Should these go in the existing `MovieController` or a new `MovieDetailController`?
   - Recommendation: Create a separate `MovieDetailController` for separation of concerns. Both map to `/movies` — Spring handles this correctly when there is no method-level conflict. The existing `GET /movies/{id}/status` remains in `MovieController`.

2. **Partial PATCH semantics — null vs absent**
   - What we know: `PATCH /movies/{id}/personal` must support partial updates (e.g., only `watched`, without touching `personalRating`).
   - What's unclear: How to distinguish "set to null" (clear the rating) vs "not provided" (don't change).
   - Recommendation: Use `@JsonInclude(NON_NULL)` on the request DTO with `Boolean/Short/String` wrapper types (not primitives). Frontend only sends the field that changed. Service checks `if (request.getWatched() != null)` before updating. For clearing personal_rating (deselect star), frontend sends `{ personalRating: null }` — service sets to null. This works because `Short` wrapper can distinguish `null` from any value.

---

## Environment Availability

Step 2.6: SKIPPED — No new external dependencies. All required tools (Java, Gradle, Node, PostgreSQL, OpenSearch) are already verified from previous phases. The phase is purely code and config changes against existing infrastructure.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 + Testcontainers (Postgres + OpenSearch) + MockMvc |
| Backend config | `AbstractOpenSearchTest extends AbstractIntegrationTest` |
| Backend quick run | `./gradlew test --tests "de.moviearchive.movie.MovieDetailControllerTest" -x javadoc` |
| Backend full suite | `./gradlew test -x javadoc` |
| Frontend framework | Vitest + @nuxt/test-utils + MSW |
| Frontend config | `frontend/vitest.config.ts` (environment: 'nuxt') |
| Frontend quick run | `pnpm --prefix frontend test run -- --reporter=verbose test/unit/composables/useMovieDetail.spec.ts` |
| Frontend full suite | `pnpm --prefix frontend test run` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DETAIL-01 | GET /movies/{id} returns all TMDB+OMDB fields; ownership enforced; 404 on wrong user | integration | `./gradlew test --tests "de.moviearchive.movie.MovieDetailControllerTest"` | ❌ Wave 0 |
| DETAIL-01 | OMDB fields null when no OMDB data | integration | same | ❌ Wave 0 |
| DETAIL-02 | Wikipedia fields included in response when present | integration | same | ❌ Wave 0 |
| DETAIL-03 | PATCH /movies/{id}/personal updates Postgres + syncs to OS | integration | `./gradlew test --tests "de.moviearchive.movie.MovieDetailControllerTest"` | ❌ Wave 0 |
| DETAIL-03 | useMovieDetail.updatePersonal() calls PATCH with correct body | unit | `pnpm --prefix frontend test run -- test/unit/composables/useMovieDetail.spec.ts` | ❌ Wave 0 |
| DETAIL-03 | Star rating deselect sets personalRating to null | unit | same | ❌ Wave 0 |
| DETAIL-03 | Notes auto-save fires ~1s after typing stops | unit | same | ❌ Wave 0 |
| DETAIL-04 | Trailer section shown when trailerKey present; hidden when null | unit | `pnpm --prefix frontend test run -- test/unit/components/TrailerEmbed.spec.ts` | ❌ Wave 0 |
| DETAIL-05 | Actor chip navigates to /search?actors=X | unit | `pnpm --prefix frontend test run -- test/unit/pages/movies-id.spec.ts` | ❌ Wave 0 |
| DELETE | DELETE /movies/{id} removes from OS then Postgres; 404 for wrong user | integration | `./gradlew test --tests "de.moviearchive.movie.MovieDetailControllerTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** Backend: `./gradlew test --tests "de.moviearchive.movie.MovieDetailControllerTest" -x javadoc`; Frontend: `pnpm --prefix frontend test run -- test/unit/composables/useMovieDetail.spec.ts`
- **Per wave merge:** Full suite: `./gradlew test -x javadoc` + `pnpm --prefix frontend test run`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java` — covers DETAIL-01 through DELETE
- [ ] `frontend/test/mocks/handlers/movieDetail.ts` — MSW stubs for GET/PATCH/DELETE /api/movies/:id
- [ ] `frontend/test/unit/composables/useMovieDetail.spec.ts` — composable tests
- [ ] `frontend/test/unit/pages/movies-id.spec.ts` — page-level tests (actor nav, trailer show/hide)
- [ ] `frontend/test/unit/components/TrailerEmbed.spec.ts` — lazy embed toggle behavior

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | JWT filter already in place (JwtAuthFilter) |
| V3 Session Management | no | Handled by existing auth infrastructure |
| V4 Access Control | yes | movieRepository.findByIdAndUserId() — ownership enforced at service layer |
| V5 Input Validation | yes | `@PathVariable UUID id` — Spring validates UUID format. PATCH body: `@RequestBody` with Jackson. personalNotes is free-text — no XSS risk server-side (plaintext stored, no HTML rendering server-side) |
| V6 Cryptography | no | No new secrets in this phase |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| IDOR — accessing another user's movie by UUID | Tampering / Info Disclosure | `findByIdAndUserId(movieId, userId)` — both ID columns required in query |
| Mass assignment on PATCH request | Tampering | `UpdatePersonalRequest` is a dedicated DTO with only `watched`, `personalRating`, `personalNotes` — no user_id or other sensitive fields |
| XSS in personalNotes rendered in frontend | Tampering | Nuxt/Vue auto-escapes text interpolation (`{{ }}`); no `v-html` on user data |
| Stored plaintext in personalNotes visible to server admin | Info Disclosure | Acceptable for personal single-user app (not a multi-tenant SaaS) — personal_notes is not encrypted |

---

## Sources

### Primary (HIGH confidence)
- `backend/src/main/java/de/moviearchive/search/SearchController.java` — resolveUserId pattern (VERIFIED in codebase)
- `backend/src/main/java/de/moviearchive/indexing/IndexingService.java` — OpenSearch client usage, index method (VERIFIED)
- `backend/src/main/java/de/moviearchive/indexing/DocumentBuilder.java` — all 40+ field paths from rawTmdbJson/rawOmdbJson (VERIFIED)
- `backend/src/main/java/de/moviearchive/movie/Movie.java` — entity fields, annotations (VERIFIED)
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — findByIdAndUserId pattern (VERIFIED)
- `backend/src/main/resources/db/migration/V6__create_movies.sql` — migration syntax pattern (VERIFIED)
- `frontend/composables/useSearch.ts` — composable pattern, authHeaders, $fetch, manual debounce (VERIFIED)
- `frontend/components/MovieCard.vue` — current card structure without NuxtLink (VERIFIED)
- `frontend/components/MovieListItem.vue` — same (VERIFIED)
- `frontend/middleware/auth.global.ts` — global auth protection pattern (VERIFIED)
- `.claude/data-model.md` — full OS field mapping (VERIFIED)
- `.claude/api-contracts.md` — TMDB image URLs, YouTube embed/thumbnail URLs (VERIFIED)
- `.planning/UI-SPEC.md` — design tokens, component library (VERIFIED)
- `.planning/phases/06-movie-detail-personal-fields/06-CONTEXT.md` — all locked decisions (VERIFIED)

### Secondary (MEDIUM confidence)
- CLAUDE.md §JWT Authentication — auth.getName() = email pattern (documented by project team, VERIFIED against code)
- CLAUDE.md §OpenSearch Java Client 2.19.0 — client bean configuration patterns

### Tertiary (LOW confidence / ASSUMED)
- UpdateRequest API signature for opensearch-java 2.19.0 — not directly used in existing codebase; full re-index approach eliminates this dependency

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all dependencies confirmed in build.gradle.kts and existing source
- Architecture: HIGH — patterns directly verified in existing codebase (SearchController, IndexingService, useSearch.ts)
- Pitfalls: HIGH — most are directly observed in existing code and CLAUDE.md documented lessons
- OS partial update: MEDIUM — UpdateRequest API assumed; recommend full re-index pattern to eliminate

**Research date:** 2026-05-18
**Valid until:** 2026-07-18 (stable libraries; opensearch-java and Nuxt 3 are not fast-moving in patch details)
