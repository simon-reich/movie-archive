# Phase 6: Movie Detail & Personal Fields - Context

**Gathered:** 2026-05-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can view complete film metadata (TMDB + OMDB + Wikipedia) and record their personal relationship to each film (watched status, personal rating, free-text notes). The detail page is linked from search results. Phase 6 also delivers film deletion from the archive.

**Scope anchor:** DETAIL-01, DETAIL-02, DETAIL-03, DETAIL-04, DETAIL-05
**Folded-in capability:** Delete film from archive (confirmed by user during discussion — new backend endpoint + frontend confirmation modal)

**Explicit out-of-scope for Phase 6:**
- Mobile responsive polish — Phase 7
- E2E Playwright tests for the full happy path — Phase 7

</domain>

<decisions>
## Implementation Decisions

### Page Route

- **D-01:** Detail page route: `/movies/{id}` (UUID). Linked from search result cards (MovieCard / MovieListItem) which currently render without a link — Phase 6 activates those links.

### Hero Section

- **D-02:** Full-width backdrop image as the hero background. Poster image overlaid on the left. Title, year, tagline displayed on the right side of the hero. Cinematic layout.

### Page Structure (below hero)

- **D-03:** Two-column layout below the hero:
  - **Left/main column:** Primary facts → Wikipedia content (see D-04/D-05 for order)
  - **Right sidebar:** Personal fields (watched toggle, star rating, notes) + trailer embed
  - Column ratio: Claude's discretion — must comfortably fit a 16:9 trailer embed and personal field controls in the sidebar.
- **D-04:** Left column content order:
  1. Primary facts: year, runtime, genres, director, writer, main cast, language, country, ratings (IMDB, TMDB vote_average, OMDB ratings if present)
  2. Overview / synopsis (TMDB tagline + overview)
  3. Wikipedia plot and critics sections (when available)
- **D-05:** Full cast & crew section at the very bottom of the page, spanning full width. Credits-style layout — 2 or 3 columns side by side. Claude decides column count and grouping (by department or job role).

### Personal Fields

- **D-06:** Rating widget: 10-star rating (1–10, integer). Clickable stars.
- **D-07:** Save trigger: **auto-save on each change**. Watched toggles immediately. Star rating saves on click. Notes auto-save after a debounce (~1s after typing stops). No explicit save button. Backend receives a PATCH per field change.
- **D-08:** Backend endpoint: `PATCH /movies/{id}/personal` — accepts `{ watched, personalRating, personalNotes }` (all fields optional, partial updates allowed). After saving to Postgres, must also upsert the OS document to keep personal fields searchable (watched filter, personal rating sort, personal notes full-text). This is the deferred integration from Phase 5.
- **D-09:** Postgres migration (V7): add columns `watched BOOLEAN DEFAULT FALSE NOT NULL`, `personal_rating SMALLINT`, `personal_notes TEXT` to the `movies` table.

### OMDB Absent Behavior

- **D-10:** When `raw_omdb_json` is null: hide OMDB-sourced fields individually and silently. No placeholder section, no "data not available" message. IMDB rating, content rating, box office, RT/Metacritic scores simply do not appear. Same rule applies to any nullable field regardless of source — only show fields with data.

### Trailer Embed

- **D-11:** Show YouTube thumbnail image (from `trailer_key`) with a play button overlay. Clicking the thumbnail loads the YouTube iframe. No YouTube request until user clicks — better page performance and no YouTube tracking on initial load. When no `trailer_key` is available, the trailer section is omitted entirely from the sidebar.

### Clickable Attributes (DETAIL-05)

- **D-12:** Clicking an actor, director, or genre on the detail page navigates to `/search` with the URL param pre-applied — **same format as Phase 5 D-14** (e.g., `/search?director=Christopher+Nolan`, `/search?genre=Thriller`, `/search?actor=Cillian+Murphy`). Reuse the exact param naming convention established in Phase 5.

### Delete Film

- **D-13:** Delete button placement: Claude's discretion (fit the editorial layout — likely top-right area of the hero or a dedicated action bar). Behavior: clicking opens a confirmation modal ("Remove from archive? This cannot be undone." + Confirm/Cancel). On confirmation: DELETE to backend, which removes from OpenSearch index first, then deletes from Postgres. After deletion: redirect to `/search`.
- **D-14:** Backend endpoint: `DELETE /movies/{id}`. Must verify ownership (same JWT → email → userId pattern as other controllers). Idempotent: if film not found for this user, return 404.

### Claude's Discretion

- Column width ratio for the two-column layout (fit 16:9 trailer + personal fields in sidebar)
- Delete button placement within the page (hero area or sidebar top — whichever fits the editorial aesthetic)
- Full cast & crew column count (2 or 3 columns) and grouping (by department/role)
- Exact star rating component implementation (lucide-vue-next icons or custom SVG)
- Debounce duration for notes auto-save (~1s)
- Poster/backdrop fallback when image is unavailable (solid terracotta background or no-image placeholder)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model
- `.claude/data-model.md` — Complete OpenSearch field mapping. All 40+ fields, types, analyzers. **Read before writing the PATCH endpoint, detail API response, or OS upsert logic.** Key fields for Phase 6: `trailer_key` (keyword, stored), `watched` (boolean), `personal_rating` (float), `personal_notes` (text, custom_english_analyzer), `full_cast` / `full_crew` (nested), `full_cast_names` / `full_crew_names` (keyword + text sub-fields), `poster_path`, `backdrop_path`, `video_list`.
- `backend/src/main/resources/db/migration/V6__create_movies.sql` — Current movies table schema. Phase 6 adds V7 migration for `watched`, `personal_rating`, `personal_notes`.

### Requirements
- `.planning/REQUIREMENTS.md` §Movie Detail — DETAIL-01 through DETAIL-05 (authoritative requirement text)

### Tech Stack & Patterns
- `CLAUDE.md` §JWT Authentication — `auth.getName()` returns email (not UUID). All controllers must resolve userId via `userRepository.findByEmail(auth.getName()).getId()`. Same pattern as Phase 4 ReindexController and Phase 5 SearchController.
- `CLAUDE.md` §OpenSearch Java Client 2.19.0 — Client bean, query builder patterns. The PATCH endpoint must upsert the OS document after Postgres write (partial update using OS update API or re-index full document).

### Prior Phase Context
- `.planning/phases/05-search/05-CONTEXT.md` — D-14 (clickable attribute URL param format — MUST reuse in DETAIL-05), D-10 (filter field names — actor param naming), Deferred section (OS upsert when personal fields saved — **this is now required in Phase 6**).
- `.planning/phases/04-opensearch-indexing/04-CONTEXT.md` — D-01 (IndexingService.index() contract), D-05/D-06 (personal fields in mapping but null until Phase 6). The upsert in Phase 6 writes these fields for the first time.

### API Contracts
- `.claude/api-contracts.md` — TMDB poster/backdrop URL construction (`https://image.tmdb.org/t/p/w500{poster_path}`), trailer_key YouTube embed URL (`https://www.youtube.com/embed/{trailer_key}`), YouTube thumbnail URL (`https://img.youtube.com/vi/{trailer_key}/hqdefault.jpg`).

### Design System
- `.planning/UI-SPEC.md` — Global design contract: warm off-white + deep terracotta, NO rounded corners, editorial/avantgardistic aesthetic, shadcn-vue components, lucide-vue-next icons. **All new pages must follow this spec.**

### Existing Frontend Code
- `frontend/components/MovieCard.vue` — Current search grid card. Phase 6 adds `<NuxtLink :to="'/movies/' + movie.id">` to activate the detail page link.
- `frontend/components/MovieListItem.vue` — Same: activate detail page link.
- `frontend/composables/useSearch.ts` — Reference for composable pattern. Phase 6 needs a `useMovieDetail.ts` composable.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/components/MovieCard.vue` / `MovieListItem.vue` — Already render search results. Phase 6 wraps them with `<NuxtLink>` to activate detail navigation.
- `frontend/composables/useAuth.ts` — Auth state access. Detail page requires authentication (same middleware as `/search`).
- `backend/de/moviearchive/search/SearchController.java` — Reference for JWT ownership pattern. `MovieDetailController` follows the same structure.
- `backend/de/moviearchive/indexing/IndexingService.java` — OpenSearch client usage. The PATCH personal fields endpoint reuses the same client to upsert.
- `frontend/components/ui/` — Existing shadcn-vue components (Button, Input, etc.). Use for modal dialog (Dialog / AlertDialog component from shadcn-vue for the delete confirmation).

### Established Patterns
- `@Service` + `@RestController` split — Phase 6 adds `MovieDetailService` + `MovieDetailController` (or extends `MovieController`).
- Testcontainers real DB + OpenSearch (`AbstractOpenSearchTest`, `AbstractIntegrationTest`) — integration tests use real containers.
- MSW handlers for frontend tests — `/movies/{id}` GET, `PATCH /movies/{id}/personal`, `DELETE /movies/{id}` all need MSW stubs.
- Flyway migration naming: `V7__add_personal_fields_to_movies.sql` (next after V6).

### Integration Points
- `MovieCard.vue` / `MovieListItem.vue` — Activate `<NuxtLink>` to `/movies/{id}`. These currently render cards without links.
- `AppNav.vue` — No changes needed (dashboard and search links already exist).
- `frontend/pages/search.vue` — After deletion redirects back to `/search`. No changes to search page needed.
- `backend/de/moviearchive/movie/MovieController.java` — `GET /movies/{id}/status` already exists. Detail endpoint is a new `GET /movies/{id}` returning full metadata + personal fields.

</code_context>

<specifics>
## Specific Ideas

- **Two-column editorial layout:** Left column (primary facts + Wikipedia) sits next to right sidebar (personal fields + trailer). This is modeled loosely on editorial film magazine layouts — not a traditional app card layout. Must use NO rounded corners per UI-SPEC.
- **Full cast & crew credits:** At the very bottom of the page, full width, 2–3 columns side by side "like film credits." Claude picks the grouping (cast first, then crew by department, or combined).
- **Backdrop + poster overlay hero:** Backdrop as full-width background image, poster overlaid on the lower-left. Cinematic effect common in film apps (similar to Letterboxd header style). Overlay may use a dark gradient on the backdrop to ensure title/tagline text is readable.
- **Star rating UX note:** 1–10 integer stars. Clicking a star that's already selected should deselect (rating back to null / unrated). "Unrated" is a valid state.
- **Delete confirmation modal text:** "Remove from archive? This cannot be undone." with Confirm (terracotta primary button) / Cancel.

</specifics>

<deferred>
## Deferred Ideas

- **Average personal rating on dashboard** — Phase 5 deferred this because personal_rating was null. After Phase 6 writes personal_rating, a future iteration (or Phase 7 polish) could add this stat to the dashboard.
- **Watched vs. unwatched count on dashboard** — Same: meaningful only after Phase 6 writes `watched`.
- **Edit TMDB/OMDB data** — Not in scope. Snapshot strategy (frozen at save time, no re-fetch).
- **Mobile polish for detail page** — Phase 7 (QLTY-01). The two-column layout will likely need to stack vertically on mobile.

None — discussion stayed tightly within Phase 6 scope. Delete was folded in (new capability, confirmed by user).

</deferred>

---

*Phase: 06-movie-detail-personal-fields*
*Context gathered: 2026-05-18*
