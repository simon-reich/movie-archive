# Phase 15: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing - Research

**Researched:** 2026-08-28
**Domain:** Nuxt 3 (Vue 3/Pinia) batch-report UI extension + Spring Boot 3 CSV parsing/REST extension, on an existing bulk-import feature (Phases 10-11)
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**View toggle**
- **D-01:** Default view on `/imports/{batchId}` is the current poster-grid; list is the new opt-in view. — Reversibility: reversible
- **D-02:** View-mode preference persists in `localStorage` (per-browser), not a backend setting. — Reversibility: reversible
- **D-03:** List view shows a small thumbnail + text per row (not text-only, not full-size posters). — Reversibility: reversible
- **D-04:** List view reuses the exact same status vocabulary as grid (the `CheckCircle2`/`XCircle` icons and `statusLabel()` mapping in `frontend/pages/imports/[batchId].vue`), just laid out inline per row instead of as an image overlay. — Reversibility: reversible

**Movie links for SAVED lines**
- **D-05:** The entire SAVED card is clickable, linking to `/movies/{movieId}` — not a small icon/link within the card. — Reversibility: reversible
- **D-06:** `movieId` is resolved server-side at response time in `BulkImportController.getBatchDetail()`, via the existing `MovieRepository.findByUserIdAndTmdbId(userId, tmdbId)` — no schema change, no new column, no migration. `BulkImportLineResult` gains a nullable `movieId` field. — Reversibility: reversible
- **D-07:** AMBIGUOUS/NOT_FOUND/PARSE_ERROR cards get no movie link — resolution for AMBIGUOUS/NOT_FOUND is handled entirely by the new inline-resolve widget; PARSE_ERROR gets no resolve action at all. — Reversibility: reversible

**Inline ambiguous/not-found resolution**
- **D-08:** BulkImportService never persists TMDB candidates for AMBIGUOUS lines — only the AMBIGUOUS status is upserted, candidates are discarded. Inline resolve therefore does a **fresh TMDB search on expand**, prefilled with the line's title (and year if present), via the existing `/movies/search` endpoint — no reuse of stale candidates, no new server-side matcher endpoint. — Reversibility: reversible
- **D-09:** After the user picks a candidate and saves inline, the batch-detail page **refetches the full batch** (`GET /movies/bulk-import/batches/{batchId}`) rather than optimistically patching local state. — Reversibility: reversible
- **D-10:** Inline resolve uses a **new endpoint** (e.g. `POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve`) that both (a) saves the movie via the existing `MovieService.initiate()` path and (b) updates that specific `BulkImportLine` row's `status` → `SAVED`, `tmdbId`, and `posterPath`. Plain reuse of `/movies/save` was explicitly rejected because it would leave the line stuck at AMBIGUOUS forever in the batch report. — Reversibility: costly — rationale: downstream agents/tests should treat this as a real new API surface (ownership check on both batchId and lineId, same pattern as `loadOwnedBatch()`), not a trivial reuse of `/movies/save`.
- **D-11:** **PARSE_ERROR is its own separate category**, visually/structurally distinct from AMBIGUOUS/NOT_FOUND — not just another failure-status card with the same treatment. No inline resolve widget. Instead, PARSE_ERROR cards must display the **raw line text** (`BulkImportLine.rawLine`, already persisted server-side per line — needs adding to `BulkImportLineResult`/`BulkImportBatchDetail` API response, not currently exposed) so the user can trace exactly which line in their source file failed and why. — Reversibility: reversible

**Real CSV parsing & format**
- **D-12:** Both formats are supported going forward — the parser must auto-detect/accept **both** the legacy semicolon format (`Title;OriginalTitle;Year`) and real comma-delimited CSV (with quoted fields, e.g. `"Title, Part 2",OriginalTitle,Year`). The legacy format is NOT replaced or deprecated. — Reversibility: reversible — rationale: purely additive parsing capability.
- **D-13:** Use **Apache Commons CSV** for real comma/quote parsing — not a hand-rolled extension of `ImportLineParser`'s manual `split(";")` logic. Not yet a dependency in `backend/build.gradle.kts` — must be added. — Reversibility: reversible
- **D-14:** The parser supports an **optional header row** — auto-detected (e.g., if the first row's "Year" column doesn't parse as an integer, treat row 1 as a header and skip it) rather than requiring an explicit flag or fixed first-line convention. — Reversibility: reversible
- **D-15:** A title containing a comma is handled correctly by standard CSV quoting once Commons CSV parsing is in place — this works out of the box, no special-casing needed.
- **D-16:** A title containing a literal semicolon in the **legacy semicolon format** is an accepted, documented limitation — NOT special-cased. Such a line will misparse into extra fields and land as PARSE_ERROR — diagnosable via D-11's raw-line display. — Reversibility: reversible
- **D-17:** `saubere_filmliste.txt` (untracked, 1139 lines, repo root, semicolon-format) is the user's real archive list and a legitimate real-world test input for D-12 — but needs no conversion or special handling.

### Claude's Discretion
- Exact visual treatment distinguishing PARSE_ERROR cards from AMBIGUOUS/NOT_FOUND (D-11) — e.g. a distinct badge color, a separate section/grouping, or different card styling — left to implementation judgment as long as it reads as a clearly separate category, not just another status icon.
- Exact CSV delimiter auto-detection strategy (D-12) — e.g. sniff first line for `,` vs `;` presence, or try comma first and fall back to semicolon on parse failure — left to research/planning, as long as both formats keep working without the user having to declare which one they're uploading. **Research recommendation below.**

### Deferred Ideas (OUT OF SCOPE)
- CSV export (the other half of the 2026-08-24 todo) — tracked separately as v2-candidate **SET-05**. Not this phase.
- CSV import as a structured multi-field format (more columns than Title/OriginalTitle/Year) — excluded from v1.1 per REQUIREMENTS.md, stays v2-candidate **SET-06**. This phase only changes delimiter/quoting within the existing 3-column schema.
- `2026-08-27-authorizationdeniedexception-on-sse-emitter-complete.md` — wiki-reload SSE completion bug, unrelated.
- `2026-08-27-distinguish-stopped-vs-completed-in-progress-ui.md` — wiki-reload progress UI, unrelated.
- `2026-08-28-create-api-contract-doc-for-future-flutter-port.md` — project-wide doc todo, not phase-specific.
</user_constraints>

<phase_requirements>
## Phase Requirements

No formal `REQ-XX` IDs exist for this phase in REQUIREMENTS.md — it folds two ad-hoc todos and the deferred `SET-06` backlog item forward into v1.1's closing phase (per phase description and STATE.md "Phase 15 added"). The operative requirement set is CONTEXT.md's D-01–D-17 decisions. Traceability against the closest formal backlog anchors:

| ID | Description | Research Support |
|----|-------------|------------------|
| SET-06 (backlog, REQUIREMENTS.md Out-of-Scope table, "CSV-Import bleibt v2-Kandidat") | CSV import with real comma-delimited/quoted parsing, pulled forward into v1.1 per this phase's todo | Standard Stack (Apache Commons CSV), Architecture Patterns (format-detection design), Code Examples |
| IMPORT-V2-01 (Future Requirements) | "Manuelle Auflösung mehrdeutiger Treffer direkt in der Ergebnisübersicht" (inline ambiguous resolution) | D-08–D-11 research, new resolve-endpoint design, existing search-and-save widget pattern in `add.vue` |
| 2026-08-25 todo (view toggle, movie links, inline resolve) | Batch-detail page completion | Don't Hand-Roll (`ViewToggle.vue` reuse), Architecture Patterns, Pitfalls (localStorage vs `useCookie`) |
| 2026-08-24 todo, import half only | Real CSV parsing, backward-compatible with legacy format | Standard Stack, Architecture Patterns, Common Pitfalls |
</phase_requirements>

## Summary

This phase extends two already-shipped, well-tested subsystems rather than building anything greenfield: the Phase 11 batch-detail page (`frontend/pages/imports/[batchId].vue`) and the Phase 10 bulk-import backend (`ImportLineParser`/`BulkImportService`/`BulkImportController`). Both subsystems have clear seams to extend, and — critically — this codebase already has directly reusable components for two of the three UI asks: `frontend/components/ViewToggle.vue` (grid/list toggle button pair, `lucide-vue-next` `Grid`/`List` icons) is used today on `/search`, and the search-and-save widget pattern in `frontend/pages/add.vue` (`searchTmdb()` → poster grid → click-to-save → status overlay) is the exact interaction model D-08 asks the inline-resolve widget to follow.

The single highest-value finding from this research is a **direct conflict between locked decision D-02 (persist view mode in `localStorage`) and an existing, deliberate fix already shipped in this exact codebase**: `frontend/stores/search.ts` implements the *identical* grid/list view-mode toggle using `useCookie`, with an inline comment explaining that `localStorage` was tried first and reverted because it caused a hydration mismatch on page reload (Nuxt 3 runs with SSR enabled, no `ssr: false` override in `nuxt.config.ts`). This is documented as a full Pitfall below with a recommended mitigation, since D-02 is marked reversible in CONTEXT.md.

The second load-bearing finding is a **gap in CONTEXT.md's DTO field enumeration**: `BulkImportLineResult` currently exposes no `id` field for a line at all (verified by reading the DTO). D-10's new resolve endpoint is path-scoped as `.../lines/{lineId}/resolve`, so the frontend needs a line identifier to call it — CONTEXT.md only calls out adding `movieId` (D-06) and `rawLine` (D-11), but a `line.id` (the `BulkImportLine` entity's UUID primary key) must also be added to the response for AMBIGUOUS/NOT_FOUND lines, or the inline-resolve widget has no way to address its target line.

For CSV parsing, Apache Commons CSV 1.14.1 (confirmed current on Maven Central) is not yet a build.gradle.kts dependency and must be added. `CSVFormat.DEFAULT` already uses comma delimiter + double-quote quoting out of the box (satisfies D-15 with zero configuration). The recommended integration keeps the existing per-line pipeline (pacing, per-line failure isolation, `ParsedLine` contract) completely intact: detect format once per file (comma-sniff on the first non-blank line), then dispatch each already-newline-split raw line to either the existing `ImportLineParser.parse()` (semicolon) or a new sibling `ImportLineParser.parseCsv()` (Commons CSV, single-record-per-line) — both returning the same `ParsedLine` record, so `BulkImportService` needs zero changes downstream of parsing.

**Primary recommendation:** Extend, don't rebuild — reuse `ViewToggle.vue` (component), the `add.vue` search-and-save interaction pattern (UX model), and `ImportLineParser`'s `ParsedLine` contract (parsing seam) as the three load-bearing reuse points; treat the `localStorage`-vs-`useCookie` conflict and the missing `line.id` field as must-resolve items before/during planning.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| View-mode toggle (grid/list) | Browser / Client | Frontend Server (SSR) | Pure UI state; persistence choice (`localStorage` vs cookie) determines whether SSR tier is involved at all — see Pitfall 1 |
| Movie link resolution (`movieId` for SAVED lines) | API / Backend | — | `MovieRepository.findByUserIdAndTmdbId()` query, response-time join — no new persistence |
| Inline ambiguous/not-found resolution (search) | API / Backend | Browser / Client | Backend: fresh TMDB search via existing `/movies/search`; Client: search-and-pick widget UI |
| Inline resolve (save + line-status update) | API / Backend | — | New endpoint combines `MovieService.initiate()` + `BulkImportLine` row update in one transaction |
| CSV/legacy format detection & parsing | API / Backend | — | Pure parsing logic (`ImportLineParser`), no client-side parsing — file is uploaded raw and parsed server-side exactly as today |
| Raw-line display for PARSE_ERROR | API / Backend | Browser / Client | Backend: expose already-persisted `rawLine` field; Client: render it verbatim |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Apache Commons CSV | 1.14.1 [CITED: https://central.sonatype.com/artifact/org.apache.commons/commons-csv] | Real comma-delimited CSV parsing with RFC4180-style quoting | Locked by D-13; official Apache Commons project, in continuous use since 2003, the de facto standard CSV library in the Java/Spring ecosystem — confirmed NOT currently present in `backend/build.gradle.kts` [VERIFIED: backend/build.gradle.kts:1-90 — full `dependencies { ... }` block read, no `commons-csv` or any CSV-related artifact present] |

No new frontend dependency is needed: `localStorage` (if kept per D-02) and the SSR-safe `useCookie` alternative are both native/built-in (Nuxt composable), and `lucide-vue-next` (for `Grid`/`List`/`CheckCircle2`/`XCircle` icons) is already a dependency, confirmed in use by `ViewToggle.vue` and `[batchId].vue` [VERIFIED: frontend/components/ViewToggle.vue:2 — `import { Grid, List } from 'lucide-vue-next'`; frontend/pages/imports/[batchId].vue:3 — `import { CheckCircle2, XCircle } from 'lucide-vue-next'`].

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Apache Commons CSV | OpenCSV | Not locked in by D-13 (user explicitly named Commons CSV, "as suggested in the source todo"); OpenCSV has a more permissive but less-audited annotation-based mapping API not needed for this 3-column use case |
| `localStorage` for view-mode (D-02) | `useCookie` (already the established in-repo pattern for the identical feature) | See Pitfall 1 — this is the single most important open tension in this research |

**Installation:**
```kotlin
// backend/build.gradle.kts, inside dependencies { ... }
implementation("org.apache.commons:commons-csv:1.14.1")
```

**Version verification:** Apache Commons CSV 1.14.1 confirmed as the current release via Maven Central artifact page [CITED: https://central.sonatype.com/artifact/org.apache.commons/commons-csv] (fetched directly, cross-checked against WebSearch results referencing the same 1.14.1 identifier — MEDIUM confidence per this project's classify-confidence seam for a cross-checked `websearch`+`webfetch` provider pair).

## Package Legitimacy Audit

> This phase's only new external dependency is a Maven/Java artifact (`org.apache.commons:commons-csv`), not an npm/PyPI/crates package — the `gsd_run query package-legitimacy check` seam does not cover the Maven ecosystem. Manual diligence performed instead:

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `org.apache.commons:commons-csv` | Maven Central | ~13 years (first released 2013 per Apache Commons CSV project history; Commons project itself since 2003) | Top-20 most-depended-on Java library ecosystem-wide (Apache Commons family); no registry download counter on Maven Central, but ubiquity confirmed via widespread transitive use across the Spring ecosystem | `github.com/apache/commons-csv` [CITED: https://github.com/apache/commons-csv, found via WebSearch] | OK (manual) | Approved |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none — Apache Commons is an ASF top-level project; no legitimacy concern.

## Architecture Patterns

### System Architecture Diagram

```
                         ┌─────────────────────────────────────────┐
                         │  Browser: /imports/{batchId}.vue          │
                         │                                           │
  GET .../batches/{id}   │  ┌──────────┐   ┌───────────────────┐    │
  ◄──────────────────────┤  │ViewToggle│──▶│ grid | list render │    │
                         │  └──────────┘   │ (D-01..D-04)       │    │
                         │                 └───────────────────┘    │
                         │  SAVED card ──▶ NuxtLink /movies/{id}    │
                         │  (D-05, movieId from response)           │
                         │                                           │
                         │  AMBIGUOUS/NOT_FOUND card                │
                         │   ├─ expand ──▶ GET /movies/search?q=..  │
                         │   │             (fresh search, D-08)     │
                         │   └─ pick ────▶ POST .../lines/{id}/     │
                         │                 resolve (D-10)           │
                         │                 └─▶ on success: refetch  │
                         │                     batch detail (D-09)  │
                         │                                           │
                         │  PARSE_ERROR card ──▶ shows rawLine text  │
                         │   (no resolve widget, D-11)               │
                         └─────────────────────┬─────────────────────┘
                                                │
                                                ▼
                         ┌─────────────────────────────────────────┐
                         │  BulkImportController (Spring Boot)      │
                         │                                           │
                         │  POST /movies/bulk-import                │
                         │   ├─ read file bytes → List<String> lines │
                         │   ├─ NEW: detect format (comma sniff)    │
                         │   ├─ NEW: strip header row if detected   │
                         │   └─ dispatch to BulkImportService        │
                         │       .runImport() (async, unchanged)     │
                         │                                           │
                         │  GET .../batches/{id}                    │
                         │   └─ maps BulkImportLine → LineResult     │
                         │      + NEW id, movieId, rawLine fields    │
                         │                                           │
                         │  NEW POST .../lines/{lineId}/resolve      │
                         │   ├─ loadOwnedBatch() + line ownership    │
                         │   ├─ MovieService.initiate(tmdbId)        │
                         │   ├─ update BulkImportLine → SAVED        │
                         │   └─ enrichmentService.enrich() if new    │
                         └─────────────────────┬─────────────────────┘
                                                │
                                                ▼
                         ┌─────────────────────────────────────────┐
                         │  ImportLineParser                        │
                         │   parse(line)     → legacy ";" (D-16)    │
                         │   NEW parseCsv(line) → Commons CSV (D-13)│
                         │   both return the same ParsedLine record │
                         └───────────────────────────────────────────┘
```

### Recommended Project Structure

No new files/folders — all changes are additions/extensions to existing files:
```
backend/src/main/java/de/moviearchive/bulkimport/
├── ImportLineParser.java          # add parseCsv() sibling method
├── BulkImportService.java         # add resolveLine() method (new)
├── BulkImportController.java      # add format-detection + resolve endpoint
├── BulkImportLineRepository.java  # add findByIdAndBatchId() ownership query
└── dto/
    ├── BulkImportLineResult.java  # add id, movieId, rawLine fields
    └── (new) ResolveLineRequest.java  # tmdbId + posterPath

frontend/pages/imports/[batchId].vue  # view toggle, movie links, inline resolve
frontend/composables/useBulkImport.ts # extend types + resolveLine() call
```

### Pattern 1: File-level format detection, line-level dispatch (recommended for D-12/D-13/D-14)

**What:** Detect CSV-vs-legacy-semicolon once per uploaded file (not per line), by sniffing the first non-blank raw line for a raw comma character. If present, treat the whole file as CSV; otherwise legacy semicolon. This is a straightforward implementation of CONTEXT.md's stated discretion option ("sniff first line for `,` vs `;` presence").

**When to use:** In `BulkImportController.uploadBulkImport()`, immediately after `rawLines` is built from `file.getBytes()` and before `createBatch()`/`runImport()` dispatch — so the batch's `totalLines` count and the header-skip both happen before any async work starts.

**Why file-level, not line-level:** The existing pipeline processes `List<String> rawLines` one line at a time for pacing (`Thread.sleep(pacingDelayMs)` between lines) and per-line failure isolation (D-03, Phase 10) — this must not change. Per-line format auto-detection would risk a file with one comma-containing title (D-15's example: `"Title, Part 2"`) being misdetected line-by-line inconsistently. A single file-level decision, made once, keeps the existing per-line loop in `BulkImportService.runImport()` completely untouched — `ImportLineParser.parse()` vs `.parseCsv()` is selected once per batch and passed down.

**Example:**
```java
// Source: derived from ImportLineParser.java (read this session) + Commons CSV apidocs
// [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java:27-58]
// New sibling method — Commons CSV per-line, returns the SAME ParsedLine contract.
public ParsedLine parseCsv(String rawLine) {
    String trimmed = rawLine.trim();
    if (trimmed.isEmpty()) {
        return null;
    }
    try (CSVParser parser = CSVParser.parse(trimmed, CSVFormat.DEFAULT)) {
        List<CSVRecord> records = parser.getRecords();
        if (records.size() != 1 || records.get(0).size() != 3) {
            return new ParsedLine(trimmed, null, null, null, false);
        }
        CSVRecord record = records.get(0);
        String title = record.get(0).trim();
        String originalTitleRaw = record.get(1).trim();
        String originalTitle = originalTitleRaw.isEmpty() ? null : originalTitleRaw;
        if (title.isEmpty()) {
            return new ParsedLine(trimmed, null, originalTitle, null, false);
        }
        try {
            Integer year = Integer.parseInt(record.get(2).trim());
            return new ParsedLine(trimmed, title, originalTitle, year, true);
        } catch (NumberFormatException e) {
            return new ParsedLine(trimmed, title, originalTitle, null, false);
        }
    } catch (IOException e) {
        return new ParsedLine(trimmed, null, null, null, false);
    }
}
```
Format-detection + header-skip, in the controller:
```java
// Source: derived from BulkImportController.java:88-98 (read this session)
List<String> rawLines = new String(file.getBytes(), StandardCharsets.UTF_8).lines().toList();
boolean isCsvFormat = rawLines.stream()
        .map(String::trim)
        .filter(l -> !l.isEmpty())
        .findFirst()
        .map(first -> first.contains(","))
        .orElse(false);

if (isCsvFormat && !rawLines.isEmpty()) {
    // D-14: header auto-detect — only meaningful for the CSV path (see Open Questions)
    ImportLineParser.ParsedLine firstParsed = importLineParser.parseCsv(rawLines.get(0));
    if (firstParsed != null && !firstParsed.valid() /* year didn't parse */) {
        rawLines = rawLines.subList(1, rawLines.size()); // drop header row before batch creation
    }
}
```

### Pattern 2: Reuse the `add.vue` search-and-save widget model for inline resolve (D-08)

**What:** The inline-resolve widget (AMBIGUOUS/NOT_FOUND cards) should follow the exact interaction shape already proven in `frontend/pages/add.vue`: text query pre-filled from the line's title/year → `searchTmdb(query)` (existing `/movies/search` endpoint, `useMovies()` composable) → render a small poster grid of candidates → click to pick → call the new resolve endpoint → show a pending/success/error overlay state, matching `PosterState` (`idle`/`pending`/`success`/`error`/`saved`) already defined in `useMovies.ts`.

**When to use:** Building the expand/search/pick UI inside each AMBIGUOUS/NOT_FOUND card on `/imports/{batchId}`.

**Example (existing pattern to model against — not new code):**
```ts
// Source: frontend/pages/add.vue:34-68 (read this session) — the proven pattern
async function handleSearch() {
  const items = await searchTmdb(query.value.trim())
  results.value = items.map(item => ({ ...item, state: 'idle' as const }))
}
async function handlePosterClick(item: SearchResultItem) {
  item.state = 'pending'
  const { id } = await saveMovie(item.tmdbId) // ← replace with resolveLine(batchId, lineId, item)
  item.state = 'success'
}
```

### Pattern 3: `MovieController.saveMovie()`'s transaction-then-enrich sequencing, applied to the new resolve endpoint (D-10)

**What:** `MovieController.saveMovie()` calls `movieService.initiate()` (a `@Transactional` method that commits before returning) and only *then* calls `enrichmentService.enrich(result.id())` from the controller, outside any transaction [VERIFIED: backend/src/main/java/de/moviearchive/movie/MovieController.java:33-42 — `MovieInitiateResult result = movieService.initiate(...); if (result.isNew()) { enrichmentService.enrich(result.id()); }`]. This avoids the exact race `BulkImportService`'s CR-01 fix addresses (calling `enrich()` from inside a still-open transaction races the not-yet-committed `Movie` INSERT under READ COMMITTED isolation) [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:202-213 — CR-01 javadoc].

**When to use:** The new resolve endpoint is a synchronous REST call (not `@Async`), so it does **not** need `BulkImportService`'s `@Lazy self`-proxy trick — that trick exists specifically to make `@Transactional` apply across `@Async`-invoked per-item calls. For resolve, wrap steps (a) `movieService.initiate()` and (b) the `BulkImportLine` row update in **one** `@Transactional` service method; call `enrichmentService.enrich()` **after** that method returns, from the controller — mirroring `MovieController.saveMovie()` exactly.

### Anti-Patterns to Avoid
- **Re-deriving CSV parsing logic inside `BulkImportService`:** Keep all format-specific logic inside `ImportLineParser` (both `parse()` and the new `parseCsv()`), returning the same `ParsedLine` record. `BulkImportService.processLine()` must not need to know which format produced a given `ParsedLine`.
- **Calling `enrichmentService.enrich()` from inside the same `@Transactional` method that calls `MovieService.initiate()`:** This is the exact bug CR-01 already fixed once in this codebase (see Pattern 3) — do not reintroduce it in the new resolve endpoint.
- **Reusing `POST /movies/save` for inline resolve:** Explicitly rejected by D-10 — it has no way to update the originating `BulkImportLine` row, so the line stays AMBIGUOUS forever in the batch report even after the movie is saved.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Grid/list view toggle button | A new toggle component | `frontend/components/ViewToggle.vue` [VERIFIED: frontend/components/ViewToggle.vue:1-43 — full file read, `modelValue: 'grid' \| 'list'` prop, `update:modelValue` emit, `Grid`/`List` icons from `lucide-vue-next`] | Already built, already styled to match this app's design system, already used identically on `/search` (`frontend/pages/search.vue:44-47`) |
| CSV comma/quote parsing | Manual `split(",")` with quote-aware regex | Apache Commons CSV (`CSVFormat.DEFAULT`) | RFC4180 quoting/escaping edge cases (embedded commas, embedded quotes via `""`, leading/trailing whitespace) are exactly what D-13 locks in Commons CSV to avoid re-solving by hand |
| Search-and-pick UI for inline resolve | A new search widget from scratch | The `add.vue` poster-grid search pattern (Pattern 2 above) | Already handles the exact `idle → pending → success/error` state machine this feature needs, using the same `useMovies()` composable and `TmdbSearchResult` type |

**Key insight:** This phase is almost entirely "wire an existing, working sub-pattern into a new location" rather than new design — the risk is not in the individual pieces (all proven) but in the seams: the DTO fields that need adding (`id`, `movieId`, `rawLine`), and the persistence-mechanism choice for view-mode (see Pitfall 1).

## Common Pitfalls

### Pitfall 1: `localStorage` for view-mode conflicts with an existing, deliberate fix in this codebase (D-02)
**What goes wrong:** Reading/writing `localStorage` at Vue `<script setup>` top-level (outside `onMounted()`) throws or silently returns `undefined` during Nuxt's server-side render pass, because `localStorage` does not exist in the Node.js SSR environment. Even guarded behind `onMounted()`, the *first* server-rendered paint will show the default view mode, then flip to the persisted mode once the client mounts and reads `localStorage` — a visible flash/hydration mismatch on reload.
**Why it happens:** This app runs Nuxt 3 with SSR enabled (no `ssr: false` in `nuxt.config.ts` [VERIFIED: frontend/nuxt.config.ts:1-30 — full file read, no `ssr` key present, meaning default universal/SSR rendering]). This exact scenario — a grid/list view-mode toggle — was already built once in this codebase using `localStorage` and was deliberately reverted to `useCookie` [VERIFIED: frontend/stores/search.ts:1-19 — quoted verbatim: `"// useCookie is SSR-safe — unlike localStorage which is unavailable on the server. // On SSR the cookie value comes from the request headers; on client it's document.cookie. // This fixes the hydration mismatch that caused viewMode to reset on page reload."`].
**How to avoid:** Since D-02 is marked **reversible** in CONTEXT.md, flag this conflict explicitly to the user/planner before implementation. Two viable paths: (1) implement D-02 as literally specified (`localStorage`), reading/writing only inside `onMounted()`/a client-only watcher, and accept the one-frame hydration flash as a known, low-severity cosmetic tradeoff (unlike `search.ts`'s case, this view toggle is on a low-traffic one-off batch-report page, not the main search results page, so the impact is smaller); or (2) reuse the exact `useCookie` pattern from `frontend/stores/search.ts` for consistency and to avoid re-encountering the bug that was already found and fixed once. This research does not override the locked decision — it surfaces the tradeoff for `/gsd-plan-phase` or a follow-up user confirmation.
**Warning signs:** A Vitest/`@vue/test-utils` test for the batch-detail page that asserts the default view mode on initial mount, combined with a manual browser reload test — if the toggle position flickers or resets unexpectedly on reload, this is the mismatch.

### Pitfall 2: `BulkImportLineResult` currently has no `id` field — the resolve endpoint's path variable has nothing to bind to
**What goes wrong:** The frontend cannot call `POST .../lines/{lineId}/resolve` (D-10) without a line identifier. CONTEXT.md's Integration Points section calls out adding `movieId` (D-06) and `rawLine` (D-11) to `BulkImportLineResult`, but does not call out adding the line's own `id`.
**Why it happens:** `BulkImportLineResult` was designed in Phase 11 as a pure read-only reporting DTO with no need for a client-addressable identifier [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java:1-15 — full file: `record BulkImportLineResult(String title, String originalTitle, Integer year, String status, String posterPath)` — no `id` field]. Phase 15 is the first phase where the frontend needs to address an individual line.
**How to avoid:** Add `id` (the `BulkImportLine` entity's UUID primary key, already exists as `@Id private UUID id;` [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java:19-21]) to `BulkImportLineResult` alongside `movieId` and `rawLine`. Also update the `:key` binding in `[batchId].vue`, which currently uses the fragile composite `` `${line.title}-${line.year}` `` [VERIFIED: frontend/pages/imports/[batchId].vue:99 — `:key="\`${line.title}-${line.year}\`"`] — switch to `line.id` once available, which is also a correctness fix (two lines with the same title/year but different status would otherwise collide as Vue keys).
**Warning signs:** Any attempt to plan the resolve-endpoint frontend call without first confirming a `lineId` is available on the object being rendered.

### Pitfall 3: Real CSV vs. legacy semicolon format is ambiguous for European/German-locale Excel exports
**What goes wrong:** Excel/Numbers in many European locales (including German — relevant since this project's own todos and CONTEXT.md quotes are in German) exports "CSV" files using **semicolon** as the field separator by default, not comma, while still using proper RFC4180 quoting for fields containing the separator. Under the file-level "does the first line contain a comma" detection heuristic (Pattern 1), such a file would be misdetected as legacy-format (no comma present) and parsed with the unquoting-unaware `split(";", -1)` legacy parser — silently mishandling any quoted field.
**Why it happens:** D-12's locked scope defines "real CSV" strictly as comma-delimited, and the legacy format is strictly semicolon-delimited-without-quoting — this is a deliberate simplification that resolves the ambiguity by definition, not an oversight, but it means a German-locale Excel CSV export (semicolon + quoting) is **not** one of the two supported formats.
**How to avoid:** This is a known, accepted scope boundary per the locked decisions (not a bug to fix) — but call it out explicitly during planning/UAT so it doesn't surface as a surprise "why didn't my Excel export work" bug report later, the same way the original strict-semicolon-only format surfaced as `.planning/debug/bulk-import-not-adding-movies.md` [VERIFIED: .planning/debug/bulk-import-not-adding-movies.md — root_cause: "The Bulk Import UI provides no guidance on the required file format... causing... 100% of lines ending as PARSE_ERROR"]. Recommend the format-guidance text on `add.vue`'s Bulk Import section (already exists per that debug doc's fix) be updated to explicitly say "comma-separated CSV" so a semicolon-locale Excel export isn't assumed to be supported.
**Warning signs:** A PARSE_ERROR-heavy batch from a user-uploaded file that looks correctly quoted but uses semicolons.

### Pitfall 4: CSV parsing per-line breaks on a title containing a literal embedded newline inside quotes
**What goes wrong:** True RFC4180 CSV allows a quoted field to contain a literal newline character, spanning multiple physical lines for one logical record. The recommended integration (Pattern 1) splits the file into `List<String>` via `.lines()` **before** CSV-parsing each line individually, which would break such a field — the newline would end the "line" before the closing quote is seen.
**Why it happens:** Preserving the existing per-line pipeline (pacing, per-line failure isolation) requires a raw-line list as the unit of work; true multi-line CSV records don't fit that model without a bigger rewrite (parsing the whole file as one `CSVParser` pass first, then mapping records back to something with the same failure-isolation/pacing granularity — a materially larger change, out of scope per D-12's "purely additive" framing).
**How to avoid:** Accept as a known, documented limitation — analogous in spirit to D-16's accepted semicolon-in-legacy-title limitation. Movie titles containing a literal embedded newline are effectively unheard of in practice, so this is a low-risk simplification. Document it in the format-guidance text near the file upload control if the planner wants explicit UAT coverage.
**Warning signs:** None expected in practice — flagged for completeness since it's a real gap versus the RFC4180 spec Commons CSV otherwise fully implements.

### Pitfall 5: `MultipartFile` temp storage and SSE emitter timeout — pre-existing pitfalls this phase's new endpoint must not reintroduce
**What goes wrong:** Two pitfalls already documented and fixed in this exact controller: (1) `MultipartFile`'s backing temp storage is cleared once the HTTP request completes, so file bytes must be read synchronously in the request thread [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:88-91 — comment: "MUST read the file's content synchronously, in the request thread — MultipartFile's backing temp storage is cleared once the HTTP request completes"]; (2) `SseEmitter(Long.MAX_VALUE)` is used deliberately to avoid the container's default async timeout for long-running imports [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:126-129].
**Why it relates to this phase:** The new resolve endpoint is a plain synchronous POST (no file upload, no SSE) so neither pitfall applies directly to it — but it's worth confirming during planning that no part of the new endpoint's design accidentally reintroduces async/streaming semantics that would need the same treatment.
**How to avoid:** Design the resolve endpoint as a plain synchronous request/response (matching `MovieController.saveMovie()`, not `BulkImportController.progress()`).

## Code Examples

### Existing `ImportLineParser.parse()` contract (unchanged, D-16) — the shape `parseCsv()` must match
```java
// Source: backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java:27-58 (read this session, verbatim)
public ParsedLine parse(String rawLine) {
    String trimmed = rawLine.trim();
    if (trimmed.isEmpty()) {
        return null;
    }
    String[] fields = trimmed.split(";", -1);
    if (fields.length != 3) {
        return new ParsedLine(trimmed, null, null, null, false);
    }
    // ... title/originalTitle/year extraction, unchanged ...
}
public record ParsedLine(String rawLine, String title, String originalTitle, Integer year, boolean valid) {}
```

### Existing movie-link routing pattern to reuse for D-05
```html
<!-- Source: frontend/pages/index.vue:65, frontend/components/MovieCard.vue:26 (read this session) -->
<NuxtLink :to="`/movies/${movie.id}`" class="block">...</NuxtLink>
```
Movie detail page confirmed at `frontend/pages/movies/[id].vue` [VERIFIED: file listing — `find frontend/pages -type d` output confirms `frontend/pages/movies/[id].vue` exists], so `/movies/{movieId}` is the correct route for D-05.

### `MovieRepository.findByUserIdAndTmdbId()` — the exact query D-06 reuses
```java
// Source: backend/src/main/java/de/moviearchive/movie/MovieRepository.java:19 (read this session, verbatim)
Optional<Movie> findByUserIdAndTmdbId(UUID userId, Integer tmdbId);
```

### `BulkImportLineStatus` enum — the exact 4 values this phase's status handling must branch on
```java
// Source: backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineStatus.java:3-8 (read this session, verbatim)
public enum BulkImportLineStatus {
    SAVED,
    AMBIGUOUS,
    NOT_FOUND,
    PARSE_ERROR
}
```

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Header-row auto-detection (D-14) should apply only to the CSV-format path, not the legacy semicolon path | Architecture Patterns, Pattern 1 | If the user intends header-detection to also apply to legacy semicolon files, a semicolon-format file whose first line happens to look like `Title;;NotAYear` (e.g. a genuinely malformed first data row, not a header) would be silently dropped instead of correctly reported as PARSE_ERROR. Low risk (rare edge case) but worth a one-line confirmation during planning/discuss. |
| A2 | "Sniff first non-blank line for a raw comma" is an acceptable, sufficient CSV-vs-legacy detection heuristic | Architecture Patterns, Pattern 1 | CONTEXT.md explicitly leaves this to research/planning discretion, so this is a recommendation, not a verified requirement. A file whose only comma-bearing field is on a later line (all titles on line 1 happen to be comma-free) would misdetect as legacy format; low risk given D-15's own framing implies at least one comma-containing title is expected to exist somewhere typical in a CSV export, but the *first* line specifically is not guaranteed to contain one. |
| A3 | The new resolve endpoint should be synchronous (not `@Async`) | Architecture Patterns, Pattern 3 | If a future non-functional requirement needs resolve to be paced/backgrounded like bulk import itself, this design would need to change; low risk since it's a single-line save-and-search action, not a batch operation. |

**If this table is empty:** N/A — see above.

## Open Questions

1. **Should D-02's `localStorage` choice be revisited in favor of the existing `useCookie` pattern?**
   - What we know: `localStorage` is locked as reversible in CONTEXT.md; `frontend/stores/search.ts` already solved the identical problem with `useCookie` after hitting a real hydration-mismatch bug with `localStorage`.
   - What's unclear: Whether the user was aware of `search.ts`'s prior fix when making D-02, or considers this a different-enough context (isolated batch-report page vs. main search page) to accept the tradeoff.
   - Recommendation: Surface this explicitly at the start of `/gsd-plan-phase` or a discuss-phase follow-up before implementation; either choice is technically viable (see Pitfall 1).

2. **Does header-row auto-detection (D-14) apply to the legacy semicolon format too, or CSV only?**
   - What we know: D-14's rationale text ("Matches how real CSV exports from Excel/Numbers/Sheets typically look") strongly implies CSV-only scope.
   - What's unclear: CONTEXT.md's decision statement itself doesn't explicitly scope it to one format.
   - Recommendation: Scope to CSV-format files only (per this research's Pattern 1) unless the planner/user confirms otherwise — documented as Assumption A1.

## Environment Availability

No new external services, runtimes, or infra dependencies. The one new dependency (Apache Commons CSV) is a standard Maven Central artifact resolved by the existing Gradle build — no environment probe needed beyond normal `./gradlew build` internet access, which this project's CI/dev environment already requires for every other Spring Boot dependency.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 + AssertJ, Spring Boot Test (MockMvc, `@SpringBootTest`), Testcontainers (`postgres:16-alpine`), WireMock [VERIFIED: backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java:1-80 — `MockMvc`, `AbstractWireMockTest` base class, `@Autowired` repositories confirmed] |
| Backend config file | `backend/build.gradle.kts` (`useJUnitPlatform()`, JaCoCo coverage gate at 75% line coverage) |
| Backend quick run | `cd backend && ./gradlew test --tests "de.moviearchive.bulkimport.*"` |
| Backend full suite | `cd backend && ./gradlew check` |
| Frontend framework | Vitest + `@vue/test-utils` [VERIFIED: frontend/test/unit/pages/imports-batchId.spec.ts:1-5 — `import { describe, it, expect, vi, beforeEach } from 'vitest'`, `import { mount } from '@vue/test-utils'`] |
| Frontend config file | `frontend/package.json` scripts (`"test": "vitest run"`) [VERIFIED: frontend/package.json:11-14 — `"test": "vitest run"`, `"test:coverage": "vitest run --coverage"`, `"test:watch": "vitest"`, `"test:e2e": "playwright test"`] |
| Frontend quick run | `cd frontend && pnpm vitest run test/unit/pages/imports-batchId.spec.ts` |
| Frontend full suite | `cd frontend && pnpm test` |

### Phase Requirements → Test Map
| Decision | Behavior | Test Type | Automated Command | File Exists? |
|----------|----------|-----------|-------------------|-------------|
| D-01–D-04 | View toggle default/persistence/list-view rendering | unit (Vitest) | `pnpm vitest run test/unit/pages/imports-batchId.spec.ts` | ✅ existing file, needs new cases |
| D-05, D-06 | SAVED card links to `/movies/{movieId}`; movieId resolved server-side | unit (Vitest) + integration (MockMvc) | `pnpm vitest run test/unit/pages/imports-batchId.spec.ts` / `./gradlew test --tests "*BulkImportControllerTest"` | ✅ both exist, need new cases |
| D-08–D-10 | Inline resolve: search, pick, save, line-status update | integration (MockMvc + WireMock TMDB stub) | `./gradlew test --tests "*BulkImportControllerTest"` | ✅ existing file, needs new test class or cases for the new endpoint |
| D-11 | PARSE_ERROR shows raw line text | unit (Vitest) | `pnpm vitest run test/unit/pages/imports-batchId.spec.ts` | ✅ existing file, needs new case |
| D-12–D-16 | CSV parsing, format detection, header skip, backward compat | unit (JUnit) | `./gradlew test --tests "*ImportLineParserTest"` | ✅ existing file, needs new `parseCsv()` test cases |
| D-17 | `saubere_filmliste.txt` still imports correctly (regression) | manual/UAT | N/A — real-file UAT pass, not a CI-automatable assertion (per-user local file, not committed as a fixture) | manual only |

### Sampling Rate
- **Per task commit:** relevant unit test class (`ImportLineParserTest`, `imports-batchId.spec.ts`, etc.)
- **Per wave merge:** `./gradlew check` (backend) + `pnpm test` (frontend)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java` — needs new `@Test` cases covering `parseCsv()`: comma-delimited valid line, quoted comma-containing title (D-15), wrong field count, non-numeric year, header-row detection input.
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` — needs new cases for the resolve endpoint (happy path, wrong-user 403, unknown lineId 404/other-batch's lineId rejected) and for `getBatchDetail()`'s new `id`/`movieId`/`rawLine` fields.
- [ ] `frontend/test/unit/pages/imports-batchId.spec.ts` — needs new cases for view toggle persistence, movie-link `NuxtLink` target, inline-resolve widget flow (mocked `useMovies()`/new resolve composable call), PARSE_ERROR raw-line rendering.
- [ ] No new test framework/fixture setup needed — both backend and frontend test infrastructure already fully cover this phase's needs.

*(No framework install gaps: both `JUnit 5 + AssertJ + MockMvc + WireMock` and `Vitest + @vue/test-utils` are already fully configured and exercised by the existing bulk-import test suites.)*

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V4 Access Control | yes | New resolve endpoint MUST replicate `loadOwnedBatch()`'s exact ownership pattern, extended to also verify the `lineId` belongs to the owned `batchId` — not just that *a* line with that id exists somewhere (D-10's explicit rationale: "same pattern as `loadOwnedBatch()`... a real new API surface"). Add `BulkImportLineRepository.findByIdAndBatchId(UUID id, UUID batchId)` and 403/404 on mismatch, mirroring the existing `AccessDeniedException`/`NoSuchElementException` handlers already registered in `BulkImportController` [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:226-236]. |
| V5 Input Validation | yes | CSV parsing must reject malformed input the same way the legacy parser does today (wrong field count, non-numeric year → `PARSE_ERROR`, never an uncaught exception). `record.get(index)` on a `CSVRecord` with fewer than 3 fields must be guarded by a `size()` check first (an unguarded `.get(2)` on a 1-field record throws `ArrayIndexOutOfBoundsException`, which would propagate as an unhandled 500 rather than the existing per-line-isolated `PARSE_ERROR` path). |
| V5 Input Validation (file upload) | yes | Existing `bulk-import.max-lines` cap (`@Value("${bulk-import.max-lines:5000}")`) [VERIFIED: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:61-62] already bounds total line count regardless of format — no new upload-size concern introduced by CSV support, since Commons CSV parses the same already-size-capped `rawLines` list. |
| V2/V3 Auth/Session | no (unchanged) | Resolve endpoint sits behind the same JWT `Authentication` resolution already used by every other `/movies/*` endpoint — no new auth mechanism. |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| IDOR via `lineId` path variable (resolving a line that belongs to another user's batch) | Elevation of Privilege | `findByIdAndBatchId()` scoped query + explicit ownership check before any mutation, matching `loadOwnedBatch()` (D-10) |
| CSV injection (a title/originalTitle field beginning with `=`, `+`, `-`, `@` — a formula-injection risk if this data is ever exported to CSV/opened in Excel) | Tampering | Out of scope for *import* (this phase does not build CSV export — deferred as SET-05); worth a one-line note for whoever eventually builds CSV export, since D-12's column choices are explicitly kept "export-compatible in spirit" |
| Malformed/adversarial CSV causing unbounded memory use (e.g. a single absurdly long unquoted "line" with no line breaks) | Denial of Service | Already mitigated by the pre-existing `bulk-import.max-lines` cap (bounds line *count*, not per-line length) — no new per-line length cap exists today for either format; note as a pre-existing, out-of-scope gap rather than something this phase introduces or must fix |

## Sources

### Primary (HIGH confidence)
- Direct file reads (this session) of: `ImportLineParser.java`, `BulkImportService.java`, `BulkImportController.java`, `BulkImportLine.java`, `BulkImportLineStatus.java`, `BulkImportLineRepository.java`, `BulkImportLineResult.java`, `BulkImportBatchDetail.java`, `MatchedLine.java`, `MovieRepository.java`, `MovieService.java`, `MovieController.java`, `SaveMovieRequest.java`, `TmdbSearchResultItem.java`, `MovieInitiateResult.java`, `backend/build.gradle.kts`, `frontend/pages/imports/[batchId].vue`, `frontend/composables/useBulkImport.ts`, `frontend/composables/useMovies.ts`, `frontend/pages/add.vue`, `frontend/components/ViewToggle.vue`, `frontend/components/MovieListItem.vue`, `frontend/components/MovieCard.vue`, `frontend/pages/search.vue`, `frontend/stores/search.ts`, `frontend/nuxt.config.ts`, `ImportLineParserTest.java`, `imports-batchId.spec.ts`, `frontend/package.json`.
- `.planning/phases/15-.../15-CONTEXT.md`, `15-DISCUSSION-LOG.md`, `.planning/REQUIREMENTS.md`, `.planning/STATE.md`, `.planning/debug/bulk-import-not-adding-movies.md`, the two folded todo files — all read in full this session.

### Secondary (MEDIUM confidence)
- Apache Commons CSV version 1.14.1 and `CSVFormat.DEFAULT` delimiter/quote defaults, `CSVParser`/`CSVFormat.Builder` API shape — [CITED: https://central.sonatype.com/artifact/org.apache.commons/commons-csv] and [CITED: https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.html], cross-checked via WebSearch + WebFetch.

### Tertiary (LOW confidence)
- None — no findings in this research relied solely on unverified training knowledge without either a file read or an official-source citation.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Apache Commons CSV version/API confirmed via official Maven Central + Apache docs; no other new dependencies.
- Architecture: HIGH — every integration point (DTOs, repository methods, controller endpoints, frontend composables/components) was read directly from source this session, not inferred.
- Pitfalls: HIGH — Pitfall 1 (localStorage/SSR) and Pitfall 2 (missing `id` field) are both grounded in direct, verbatim file reads of this exact codebase, not general framework knowledge.

**Research date:** 2026-08-28
**Valid until:** 2026-09-27 (30 days — stable, no fast-moving dependencies; re-verify Apache Commons CSV version if planning is delayed past that window)
