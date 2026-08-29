# Phase 10: Bulk Import Engine - Context

**Gathered:** 2026-08-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Users upload a title+year list (a text file) in the Add Film area. The system parses each line, searches TMDB filtered by year, auto-saves unique matches via the existing save flow, flags ambiguous matches for later manual review, and skips lines that were already successfully imported on re-upload — without re-hitting TMDB for those. Live progress display and the per-line results overview UI are explicitly out of scope (Phase 11) — this phase builds the parsing/matching/execution engine and the persistence Phase 11's UI will read from.

</domain>

<decisions>
## Implementation Decisions

### Line format & parsing
- **D-01:** File format is CSV-style: `Title;OriginalTitle;Year` per line (semicolon-delimited). `OriginalTitle` field may be empty (`Title;;Year`).
- **D-02:** Lines are trimmed; blank lines are skipped silently. File is read as UTF-8 only.
- **D-03:** A line that fails to parse (missing/non-numeric year, wrong field count) is recorded with status "parse error" and processing continues to the next line — never aborts the whole batch.
- **D-04:** The Original Title field (when non-empty) is used for matching, not just informational — see D-06.

### TMDB matching & ambiguity
- **D-05:** Year filter is an exact match only — no ±1 tolerance. A candidate's parsed release year must equal the line's year exactly.
- **D-06:** When multiple year-matching candidates exist and an Original Title was supplied, an exact case-insensitive match against TMDB's `original_title` narrows the candidate set to one → treated as unique, auto-saved. If it doesn't narrow to exactly one, the line is still ambiguous.
- **D-07:** A line with zero year-matching candidates is recorded as "not found" and processing continues — distinct from "ambiguous" (multiple matches).

### Already-imported dedup (IMPORT-07)
- **D-08:** Dedup key is a normalized `(title, year)` pair from the uploaded line — checked against a persisted import record *before* any TMDB call, so re-uploads of already-saved lines never hit TMDB.
- **D-09:** Persistence: new table `bulk_import_line` (columns: `user_id`, `title`, `original_title`, `year`, `tmdb_id`, `status`) — one row per line ever processed for a user. This is also what Phase 11's results UI will read from (title, poster via `tmdb_id`, status).
- **D-10:** Skip-on-reupload applies **only** to lines with status `saved`. Lines previously recorded as `ambiguous`, `not_found`, or `parse_error` are retried (including a fresh TMDB call) on every re-upload — matches IMPORT-07's literal wording ("skip already-*imported*"), and avoids permanently trapping a fixable typo or a title not yet on TMDB.

### Job execution & result persistence
- **D-11:** Bulk import runs asynchronously: the upload endpoint returns 202 Accepted immediately; processing happens in a background job via a dedicated bounded executor — mirrors `WikiReloadService`/`WikiReloadController`'s pattern (self-proxy for per-item `@Transactional` calls, per-item failure isolation, pacing between TMDB calls, executor-full → 503).
- **D-12:** A unique-match line is saved by calling the existing `MovieService.initiate(tmdbId)` + `EnrichmentService.enrich()` exactly as `/movies/save` does today — no bulk-specific save path (per IMPORT-03's explicit instruction to reuse existing save/dedup logic).
- **D-13:** `bulk_import_line` rows are written/updated per-line, live, as each line finishes processing within the job — not buffered to a single end-of-job write. This is what lets Phase 11 implement live progress (IMPORT-05) by simply polling/counting this table; Phase 10 owns building that persistence, Phase 11 only adds UI on top.

### Claude's Discretion
- Exact `bulk_import_line` schema beyond the columns named in D-09 (e.g. timestamps, primary key shape, indexes) — planner's call.
- Whether the uploaded file has a header row / is quotable-CSV (RFC 4180) or plain semicolon-split — not raised during discussion; default to simple semicolon-split, no header, no quoting, unless planner finds a reason otherwise.
- Executor bean naming/pool sizing for the bulk-import background job — follow the `wikiReloadExecutor` sizing pattern in `AsyncConfig` unless there's a reason to differ.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & Roadmap
- `.planning/REQUIREMENTS.md` — IMPORT-01, IMPORT-02, IMPORT-03, IMPORT-04, IMPORT-07 (this phase's scope; IMPORT-05/06 belong to Phase 11)
- `.planning/ROADMAP.md` §"Phase 10: Bulk Import Engine" — goal, success criteria, depends-on note (independent of Phase 8–9)

### Design system
- `.planning/UI-SPEC.md` — shared app-level design contract (shadcn-vue manual tokens, radix-vue, lucide-vue-next, avantgardistic/editorial aesthetic, no rounded corners, terracotta-only error palette). Governs the Add Film upload UI even though it's scoped to Phase 1 in its frontmatter — per prior project convention, phases reuse this shared spec rather than generating a new per-phase UI-SPEC.

No other external specs/ADRs — requirements fully captured in decisions above.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `backend/src/main/java/de/moviearchive/movie/MovieController.java:33-42` — `POST /movies/save` (tmdbId) → `MovieService.initiate()`; the exact save call D-12 reuses per matched line.
- `backend/src/main/java/de/moviearchive/movie/MovieService.java:47-62` (`initiate`) — check-then-insert idempotency via `findByUserIdAndTmdbId`; bulk import gets this idempotency for free by calling `initiate()` directly.
- `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java:27-52` — `search(query, tmdbKey)` against TMDB `/3/search/movie`, already parses `release_date` into a year. Bulk import's year filter (D-05) and original-title match (D-06) are applied on top of this result list — no year filtering exists there today, must be added.
- `backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java` — record `(tmdbId, title, year, posterPath)`; check whether `original_title` needs to be added to this DTO for D-06 (TmdbClient currently maps `title`, not confirmed if `original_title` is captured — planner/researcher to verify).
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` + `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — the async job template D-11 mirrors: dedicated executor bean, 202-accepted trigger, `@Lazy` self-proxy for per-item `@Transactional` calls, `Thread.sleep` pacing, per-item failure isolation, `TaskRejectedException` → 503 on full queue.

### Established Patterns
- No multipart/file-upload handler exists anywhere in the backend today (confirmed via grep) — this phase's upload endpoint is the first of its kind; no existing pattern to mirror for the multipart-handling part specifically.
- `frontend/pages/add.vue` + `frontend/composables/useMovies.ts` — existing search-and-save UI pattern (search → poster grid → save → poll status every 2.5s). The bulk-import upload control should live in this same Add Film area per the phase goal; UI wiring beyond upload (no progress bar, no results list) is out of scope for this phase per Phase 11 boundary.
- `GET /movies/saved-ids` (`MovieController.java:44-48`) — existing tmdb_id-based saved-check the frontend already uses; not the mechanism for D-08's dedup (which is title/year-based, pre-search), but relevant context for how "already saved" is currently signaled elsewhere.

### Integration Points
- New `bulk_import_line` table needs a Flyway migration — check the actual highest existing `V{n}` in `backend/src/main/resources/db/migration/` at implementation time (sequential convention, per Phase 8's context).
- Async executor config — check `AsyncConfig` (referenced by Phase 8/9 context) for the `wikiReloadExecutor` bean pattern to mirror for a new bulk-import executor bean.

</code_context>

<specifics>
## Specific Ideas

- The user explicitly chose CSV-style `Title;OriginalTitle;Year` over the ROADMAP's literal "Title (Original Title) Year" parenthetical wording — this is an implementation-detail deviation the user made deliberately when given the choice, not an oversight. Planner should use the semicolon format as-is.
- The user consistently favored the option that kept lines processable individually and re-triable (parse errors don't abort the batch; not-found/ambiguous lines aren't permanently skip-locked) over stricter/simpler alternatives — a "never lose a line permanently to a transient failure" preference that should guide any related judgment calls during planning.

</specifics>

<deferred>
## Deferred Ideas

- **Live progress indicator during import** — Phase 11 (IMPORT-05). This phase (D-13) builds the per-line status persistence Phase 11 will poll, but does not build the polling endpoint or UI itself.
- **Per-line results overview (title, poster, status)** — Phase 11 (IMPORT-06). This phase's `bulk_import_line` table (D-09) is the data source; the display is Phase 11's job.
- **Manual resolution UI for ambiguous lines** — not requested/scoped in either Phase 10 or 11's stated success criteria; ambiguous lines are recorded (D-07/D-09) but no resolution flow was discussed. Flag for the roadmap backlog if the user wants one.

### Reviewed Todos (not folded)
- **"Show progress indicator for Wikipedia batch-reload"** (`.planning/todos/pending/2026-08-23-show-progress-indicator-for-wikipedia-batch-reload.md`) — low match score (0.3, area: ui). This todo is about the *Wikipedia* batch-reload job (Phase 8), not bulk import; not folded into Phase 10.

</deferred>

---

*Phase: 10-bulk-import-engine*
*Context gathered: 2026-08-23*
