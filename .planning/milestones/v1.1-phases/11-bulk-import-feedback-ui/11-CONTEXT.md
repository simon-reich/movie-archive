# Phase 11: Bulk Import Feedback UI - Context

**Gathered:** 2026-08-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can track an in-progress bulk import via live server-pushed progress, and browse a
persisted, retrievable report per import batch (title, poster, status per line) — both during
the run and afterward, across sessions. This delivers IMPORT-05 (live progress) and IMPORT-06
(per-line results overview), reframed by this discussion from "ephemeral overview" to "persisted,
revisitable report" per the user's explicit requirement below.

</domain>

<decisions>
## Implementation Decisions

### Progress mechanism
- **D-01:** Live progress is pushed via WebSocket/SSE, not polling. — **Reversibility:** costly — **rationale:** no existing WebSocket/SSE infrastructure in this project (confirmed via codebase scout: no such dependency in `backend/build.gradle.kts`, no reactor/websocket usage anywhere in `backend/src/main/java`). This is a new capability for the backend, not a reuse of the existing single-movie polling pattern in `frontend/pages/add.vue:69-96`. Research should investigate the minimal-footprint approach given Spring Boot 3 (WebSocket vs. SSE via `text/event-stream` — SSE is simpler for one-directional server→client progress and likely the better fit than full WebSocket).
- **Claude's Discretion:** SSE vs. full WebSocket — user picked "WebSocket/SSE" as one bucket; research should recommend the simpler one (likely SSE) given the one-directional nature of progress updates, unless research finds a concrete reason otherwise.

### Data model — batch grouping
- **D-02:** Every bulk-import upload gets its own batch identifier (e.g. `import_batch_id` + timestamp), and `BulkImportLine` rows are tagged with the batch they belong to. — **Reversibility:** one-way — **rationale:** requires a schema migration (new column, backfill strategy for existing Phase 10 rows which have no batch concept today — they'll need a synthetic/legacy batch id or be excluded from the report list). This is the foundation the whole report-browsing feature depends on; reversing it later means migrating data again.
- **D-03:** A new page lists past bulk-import batches (date, line count, status distribution); clicking one opens its full per-line results list. — **Reversibility:** reversible — **rationale:** pure UI/routing, no data-model lock-in beyond D-02.

### Poster images
- **D-04:** `poster_path` is cached onto `BulkImportLine` at save time (in `saveAndUpsert`, `BulkImportService.java:180-184`, where `tmdbId` is already known/fetched) — no extra TMDB calls when rendering the results list later. — **Reversibility:** reversible — **rationale:** additive column, no migration of existing behavior required beyond a schema addition.
- Rows with no `tmdbId` (AMBIGUOUS, NOT_FOUND, PARSE_ERROR) have no poster — results list needs a text-only/placeholder fallback for these.

### Interaction scope
- **D-05:** The results view is read-only for this phase — no inline "pick a TMDB candidate and save" action from AMBIGUOUS/NOT_FOUND rows. Manual correction stays on the existing Add Film search flow. — **Reversibility:** reversible — **rationale:** this narrows Phase 11 scope; making rows actionable later is a pure additive feature, not a rework.
- **Deferred idea (explicitly out of scope for Phase 11):** inline resolution of AMBIGUOUS/NOT_FOUND rows directly from the results view — noted below in Deferred Ideas, candidate for a future phase given today's real-world UAT finding (Predator/Zama/Obsession all landed AMBIGUOUS with zero user-facing visibility until this discussion).

### Report persistence — explicit non-goal
- **D-06:** NOT a downloadable file (CSV/PDF/etc.) — the user explicitly rejected this ("Kein Download als File, das ist irgendwie zu fizzelig"). Reports are stored in Postgres (via D-02's batch grouping) and viewed in-app only. — **Reversibility:** reversible — **rationale:** a download/export feature can be added later without touching the storage model; it would just be an additional export view over the same persisted batch data.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase 10 foundation (what this phase builds on)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` — current entity fields (no batch id, no poster_path yet — both added by this phase's D-02/D-04)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineStatus.java` — the 4 terminal statuses (SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR), no "processing" state — progress must be inferred from row counts, not a per-line status
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` — `runImport()` (the `@Async` batch loop, `bulkImportExecutor` singleton pool) and `processLine()`/`saveAndUpsert()` (where D-04's poster_path capture point is)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` — current single POST-upload endpoint; needs a new GET endpoint (batch list) and GET endpoint (single batch detail) per D-02/D-03
- `.planning/phases/10-bulk-import-engine/10-CONTEXT.md` — Phase 10's locked decisions (D-01 strict `Title;OriginalTitle;Year` format, D-03 per-line failure isolation, D-04 never auto-guess on ambiguity) — still binding, unchanged by this phase
- `.planning/phases/10-bulk-import-engine/10-UAT.md` — real-world UAT finding (2026-08-24) that motivated this phase: Predator/Zama/Obsession all silently landed as AMBIGUOUS with zero user visibility

### Requirements
- `.planning/REQUIREMENTS.md` §IMPORT-05/IMPORT-06 — locked requirement text (live progress; per-line results with title/poster/status)

### Existing patterns to reuse for visual/interaction consistency
- `frontend/pages/add.vue:69-101` — existing single-movie polling pattern (`startPolling`, `pollingIntervals` Map, cleanup in `onUnmounted`) — not reused for D-01 (SSE/WebSocket instead) but useful reference for how async status is currently surfaced in this codebase
- `frontend/pages/add.vue:161-210` — existing poster-grid result card style (responsive grid, status overlay with `lucide-vue-next` icons `CheckCircle2`/`XCircle`, `bg-background/70` overlay) — the new batch-detail results list should reuse this card/overlay/icon vocabulary, mapped to the 4 `BulkImportLineStatus` values, with a text-only fallback row style for statuses with no poster
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java:30-55` — `bulkImportExecutor` bean config (core=1/max=1/queue=1) — confirms bulk import is effectively a single global in-flight job, relevant to how "is an import currently running" is exposed for D-01's live-progress push

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/pages/add.vue`'s poster-grid card/overlay/status-icon markup — directly adaptable for the batch-detail results list (D-04's poster display)
- `BulkImportLine` repository already has upsert/lookup-by-title-year queries — needs new methods: list-by-batch, list-batches-for-user (D-02/D-03)

### Established Patterns
- Async batch jobs in this codebase (`WikiReloadService`, `BulkImportService`) use a `@Lazy` self-proxy field to route per-line calls through Spring's transactional/async proxy — same pattern should be followed if D-01's progress push needs to read/update state from within the async loop
- Singleton `ThreadPoolTaskExecutor` (core=1/max=1/queue=1) per async feature — no concurrent bulk imports per user or globally today; D-01's progress mechanism doesn't need to handle concurrent-batch complexity

### Integration Points
- SSE/WebSocket endpoint (D-01) will need to read live progress from the same `BulkImportLine` table `runImport()` writes to (row count so far vs. total lines submitted, since there's no "processing" status) — likely a scheduled/interval push server-side reading DB state, not truly event-driven from the async loop itself, unless research finds a cleaner way to hook into `runImport()`'s loop directly
- New batch-list and batch-detail GET endpoints on `BulkImportController` (or a new controller) per D-02/D-03
- New frontend route for the batch-list/report page per D-03 (placement decision: dedicated page, not inline on add.vue)

</code_context>

<specifics>
## Specific Ideas

- User's own words on why persistence matters: "Das muss irgendwie gesaved werden... ich muss irgendwo die Bulk Imports Reports wiederherstellen können, abrufen und wiederherstellen können und mir einsehen können." — this is the core driver behind D-02/D-03/D-06. Losing a report after 2-3 clicks (e.g. navigating away) is explicitly the failure mode to avoid.
- Real trigger for this whole phase: today's UAT session on Phase 10 surfaced that AMBIGUOUS-status lines (Predator 1987, Zama 2017, Obsession 2026 — all genuinely existing, correctly found by TMDB, but ambiguous without an Original Title to disambiguate) were completely invisible to the user, who assumed the import silently failed. This phase's results view must make AMBIGUOUS/NOT_FOUND/PARSE_ERROR rows clearly visible with their status, not just SAVED rows.

</specifics>

<deferred>
## Deferred Ideas

- **Inline resolution of AMBIGUOUS/NOT_FOUND rows** from the results view (pick a TMDB candidate and save directly) — explicitly deferred per D-05; candidate for a future phase.
- **Downloadable/exportable report file** (CSV/PDF) — explicitly rejected as the primary mechanism (D-06) but could be a small additive feature later since the underlying data will already be persisted per-batch.

### Reviewed Todos (not folded)
- **Show progress indicator for Wikipedia batch-reload** (`.planning/todos/pending/2026-08-23-show-progress-indicator-for-wikipedia-batch-reload.md`) — matched by keyword ("progress", "indicator") but is a different feature (Wikipedia batch-reload, not bulk import). Not folded — stays a separate todo; may share the SSE/progress-endpoint pattern established here as prior art for a future phase.
- **Support real CSV parsing for bulk import** (`.planning/todos/pending/2026-08-24-support-real-csv-parsing-for-bulk-import.md`) — matched by keyword ("bulk", "import") but is about the upload file FORMAT, not the results/feedback UI. Out of scope for this phase; stays a separate todo.

</deferred>

---

*Phase: 11-bulk-import-feedback-ui*
*Context gathered: 2026-08-24*
