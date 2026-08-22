# Phase 5: Search - Context

**Gathered:** 2026-05-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can find any film in their archive using free-text search, advanced structured filters, sort options, and clickable attribute navigation. Phase 5 also defines the home page dashboard (`/`) and the primary search page (`/search`).

**Scope anchor:** SRCH-01, SRCH-02, SRCH-03, SRCH-04

**Explicit out-of-scope for Phase 5:**
- Movie detail page — Phase 6
- Personal fields (watched, personal_rating, personal_notes) — Phase 6 writes them; Phase 5 may read `watched` for the "not yet watched" filter (will return nothing until Phase 6)
- Clickable attributes on the movie detail page — DETAIL-05 scope, Phase 6
- CSV export / import — Phase 2 placeholder buttons stay as-is

</domain>

<decisions>
## Implementation Decisions

### Home Page (`/`)

- **D-01:** `/` (index.vue) is a **dashboard** for logged-in users — it is NOT the search page. `/search` is the search page.
- **D-02:** The dashboard shows the following sections:
  - **Archive stats:** total films in archive, top genres (top N), language breakdown, IMDB rating distribution (histogram from 1–10)
  - **Movie of the day:** a single random unwatched film (poster + title + year), date-seeded so it stays the same for the full day. Until Phase 6 writes `watched`, all films are eligible.
  - **Recently added:** poster cards of the most recently saved films (linking to detail in Phase 6 — for now just a poster).
- **D-03:** "Movie of the day" algorithm: select a random film where `watched = false` (or all films until Phase 6), seeded by the ISO date string so it is stable for the day. Claude picks the seeding/randomization mechanism.

### Search Page (`/search`) — Behavior

- **D-04:** Search triggers **live as you type** with a ~300ms debounce. No explicit submit button required (Enter key may still trigger for accessibility).
- **D-05:** On page load (empty query): show **all films sorted by title A–Z** (`match_all` query, sorted on `title.raw`).
- **D-06:** Free-text query searches **all indexed text fields** (title, original_title, tagline, overview, director_list, full_cast_names, full_crew_names, genre_list, keyword_list, wikipedia_summary, wikipedia_plot, wikipedia_critics, personal_notes). Full power of `custom_english_analyzer`.

### Result Display

- **D-07:** Results are **switchable** between two view modes:
  - **Poster grid:** poster image + title + year per cell. Visually dominant, compact.
  - **Metadata-rich list:** poster thumbnail + title + year + director + genres + IMDB rating + runtime per row.
  - View mode preference should be persisted (Pinia store or localStorage — Claude's discretion).
- **D-08:** Default view on page load: Claude decides (poster grid is likely more impactful as the default).

### Advanced Filters

- **D-09:** Filters live in a **collapsible panel above the results** — hidden by default, expanded via a "Filters" button. When expanded, filters appear between the search bar and results.
- **D-10:** Extended filter set (beyond SRCH-02 minimum):
  | Filter | Type | Notes |
  |---|---|---|
  | Genre | multi-select | OR within group |
  | Director | text with autocomplete | AND across groups |
  | Actors / cast | text with autocomplete | AND across groups |
  | Year | range (from / to) | integer inputs |
  | IMDB rating | range (from / to) | float inputs |
  | Content rating | multi-select | OR within group |
  | Runtime (max) | single input | maximum minutes only |
  | Not yet watched | boolean toggle | shows `watched = false` only; empty until Phase 6 |
  | Language | multi-select | OR within group |
  | Production country | multi-select | OR within group |
- **D-11:** Filter combination logic: **OR within same filter group, AND across filter groups.** Example: `Genre=(Action OR Thriller) AND Director=Nolan AND Runtime≤120`.
- **D-12:** Sort options (SRCH-03): Title A–Z, Release year (desc), Personal rating (desc — Phase 6 field, available in UI but empty until then), IMDB rating (desc).
- **D-13:** UI controls per field type — **Claude's discretion** (e.g., tag pills for multi-select, range sliders or numeric inputs for year/rating/runtime, toggle switch for watched).

### Clickable Attribute Navigation (SRCH-04)

- **D-14:** Clicking an actor, director, or genre navigates to `/search` with a **URL query param** pre-applied (e.g., `/search?director=Christopher+Nolan`, `/search?genre=Thriller`). Shareable, bookmarkable, and back-button compatible.
- **D-15:** Clickable attributes appear on **search result cards/list items** in Phase 5. The movie detail page clickable attributes (DETAIL-05) are Phase 6 scope — note this for Phase 6 planner.
- **D-16:** URL query param naming convention — Claude's discretion (should be consistent with the filter field names in D-10).
- **D-17:** Multiple values for the same filter via URL params (e.g., `?genre=Action&genre=Thriller`) follow OR logic (D-11).

### Claude's Discretion

- OpenSearch query structure for free-text (multi_match vs. bool/should with per-field boosts)
- Debounce implementation (watcher with setTimeout, useDebounce composable, or similar)
- How autocomplete suggestions for director/actors are fetched (aggregation query or terms query)
- Dashboard aggregation queries (terms agg for genres/languages/countries, range agg for IMDB histogram)
- Date-seeding algorithm for "movie of the day"
- Pagination strategy for search results (infinite scroll, load-more button, or standard pagination)
- Default view mode (grid vs. list) if no stored preference
- Animation for filter panel expand/collapse
- Exact URL query param naming

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model & Field Mapping
- `.claude/data-model.md` — Complete OpenSearch field mapping: field names, types, analyzers, sub-fields (`.raw` for keyword sorting, `.text` for full-text on keyword fields). **Read this before writing any OpenSearch query or filter.** Note: `rating_list` is `object` type (not `flattened` — Phase 4 deviation), `full_cast` / `full_crew` are `nested`.
- `.claude/api-contracts.md` — External API contracts (not directly needed for search queries, but relevant for poster URL construction: `https://image.tmdb.org/t/p/w500{poster_path}`).

### Requirements
- `.planning/REQUIREMENTS.md` §Search — SRCH-01 through SRCH-04 (authoritative requirement text)

### Tech Stack Constraints
- `CLAUDE.md` §OpenSearch Java Client 2.19.0 — Query builder patterns, `ApacheHttpClient5Transport`. Search queries use the same client bean as indexing.
- `CLAUDE.md` §JWT Authentication — `auth.getName()` returns email (not UUID). The search controller must resolve userId from email using `userRepository.findByEmail(auth.getName()).getId()` to scope queries to `movies-{userId}`. Same pattern as Phase 4 ReindexController.

### Prior Phase Context
- `.planning/phases/04-opensearch-indexing/04-CONTEXT.md` — D-01/D-02 (silent OS failure contract), D-05/D-06 (personal fields in mapping but null until Phase 6). The search planner must handle null personal fields gracefully.
- `.planning/phases/03-save-movie-flow/03-CONTEXT.md` — D-10 (archive/list view deferred to Phase 5), D-13 (status endpoint contract — search page may link to status).

### Design System
- `.planning/UI-SPEC.md` — Global design contract: warm off-white + deep terracotta, NO rounded corners, editorial/avantgardistic aesthetic, shadcn-vue components, lucide-vue-next icons, Tailwind spacing scale. **All new pages must follow this spec.**

### Existing Frontend Code
- `frontend/composables/useMovies.ts` — Existing composable (Phase 3). Search will need a `useSearch.ts` composable (or extend useMovies) for query state, filter state, and result management.
- `frontend/pages/add.vue` — Reference for the poster grid pattern (Phase 3). Phase 5 grid view reuses the same visual pattern; check this before implementing the grid.
- `frontend/composables/useAuth.ts` — Auth state access pattern. Search pages require authentication.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/pages/add.vue` — Poster grid layout (TMDB poster images + title + year cells). The Phase 5 grid view should reuse the same visual pattern; adapt rather than re-implement.
- `frontend/composables/useMovies.ts` — `useMovies` composable manages movie state. A new `useSearch.ts` composable should handle search query state, active filters, sort, and paginated results. Keeps concerns separate.
- `backend/de/moviearchive/indexing/IndexingService.java` — OpenSearch client usage pattern. The new `SearchService` follows the same client injection and query builder style.
- `backend/de/moviearchive/admin/ReindexController.java` — Reference for JWT ownership pattern: `userRepository.findByEmail(auth.getName()).getId()`. The `SearchController` must use the same pattern to scope queries to `movies-{userId}`.

### Established Patterns
- Testcontainers real OpenSearch container (`AbstractOpenSearchTest`) for integration tests — no mocking of the OS client in search integration tests. Use the same base class.
- `@Service` + `@Controller` split for all backend features. Search needs `SearchService` (OpenSearch queries) + `SearchController` (HTTP layer).
- MSW handlers for frontend tests — the search endpoint must have an MSW stub.
- URL query params for navigation state — consistent with how Phase 5 routes clickable filters via `useRoute().query`.

### Integration Points
- `/search` page is protected (auth required). Add to the authenticated route group in Nuxt middleware.
- `/` (index.vue) is currently unimplemented — Phase 5 writes it as the dashboard.
- `AppNav` — needs links to `/` (home/dashboard) and `/search`. Check current nav for the add link and apply the same pattern.
- Phase 6 integration: the search result cards will eventually link to `/movies/{id}` (detail page). Phase 5 can render the card without a link, or with a disabled placeholder link that Phase 6 activates.

</code_context>

<specifics>
## Specific Ideas

- **Switchable view with runtime in list:** The metadata-rich list view must include runtime (minutes) alongside poster thumbnail, title, year, director, genres, IMDB rating. This was explicitly requested.
- **IMDB rating histogram:** Dashboard stats include an IMDB rating breakdown displayed as a distribution (e.g., how many films rated 1–2, 3–4, etc.). Use OpenSearch range aggregation on `imdb_rating`.
- **Language + production country breakdown:** Dashboard stats include these two. Use terms aggregation on `language_list` and `country_list` fields.
- **Movie of the day:** Random unwatched film (date-seeded). Displayed as a poster with title + year. Prominent placement on the dashboard.
- **Extended filter set:** The filter panel goes beyond SRCH-02 to include actors, runtime max, language, and production country. This is in scope — it's the same search feature, more filters.

</specifics>

<deferred>
## Deferred Ideas

- **Clickable attributes on movie detail page** — DETAIL-05 scope. Phase 6 planner must wire up actor/director/genre links on the detail page to `/search?actor=X` etc. The URL param format established in Phase 5 (D-14/D-16) must be reused.
- **OpenSearch document upsert on personal field save** — Phase 6 integration requirement (from Phase 4 D-06). When Phase 6 saves watched/rating/notes, it must also upsert the OS document. This makes the "not yet watched" filter (D-10) and personal rating sort (D-12) functional.
- **Average personal rating on dashboard** — Not added to D-02 because the personal_rating field is null until Phase 6. Phase 6 or a future iteration can add this stat.
- **Watched vs. unwatched count on dashboard** — Same dependency. Not worth showing "0 watched" on the dashboard before Phase 6 writes the field.
- **Infinite scroll / cursor-based pagination** — Not decided. Claude picks the pagination strategy (D-09 discretion). If infinite scroll is chosen and causes complexity, fall back to load-more.

</deferred>

---

*Phase: 05-search*
*Context gathered: 2026-05-17*
