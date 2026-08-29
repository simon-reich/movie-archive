# Phase 9: Manual Wiki Retry - Context

**Gathered:** 2026-08-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can manually retry Wikipedia enrichment for a single film from its detail page and immediately see whether it succeeded (ENRICH-04, ENRICH-05). Folded into this phase: a Settings-page button to manually trigger the Phase 8 batch-reload endpoint (previously admin-endpoint-only with no UI trigger), per the user's explicit 2026-08-23 decision recorded in the now-folded todo.

**Scope anchor:** ENRICH-04 (per-film Retry button, single enrichment attempt), ENRICH-05 (result feedback, `wiki_last_attempted_at` updated on manual attempts too) + folded todo (batch-reload UI trigger).

**Explicit out-of-scope for Phase 9:**
- Live/incremental progress tracking for the batch-reload run — it stays fire-and-forget (202 Accepted); only a simple acknowledgement message is shown.
- A "batch currently running" status indicator on the detail page — considered and explicitly deferred (see Deferred Ideas).
- Cooldown enforcement on the manual per-film retry — deliberately bypassed (see decisions).
- Any change to TMDB/OMDB data or `movie.status` — this phase only ever touches the Wikipedia fields, reusing Phase 8's `retryWikipedia(Movie)` exactly as built.

</domain>

<decisions>
## Implementation Decisions

### Cooldown & Concurrency

- **D-01:** The manual per-film Retry button **always bypasses the 30-day cooldown** — it is clickable any time a film has no Wikipedia data (`wikipediaUrl` is null in the DTO), regardless of `wiki_last_attempted_at`. This differs deliberately from batch-reload's cooldown-filtered eligibility (Phase 8 D-03): a manual click is a deliberate one-off user action, not a bulk sweep that needs rate-limit protection.
- **D-02:** No coordination/blocking logic between a manual retry and an in-flight batch-reload run. The two can rarely overlap (batch is paced at 1 call/second; manual retry is a single call), and the worst case is one extra near-simultaneous Wikipedia request — negligible next to the ~630-simultaneous-call incident that motivated Phase 8's pacing. Overlap risk is explicitly accepted.

### Detail Page — Retry Button & Feedback

- **D-03:** When `movie.wikipediaUrl` is null, the detail page shows **"No Wikipedia data found" plus a Retry button** in the same full-width area where the Wikipedia Plot/Critical Response sections would otherwise render (`frontend/pages/movies/[id].vue` ~line 329, currently `v-if="movie.wikipediaPlot || movie.wikipediaCritics"` hides the whole section) — the retry prompt replaces that hidden section rather than living elsewhere (e.g. the hero). No `wiki_last_attempted_at` timestamp is surfaced — just the plain "no data found" message.
- **D-04:** While the retry request is in flight (synchronous Wikipedia call, can take a few seconds), the button shows a spinner (reuse `frontend/components/SpinnerIcon.vue`, same pattern used for the page's loading state) and is disabled.
- **D-05:** No toast component (none exists in this app). On success, the `movie` ref updates from the response and the Plot/Critical Response sections render inline in place of the retry prompt. On failure, the "No Wikipedia data found" message stays; Claude's discretion on whether to add a brief inline note (e.g. "Still no page found") distinguishing "never tried" from "just retried and failed again."

### Batch-Reload Trigger Button (folded from todo)

- **D-06:** Add a lightweight **`GET /users/me`** endpoint returning the authenticated user's id, reusing the existing `resolveUserId(auth)` pattern from `MovieDetailController` (JWT subject → email → `UserRepository.findByEmail` → id). The Settings page fetches this once on load, then uses the id to call the existing `POST /admin/wiki-reload/{userId}` (Phase 8's contract is unchanged — no path/method changes to that endpoint). — **Reversibility:** reversible — purely additive endpoint, no existing contract touched.
- **D-07:** Settings page button: on `202 Accepted`, show an inline acknowledgement — **"Reload started — this runs in the background and may take a few minutes."** On `503` (a batch is already running, per Phase 8's `TaskRejectedException` handler), show **"A reload is already in progress."** No live progress tracking (explicitly out of scope, consistent with Phase 8's CONTEXT.md rejection of a progress UI for this endpoint).

### Claude's Discretion

- Exact response shape of the new per-film retry endpoint (e.g. return the full updated `MovieDetailResponse`, or just the changed wiki fields + a success/failure flag) — planner's call, informed by what's simplest for the frontend to merge into the existing `movie` ref.
- Exact wording/placement of the optional "retried and still not found" distinction (D-05).
- Whether the new per-film retry endpoint lives on `MovieDetailController` (as `POST /movies/{id}/retry-wiki` or similar, following its `resolveUserId(auth)` + `findByIdAndUserId` convention) or as a new small controller — planner's call; `MovieDetailController` is the closer structural analog since it already does per-movie ownership-scoped operations, unlike `WikiReloadController` which is batch/admin-styled with a path-param userId.
- Exact naming and response shape of the new `GET /users/me` endpoint (e.g. `{ "id": "..." }` vs a fuller user DTO).
- Whether the Settings page's batch-reload button needs any disabled/cooldown state of its own, or is always clickable (no cooldown was discussed for this button specifically — Phase 8's cooldown filtering happens server-side per eligible film, not on whether the button itself can be clicked).

### Folded Todos

- **"Add batch wiki-reload trigger button to UI"** (`.planning/todos/2026-08-23-add-batch-wiki-reload-trigger-button-to-ui.md`) — Phase 8 shipped `POST /admin/wiki-reload/{userId}` as admin-endpoint-only with no UI trigger. User clarified on 2026-08-23 this should be user-triggerable via a Settings page button, and explicitly decided to fold it into Phase 9 alongside the per-film retry button rather than spin up a separate phase. See D-06/D-07 above for the resulting decisions.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model
- `.claude/data-model.md` — `movies` table schema, including `wiki_url`/`wiki_plot`/`wiki_summary`/`wiki_critics`/`wiki_last_attempted_at` (the last added in Phase 8).

### API Contracts
- `.claude/api-contracts.md` §Wikipedia API — 6-step title fallback strategy (unchanged; this phase never touches `WikipediaClient` directly, only calls the existing `WikiReloadService.retryWikipedia(Movie)`).

### Tech Stack Constraints
- `CLAUDE.md` §Spring @Async+@Retryable — self-invocation rules (relevant background; the new per-film endpoint calls `retryWikipedia(Movie)` synchronously, NOT via `@Async`, so no proxy self-invocation concern applies here the way it did for Phase 8's `batchReload`).

### Requirements
- `.planning/REQUIREMENTS.md` §Enrichment Reliability — ENRICH-04, ENRICH-05 (this phase).
- `.planning/ROADMAP.md` — Phase 9 entry (Goal, Depends on Phase 8, Success Criteria, UI hint: yes).

### Prior Phase Context & Patterns
- `.planning/phases/08-wiki-enrichment-tracking-batch-reload/08-CONTEXT.md` — D-01 through D-08: narrow Wikipedia-only retry, re-indexing on late success, cooldown/pacing config, admin-endpoint trigger model. Phase 9 directly reuses `retryWikipedia(Movie)` built here.
- `.planning/phases/08-wiki-enrichment-tracking-batch-reload/08-PATTERNS.md` — Full pattern map for `WikiReloadService`/`WikiReloadController`/`AsyncConfig` — the exact `retryWikipedia(Movie)` method body (self-proxy via `@Lazy WikiReloadService self`, `@Transactional`, silent-fail-with-log) is what the new single-film endpoint will call.
- `.planning/todos/2026-08-23-add-batch-wiki-reload-trigger-button-to-ui.md` — folded todo, see Decisions above.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — `retryWikipedia(Movie movie)` (lines 72-103) already does exactly the single-film retry logic this phase needs: sets `wikiLastAttemptedAt` on every attempt, fetches via `WikipediaClient`, updates wiki fields on success, re-indexes to OpenSearch on success (D-02 from Phase 8), silently logs on failure. This phase's new endpoint should call this method directly and synchronously (it is `@Transactional` but NOT `@Async` — no self-invocation concern for an external caller).
- `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` — structural analog for the new per-film retry endpoint: `resolveUserId(auth)` (JWT → email → `UserRepository` → id) then an ownership-scoped repository call, same convention as `getDetail`/`updatePersonal`/`deleteMovie`.
- `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` — `findByIdAndUserId(movieId, userId)` (used in `getDetail`/`updatePersonal`/`deleteMovie`) is the exact ownership-scoped lookup pattern to reuse for fetching the movie before calling `retryWikipedia`.
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — `findByIdAndUserId` already exists; no new repository method needed for the per-film lookup.
- `frontend/composables/useMovieDetail.ts` — `updatePersonal`/`deleteMovie` show the established fetch-with-auth-headers pattern (`$fetch` + `authHeaders()` + `credentials: 'include'`) to copy for a new `retryWikipedia` function returning updated wiki fields to merge into `movie.value`.
- `frontend/components/SpinnerIcon.vue` — reuse for the retry button's loading state (D-04).
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — existing batch endpoint; unchanged by this phase, but its `TaskRejectedException` → 503 handler is what the Settings button's "already in progress" message (D-07) reacts to.

### Established Patterns
- Ownership check via JWT-derived `userId` + `findByIdAndUserId`, NOT a path-param `userId` + `assertOwnership` (that's the older `WikiReloadController`/`ReindexController` convention) — `MovieDetailController` represents the newer, preferred pattern per-movie endpoints follow. The new per-film retry endpoint should follow this newer convention.
- Synchronous `@Transactional` service methods returning immediately with the result — no `@Async` needed for single-film operations (only the batch loop needs `@Async` + pacing).
- Frontend: local `ref` + `$fetch` composable pattern (`useMovieDetail.ts`), no global state/store for movie detail data.

### Integration Points
- `frontend/pages/movies/[id].vue` line 329 (`v-if="movie.wikipediaPlot || movie.wikipediaCritics"`) — this condition needs an `v-else` (or restructuring) to show the new retry prompt when both are null.
- `frontend/pages/settings.vue` — new batch-reload trigger button lives here (not yet inspected in detail; planner/researcher should read it to find the right insertion point and existing button/section styling to match).
- No `/users/me`-style endpoint exists yet anywhere in the codebase (confirmed by directory scan of `de.moviearchive.user`) — D-06's new endpoint is the first of its kind; researcher should check `UserController`/`UserRepository` for the right home for it.

</code_context>

<specifics>
## Specific Ideas

- The user considers the manual retry button a "deliberate one-off action" distinct from batch-reload's rate-limit-conscious cooldown sweep — this framing (D-01, D-02) should guide the planner away from adding cooldown/coordination logic that isn't requested.
- The user twice raised wanting *some* informational signal (last-attempt info; batch-running indicator) but in both cases chose the simpler option when given a concrete tradeoff — lean toward minimal-but-clear feedback over building new status-tracking infrastructure in this phase.

</specifics>

<deferred>
## Deferred Ideas

- **Batch-reload running status indicator** (e.g. a `GET /admin/wiki-reload/status` endpoint + a banner on the detail page while a batch is in flight) — raised during discussion of the concurrency overlap, but deferred: the overlap risk itself was accepted as low, so the extra status-tracking surface wasn't judged worth building now. Revisit if manual-retry-during-batch overlap turns out to cause real confusion in practice.
- **Surfacing `wiki_last_attempted_at` timestamp in the UI** — considered for the "no wiki data found" message, but the user chose the simpler no-timestamp version. Could resurface if users want to know "when was this last checked" later.

### Reviewed Todos (not folded)
None — the only matching todo (batch-reload trigger button) was folded into this phase; see Decisions § Folded Todos.

</deferred>

---

*Phase: 9-manual-wiki-retry*
*Context gathered: 2026-08-23*
