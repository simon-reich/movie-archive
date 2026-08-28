# Phase 15: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing - Context

**Gathered:** 2026-08-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Closes the two loose bulk-import follow-up todos left over from Phases 10-11 so v1.1 can close
with the import feature actually finished:

1. The batch detail page (`/imports/{batchId}`) gets a list/grid view toggle, clickable movie
   links on SAVED lines, and inline ambiguous/not-found resolution (search-and-save without
   leaving the page, with the batch report updating live).
2. Bulk-import file parsing moves from the strict `Title;OriginalTitle;Year` semicolon-only
   format to real CSV parsing (comma-delimited, quoted fields, optional header row) — while
   keeping the existing semicolon format working, unchanged, as a fallback.

This is the last phase before v1.1 (Enrichment Reliability & Bulk Import) can close.

</domain>

<decisions>
## Implementation Decisions

### View toggle
- **D-01:** Default view on `/imports/{batchId}` is the current poster-grid; list is the new opt-in view. — **Reversibility:** reversible
- **D-02:** View-mode preference persists in `localStorage` (per-browser), not a backend setting. — **Reversibility:** reversible
- **D-03:** List view shows a small thumbnail + text per row (not text-only, not full-size posters). — **Reversibility:** reversible
- **D-04:** List view reuses the exact same status vocabulary as grid (the `CheckCircle2`/`XCircle` icons and `statusLabel()` mapping in `frontend/pages/imports/[batchId].vue`), just laid out inline per row instead of as an image overlay. — **Reversibility:** reversible

### Movie links for SAVED lines
- **D-05:** The entire SAVED card is clickable, linking to `/movies/{movieId}` — not a small icon/link within the card. — **Reversibility:** reversible
- **D-06:** `movieId` is resolved server-side at response time in `BulkImportController.getBatchDetail()`, via the existing `MovieRepository.findByUserIdAndTmdbId(userId, tmdbId)` — no schema change, no new column, no migration. `BulkImportLineResult` gains a nullable `movieId` field. — **Reversibility:** reversible
- **D-07:** AMBIGUOUS/NOT_FOUND/PARSE_ERROR cards get no movie link — resolution for AMBIGUOUS/NOT_FOUND is handled entirely by the new inline-resolve widget (see below); PARSE_ERROR gets no resolve action at all (see below). — **Reversibility:** reversible

### Inline ambiguous/not-found resolution
- **D-08:** BulkImportService never persists TMDB candidates for AMBIGUOUS lines (confirmed in code: `BulkImportService.java` — "D-04: multiple candidates, no unambiguous narrowing — never auto-guess" — only the AMBIGUOUS status is upserted, candidates are discarded). Inline resolve therefore does a **fresh TMDB search on expand**, prefilled with the line's title (and year if present), via the existing `/movies/search` endpoint — no reuse of stale candidates, no new server-side matcher endpoint. — **Reversibility:** reversible
- **D-09:** After the user picks a candidate and saves inline, the batch-detail page **refetches the full batch** (`GET /movies/bulk-import/batches/{batchId}`) rather than optimistically patching local state — simplest, guarantees the card's status/poster/movieId are all server-truth-consistent after resolve. — **Reversibility:** reversible
- **D-10:** Inline resolve uses a **new endpoint** (e.g. `POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve`) that both (a) saves the movie via the existing `MovieService.initiate()` path and (b) updates that specific `BulkImportLine` row's `status` → `SAVED`, `tmdbId`, and `posterPath`. This directly closes the gap the todo called out: "no way to see, back on the batch page, that a given line has since been resolved." Plain reuse of `/movies/save` was explicitly rejected because it would leave the line stuck at AMBIGUOUS forever in the batch report. — **Reversibility:** costly — **rationale:** downstream agents/tests should treat this as a real new API surface (ownership check on both batchId and lineId, same pattern as `loadOwnedBatch()`), not a trivial reuse of `/movies/save`.
- **D-11:** **PARSE_ERROR is its own separate category**, visually/structurally distinct from AMBIGUOUS/NOT_FOUND — not just another failure-status card with the same treatment. It gets **no inline resolve widget** (a garbled/malformed line often has no usable title text to search from). Instead, PARSE_ERROR cards must display the **raw line text** (`BulkImportLine.rawLine`, already persisted server-side per line — needs adding to `BulkImportLineResult`/`BulkImportBatchDetail` API response, not currently exposed) so the user can trace exactly which line in their source file failed and why. User's own words on why: "dann kann man vielleicht einfach den CSV-String da reinsetzen, sodass man nachvollziehen kann, wo der Fehler möglicherweise liegt in der Datei." — **Reversibility:** reversible

### Real CSV parsing & format
- **D-12:** Both formats are supported going forward — the parser must auto-detect/accept **both** the legacy semicolon format (`Title;OriginalTitle;Year`) and real comma-delimited CSV (with quoted fields, e.g. `"Title, Part 2",OriginalTitle,Year`). The legacy format is NOT replaced or deprecated. — **Reversibility:** reversible — **rationale:** purely additive parsing capability; removing CSV support later wouldn't break the semicolon path.
- **D-13:** Use **Apache Commons CSV** (as suggested in the source todo) for real comma/quote parsing — not a hand-rolled extension of `ImportLineParser`'s manual `split(";")` logic. Not yet a dependency in `backend/build.gradle.kts` — must be added. — **Reversibility:** reversible
- **D-14:** The parser supports an **optional header row** — auto-detected (e.g., if the first row's "Year" column doesn't parse as an integer, treat row 1 as a header and skip it) rather than requiring an explicit flag or fixed first-line convention. Matches how real CSV exports from Excel/Numbers/Sheets typically look. — **Reversibility:** reversible
- **D-15:** A title containing a comma is handled correctly by standard CSV quoting (`"Title, Part 2",,Year`) once Commons CSV parsing is in place — this works out of the box, no special-casing needed.
- **D-16:** A title containing a literal semicolon in the **legacy semicolon format** is an accepted, documented limitation — NOT special-cased with new quoting logic added to the legacy parser. The legacy format stays exactly as simple as it is today (unchanged `ImportLineParser`, `split(";", -1)`, no quoting). Such a line will misparse into extra fields and land as PARSE_ERROR — which is now directly diagnosable via D-11's raw-line display, so the user can see and fix it by switching that one line to CSV/quoted form. — **Reversibility:** reversible
- **D-17:** `saubere_filmliste.txt` (untracked, 1139 lines, repo root, currently semicolon-format) is the user's real archive list and is a legitimate real-world test input for this phase's backward-compat requirement (D-12) — but it does NOT need conversion or special handling; the user noted it "lässt sich dann ja auch nochmal schnell in ein anderes Format konvertieren. Müssen wir momentan nicht beachten" (can be converted quickly later if needed; not something to design around now).

### Claude's Discretion
- Exact visual treatment distinguishing PARSE_ERROR cards from AMBIGUOUS/NOT_FOUND (D-11) — e.g. a distinct badge color, a separate section/grouping, or different card styling — left to implementation judgment as long as it reads as a clearly separate category, not just another status icon.
- Exact CSV delimiter auto-detection strategy (D-12) — e.g. sniff first line for `,` vs `;` presence, or try comma first and fall back to semicolon on parse failure — left to research/planning, as long as both formats keep working without the user having to declare which one they're uploading.

### Folded Todos
- **`.planning/todos/pending/2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link.md`** ("Enhance bulk import batch detail page: view toggle, movie links, inline ambiguous resolve") — fully folded; this is the primary source for the View toggle, Movie links, and Inline resolve decision groups above.
- **`.planning/todos/pending/2026-08-24-support-real-csv-parsing-for-bulk-import.md`** ("Support real CSV parsing for bulk import (and matching CSV export)") — the *import* half is folded (D-12–D-16); the *export* half (matching future CSV export feature, SET-05) is explicitly NOT in scope — see Deferred below.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Source todos (fully folded into this phase's scope)
- `.planning/todos/pending/2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link.md`
- `.planning/todos/pending/2026-08-24-support-real-csv-parsing-for-bulk-import.md`

### Root cause context (motivates the CSV/format-guidance direction)
- `.planning/debug/bulk-import-not-adding-movies.md` — diagnosed 2026-08-24: a plain-title-list upload (no semicolons) silently produces 100% PARSE_ERROR rows with zero UI feedback. Phase 11 fixed the "zero UI feedback" half (batch report exists now); this phase's D-11 (raw-line display on PARSE_ERROR) is the direct continuation of that fix — the user can now actually see *which* line broke and why, rather than just an opaque PARSE_ERROR badge.

### Phase 10 foundation (bulk-import engine — still binding)
- `backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java` — current strict semicolon-only parser (`split(";", -1)`, no quoting); stays unchanged per D-16, new CSV parsing is additive alongside it (D-12)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` — `processLine()`/`saveAndUpsert()` (AMBIGUOUS path at "D-04: multiple candidates, no unambiguous narrowing — never auto-guess", confirms no candidates are persisted — informs D-08)
- `.planning/phases/10-bulk-import-engine/10-CONTEXT.md` — D-01 (strict format origin), D-04 (never auto-guess on ambiguity — still binding for inline resolve, D-08/D-10)

### Phase 11 foundation (batch report — what this phase extends)
- `frontend/pages/imports/[batchId].vue` — current grid-only results view; view toggle (D-01–D-04), movie links (D-05), and inline resolve (D-08–D-11) all extend this file
- `frontend/composables/useBulkImport.ts` — `BulkImportLineResult`/`BulkImportBatchDetail` types need extending with `movieId` (D-06) and `rawLine` (D-11); `getBatchDetail()` is the refetch call for D-09
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` — `getBatchDetail()` (movieId lookup point, D-06) and `loadOwnedBatch()` (ownership-check pattern to replicate for the new resolve endpoint, D-10)
- `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` — has `tmdbId`, `posterPath`, `rawLine`, `status` fields already; D-06/D-10/D-11 all read/write these, no new columns needed
- `.planning/phases/11-bulk-import-feedback-ui/11-CONTEXT.md` — D-05 (results view was explicitly read-only in Phase 11, deferred inline resolution to "a future phase" — this phase); D-04 (poster_path caching pattern, same pattern applies to any new resolve-time poster capture)

### Existing patterns to reuse
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` — `findByUserIdAndTmdbId(userId, tmdbId)` already exists, used as-is for D-06
- `backend/src/main/java/de/moviearchive/movie/Movie.java` / `MovieService.initiate()` — existing idempotent save pipeline, reused (not reimplemented) by the new resolve endpoint (D-10)
- `frontend/composables/useMovies.ts` — `searchTmdb()`/`saveMovie()` (existing manual search-and-save flow) — the inline-resolve widget's UX is modeled on this, per D-08

### CSV parsing (new dependency)
- `backend/build.gradle.kts` — Apache Commons CSV (D-13) is NOT currently a dependency; must be added
- `saubere_filmliste.txt` (repo root, untracked, 1139 lines) — user's real archive list, semicolon-format; valid real-world test input for the backward-compat requirement (D-12/D-17), not something requiring special handling

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/pages/imports/[batchId].vue`'s existing card/overlay/`statusLabel()` vocabulary — directly reused for D-04 (list view status display)
- `MovieRepository.findByUserIdAndTmdbId()` — directly reused for D-06, no new query needed
- `MovieService.initiate()` — directly reused by the new resolve endpoint (D-10), same idempotent-save semantics as manual save and bulk-import's own `saveAndUpsert()`
- `BulkImportController.loadOwnedBatch()` — ownership-check pattern to replicate (extend to also verify lineId belongs to batchId) for the new resolve endpoint

### Established Patterns
- Bulk-import and wiki-reload both use `@Lazy` self-proxy for per-item calls through Spring's transactional/async proxy — relevant if the new resolve endpoint needs to combine `initiate()` + line-status-update in one transaction
- "Never auto-guess on ambiguity" (Phase 10 D-04) is a hard invariant carried forward — inline resolve always requires an explicit user pick from search results, never silently picks a top match

### Integration Points
- New `movieId` and `rawLine` fields on `BulkImportLineResult` (backend DTO) and its frontend `BulkImportLineResult` type in `useBulkImport.ts` — both need extending together
- New `POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve` endpoint (D-10) — new controller method + new composable function in `useBulkImport.ts`
- `ImportLineParser` gains a sibling/alternate CSV-aware parse path (Commons CSV, D-13) that runs alongside the existing semicolon parser (D-12) — likely a delimiter-detection step in `BulkImportController`/`BulkImportService` before dispatching to whichever parser applies

</code_context>

<specifics>
## Specific Ideas

- User's own words on PARSE_ERROR traceability: "Und dann kann man vielleicht einfach den CSV-String da reinsetzen, sodass man dass man irgendwie nachvollziehen kann, wo der Fehler möglicherweise liegt in der Datei." — directly drove D-11.
- User confirmed `saubere_filmliste.txt` is their real list but explicitly said not to design around converting it now — it can be converted later if needed (D-17).
- User's semicolon-in-title edge case question directly drove D-16's "accept as known limitation" framing, keeping the legacy parser simple rather than adding quoting to two formats.

</specifics>

<deferred>
## Deferred Ideas

- **CSV export** (the other half of the 2026-08-24 todo, "and matching CSV export") — explicitly out of scope for this phase. Requirements.md already tracks this separately as v2-candidate **SET-05**. This phase's CSV *import* column choices (D-12–D-16) should stay export-compatible in spirit (comma-delimited, quotable), but no export work happens here.
- **CSV import as a structured multi-field format** (more columns than Title/OriginalTitle/Year) — REQUIREMENTS.md's Out-of-Scope table already excludes this from v1.1 ("Bestehende Filmliste ist reines Titel+Jahr-Textformat; CSV-Import bleibt v2-Kandidat (SET-06)"). This phase's CSV support (D-12) stays within the existing 3-column schema — it changes the *delimiter/quoting*, not the *column set*.

### Reviewed Todos (not folded)
- **`.planning/todos/pending/2026-08-27-authorizationdeniedexception-on-sse-emitter-complete.md`** — matched by file overlap (`BulkImportController.java` listed as an affected file) but the actual bug is in the wiki-reload SSE completion path (Phase 14 scope), independent per ROADMAP.md ("independent of Phase 14's wiki-enrichment work, no shared files" — confirmed: the SSE issue is `WikiReloadController`/`WikiReloadProgressService`, bulk-import's own SSE progress endpoint isn't implicated). Not folded — stays a separate todo for a wiki-reload-focused phase or hotfix.
- **`.planning/todos/pending/2026-08-27-distinguish-stopped-vs-completed-in-progress-ui.md`** — wiki-reload progress UI only (`WikiReloadProgressService`, `settings.vue`), unrelated to bulk-import's batch-detail page. Not folded.
- **`.planning/todos/pending/2026-08-28-create-api-contract-doc-for-future-flutter-port.md`** — project-wide documentation todo, not phase-specific. Not folded.

</deferred>

---

*Phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv*
*Context gathered: 2026-08-28*
