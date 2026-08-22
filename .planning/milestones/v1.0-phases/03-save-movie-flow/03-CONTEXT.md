# Phase 3: Save Movie Flow - Context

**Gathered:** 2026-05-16
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can search TMDB for a film, click a poster to trigger saving, and see real-time status feedback (spinner → success/error) while the async enrichment pipeline runs in the background. The pipeline persists enriched film data to Postgres. OpenSearch indexing is NOT in Phase 3 scope — that is Phase 4.

**Scope anchor:** SAVE-01 (search-only, no TMDB ID input), SAVE-02 (Postgres persist only), SAVE-03, SAVE-04, SAVE-05.

**Explicit out-of-scope for Phase 3:**
- Archive/list view showing saved films — lives on the future search/index page (Phase 5)
- OpenSearch write — Phase 4 handles the full index setup + document write
- Direct TMDB ID input — user chose search-only; TMDB ID fallback dropped for v1

</domain>

<decisions>
## Implementation Decisions

### 202 Async — Confirmed

- **D-01:** The save flow returns **202 Accepted** immediately. Async enrichment runs in the background via `@Async` + `@Retryable`. This was already a locked Project Decision; user confirmed it after understanding that the 202 approach fully supports the spinner-on-poster UX via polling.

### Add Film UX

- **D-02:** A dedicated `/add` page hosts the film search UI. No modal, no inline on index/home.
- **D-03:** TMDB search works as **submit-and-show grid**: user types a title, hits Search (or Enter), results appear as a poster grid below. No autocomplete/typeahead.
- **D-04:** **No direct TMDB ID input** on the add page. User chose search-only. (Deviates from SAVE-01's "or directly by TMDB ID" wording — consciously dropped for v1.)
- **D-05:** Poster grid layout for TMDB search results. Each cell: movie poster image (from TMDB `poster_path`) + title + year. Clicking a poster triggers the save flow.

### Save Interaction (Poster → Status)

- **D-06:** Clicking a poster immediately shows a **spinner overlay on that poster** (inline, not a separate loading screen). The poster stays in the search result grid during processing.
- **D-07:** Frontend polls `GET /movies/{id}/status` every 2-3 seconds until the status reaches a terminal state (SUCCESS or ERROR). The `id` is returned by `POST /movies/save` (202 response body includes the new movie UUID).
- **D-08:** On **SUCCESS**: brief success signal on the poster (e.g., green checkmark), then the poster is removed from the search result grid (film is now in the archive).
- **D-09:** On **ERROR**: spinner turns into an **error state on the poster** (red X or error icon) + brief tooltip/message ("Could not save — check your TMDB key"). Poster remains in the grid so the user can retry. No silent failures.

### Archive / List View

- **D-10:** Phase 3 does **not** build an archive/list page. Saved films are visible starting Phase 5 (Search). The `index.vue` or home page is not the responsibility of Phase 3.

### Backend Pipeline Boundary

- **D-11:** Phase 3's async pipeline runs: **TMDB fetch → OMDB (optional) → Wikipedia (6-step fallback) → Postgres persist**. Pipeline ends at Postgres. No OpenSearch write in Phase 3.
- **D-12:** The `movies` table needs an **enrichment status field** to support the polling endpoint. Planner should add a `status` column (e.g., PENDING / SUCCESS / ERROR enum) to the Flyway migration for the movies table. The `GET /movies/{id}/status` endpoint reads this field.
- **D-13:** `GET /movies/{id}/status` is an authenticated endpoint. Returns at minimum `{ "id": "...", "status": "PENDING" | "SUCCESS" | "ERROR", "title": "..." }`.

### Error Handling

- **D-14:** Carry forward from Phase 1/2: `{"message": "..."}` flat JSON for error responses. The status polling response uses a separate structured response (D-13).
- **D-15:** OMDB enrichment failure is always silent (SAVE-03) — film saves without OMDB data. Wikipedia failure is always silent (SAVE-04) — film saves without wiki data. Only TMDB failure (the mandatory step) transitions to ERROR status.

### Claude's Discretion

- Exact Flyway migration version for the `movies` table (V6 or next available after V5).
- Polling interval (2-3 seconds recommended; client-side backoff optional).
- Exact wording of error message on the poster ("Could not save — check your TMDB key" or similar).
- Whether the TMDB search backend endpoint is `/movies/search?q=` or another naming convention.
- Whether the `/add` page nav link is a "+" icon or text ("Add Film") in AppNav.
- Visual design of the spinner overlay (opacity, animation style).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model
- `.claude/data-model.md` — `movies` table schema (all fields), OpenSearch field mapping (for reference; Phase 3 writes to Postgres only). Note: `status` column must be added by Phase 3 Flyway migration.

### API Contracts
- `.claude/api-contracts.md` — TMDB search endpoint, TMDB movie details endpoint (for enrichment), OMDB endpoint, Wikipedia 6-step fallback URLs and response formats.

### Tech Stack Constraints
- `CLAUDE.md` §Spring @Async+@Retryable — `@Async` enrichment pipeline pattern, `@Retryable` on client methods (not on the `@Async` method itself), bounded thread pool config.
- `CLAUDE.md` §Wikipedia 6-step fallback — exact fallback URL construction: `{OriginalTitle}_{Year}_film` → `{OriginalTitle}_(film)` → `{OriginalTitle}` → same with `{Title}`.
- `CLAUDE.md` §WebClient vs RestTemplate — use WebClient for TMDB, OMDB, Wikipedia calls.
- `CLAUDE.md` §OpenSearch Java Client — **not needed for Phase 3** (OpenSearch write is Phase 4).

### Requirements
- `.planning/REQUIREMENTS.md` §Save Movie Flow — SAVE-01 through SAVE-05. Note D-04 deviation on SAVE-01 (search-only, no TMDB ID input).

### Prior Phase Context
- `.planning/phases/02-settings-api-keys/02-CONTEXT.md` — `useSettings` composable pattern, inline error/success patterns, WireMock test patterns.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/components/AuthCard.vue` — Card wrapper. May be reused or adapted for the /add page layout.
- `frontend/components/FormField.vue`, `InputText.vue`, `ButtonPrimary.vue` — Form components for the search input on /add.
- `frontend/components/SpinnerIcon.vue` — Existing spinner; reuse for the poster overlay.
- `frontend/composables/useAuth.ts` — Reference pattern for composable structure. Phase 3 needs a `useMovies` composable.
- `frontend/stores/auth.ts` — Pinia store pattern. Phase 3 may add a Pinia movies store or use a composable-only approach.
- `backend/de/moviearchive/settings/` — `SettingsService` + `SettingsController` pattern: reference for how to structure `MovieService` + `MovieController`.
- `backend/de/moviearchive/settings/TmdbKeyValidator.java`, `OmdbKeyValidator.java` — WebClient usage pattern for external API calls. Reuse for enrichment clients.

### Established Patterns
- UUID primary keys, Lombok `@Getter/@Setter/@NoArgsConstructor` on entities.
- Spring Data JPA repositories.
- Testcontainers (real Postgres) + WireMock (external APIs) for integration tests — all TMDB, OMDB, Wikipedia calls must be WireMocked.
- Error response: `{"message": "..."}` flat JSON.
- Frontend: inline error via `FormErrorBanner`; inline success via field-level state.
- Flyway: V5 is `user_api_keys`. Phase 3 migration is V6 (`movies` table with `status` column).

### Integration Points
- `SecurityConfig`: `/movies/**` and `/add` must be authenticated (no permitAll).
- `AppNav.vue`: add "Add Film" link (or "+" icon) visible when logged in.
- `SettingsService.getApiKey(userId, TMDB)` — Phase 3 must call this to retrieve the user's TMDB key for enrichment. OMDB key retrieved same way.
- Thread pool config: `@Async` executor bean must be configured (CLAUDE.md pattern) — check if already present from Phase 2, otherwise add.

</code_context>

<specifics>
## Specific Ideas

- **Poster-as-interactive-element UX**: The TMDB search result poster IS the save button — click it to trigger the save. The poster then transforms into a loading state (spinner overlay), success state (green checkmark), or error state (red X) in-place. This is intentionally unconventional — no separate "Add" button.
- **No archive page in Phase 3**: The user was explicit: Phase 3 is purely about the save flow. The home/index page is not the responsibility of this phase. The first "see your saved films" experience is Phase 5 Search.
- **OMDB/Wikipedia failures are invisible to the user**: Only TMDB failures (the mandatory enrichment step) result in ERROR status. OMDB and Wikipedia degradation is transparent — the film saves without that data, status is still SUCCESS.
- **Polling approach chosen explicitly**: SSE was considered and rejected as overkill for a personal single-user app with one-at-a-time saves.

</specifics>

<deferred>
## Deferred Ideas

- **Direct TMDB ID input** — User chose search-only for v1. Could be added in Phase 6 or 7 as a power-user feature.
- **Archive/list view** — Explicitly Phase 5 scope. The home/index page and the complete film list belong there.
- **OpenSearch write** — Phase 4 scope. Phase 3 ends at Postgres.
- **Retry button on error poster** — The error poster stays in the grid (user can retry by clicking again), but a dedicated "Retry" action could be a Phase 6/7 polish item.

</deferred>

---

*Phase: 03-save-movie-flow*
*Context gathered: 2026-05-16*
