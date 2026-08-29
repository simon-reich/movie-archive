# Phase 10: Bulk Import Engine - Research

**Researched:** 2026-08-23
**Domain:** Spring Boot multipart file upload + async batch job orchestration (TMDB matching, idempotent save reuse)
**Confidence:** HIGH (backend patterns — verified against existing codebase files this session) / MEDIUM (external Spring Boot / TMDB API facts — web-search confirmed against official docs)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Line format & parsing**
- D-01: File format is CSV-style: `Title;OriginalTitle;Year` per line (semicolon-delimited). `OriginalTitle` field may be empty (`Title;;Year`).
- D-02: Lines are trimmed; blank lines are skipped silently. File is read as UTF-8 only.
- D-03: A line that fails to parse (missing/non-numeric year, wrong field count) is recorded with status "parse error" and processing continues to the next line — never aborts the whole batch.
- D-04: The Original Title field (when non-empty) is used for matching, not just informational — see D-06.

**TMDB matching & ambiguity**
- D-05: Year filter is an exact match only — no ±1 tolerance. A candidate's parsed release year must equal the line's year exactly.
- D-06: When multiple year-matching candidates exist and an Original Title was supplied, an exact case-insensitive match against TMDB's `original_title` narrows the candidate set to one → treated as unique, auto-saved. If it doesn't narrow to exactly one, the line is still ambiguous.
- D-07: A line with zero year-matching candidates is recorded as "not found" and processing continues — distinct from "ambiguous" (multiple matches).

**Already-imported dedup (IMPORT-07)**
- D-08: Dedup key is a normalized `(title, year)` pair from the uploaded line — checked against a persisted import record *before* any TMDB call, so re-uploads of already-saved lines never hit TMDB.
- D-09: Persistence: new table `bulk_import_line` (columns: `user_id`, `title`, `original_title`, `year`, `tmdb_id`, `status`) — one row per line ever processed for a user. This is also what Phase 11's results UI will read from (title, poster via `tmdb_id`, status).
- D-10: Skip-on-reupload applies **only** to lines with status `saved`. Lines previously recorded as `ambiguous`, `not_found`, or `parse_error` are retried (including a fresh TMDB call) on every re-upload — matches IMPORT-07's literal wording ("skip already-*imported*"), and avoids permanently trapping a fixable typo or a title not yet on TMDB.

**Job execution & result persistence**
- D-11: Bulk import runs asynchronously: the upload endpoint returns 202 Accepted immediately; processing happens in a background job via a dedicated bounded executor — mirrors `WikiReloadService`/`WikiReloadController`'s pattern (self-proxy for per-item `@Transactional` calls, per-item failure isolation, pacing between TMDB calls, executor-full → 503).
- D-12: A unique-match line is saved by calling the existing `MovieService.initiate(tmdbId)` + `EnrichmentService.enrich()` exactly as `/movies/save` does today — no bulk-specific save path (per IMPORT-03's explicit instruction to reuse existing save/dedup logic).
- D-13: `bulk_import_line` rows are written/updated per-line, live, as each line finishes processing within the job — not buffered to a single end-of-job write. This is what lets Phase 11 implement live progress (IMPORT-05) by simply polling/counting this table; Phase 10 owns building that persistence, Phase 11 only adds UI on top.

### Claude's Discretion
- Exact `bulk_import_line` schema beyond the columns named in D-09 (e.g. timestamps, primary key shape, indexes) — planner's call.
- Whether the uploaded file has a header row / is quotable-CSV (RFC 4180) or plain semicolon-split — not raised during discussion; default to simple semicolon-split, no header, no quoting, unless planner finds a reason otherwise.
- Executor bean naming/pool sizing for the bulk-import background job — follow the `wikiReloadExecutor` sizing pattern in `AsyncConfig` unless there's a reason to differ.

### Deferred Ideas (OUT OF SCOPE)
- Live progress indicator during import — Phase 11 (IMPORT-05). This phase (D-13) builds the per-line status persistence Phase 11 will poll, but does not build the polling endpoint or UI itself.
- Per-line results overview (title, poster, status) — Phase 11 (IMPORT-06). This phase's `bulk_import_line` table (D-09) is the data source; the display is Phase 11's job.
- Manual resolution UI for ambiguous lines — not requested/scoped in either Phase 10 or 11's stated success criteria; ambiguous lines are recorded (D-07/D-09) but no resolution flow was discussed. Flag for the roadmap backlog if the user wants one.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| IMPORT-01 | User can upload a text file (Title + optional Original Title + Year) in Add Film area | Multipart upload pattern (Architecture Patterns §1); no new dependency needed — `spring-boot-starter-web` already provides multipart autoconfiguration |
| IMPORT-02 | System parses each line, searches TMDB via existing search logic, filters by year | Reuse `TmdbClient.search()`; year-filter logic must be added on top (not present in `TmdbClient` today — verified) |
| IMPORT-03 | Unique match auto-saved via existing `/movies/save` logic incl. dedup idempotency | Reuse `MovieService.initiate(email, tmdbId)` + `EnrichmentService.enrich(movieId)` directly — verified exact signatures below |
| IMPORT-04 | Ambiguous matches (multiple year-matching candidates) flagged, never auto-guessed | Matching algorithm in Architecture Patterns §3 (D-05/D-06/D-07) |
| IMPORT-07 | Re-upload skips already-saved lines — no duplicate TMDB calls, no duplicate saves | Dedup-before-TMDB-call pattern in Architecture Patterns §4 (D-08/D-09/D-10) + upsert schema design |
</phase_requirements>

## Summary

Phase 10 is a pure backend-and-thin-frontend integration phase: there is no new external library to evaluate — every building block it needs (`MultipartFile` handling, `@Async`/`@Retryable`/bounded executor, TMDB search, idempotent save) is either already on the classpath (`spring-boot-starter-web` autoconfigures multipart handling) or already implemented in this codebase and explicitly designated for reuse (`MovieService.initiate()`, `EnrichmentService.enrich()`, and the `WikiReloadService`/`WikiReloadController` async-job template). The engineering work is entirely in **wiring these together correctly**: extracting the uploaded file's content synchronously before dispatching to the async job (a well-documented Spring pitfall — `MultipartFile`'s backing temp storage is cleared when the HTTP request completes), adding a year filter and an `original_title` field to the TMDB search path (verified absent today), designing an idempotent per-line upsert into the new `bulk_import_line` table that supports D-10's differentiated retry-vs-skip semantics, and mirroring `WikiReloadService`'s self-proxy `@Transactional`/per-item-failure-isolation/pacing pattern faithfully for a second, structurally similar but functionally distinct background job.

**Primary recommendation:** Build `BulkImportController` (multipart endpoint, synchronous file-read + TMDB-key check, 202 Accepted) → `BulkImportService` (mirrors `WikiReloadService`'s `@Lazy self`-proxy / per-line `@Transactional` / `Thread.sleep` pacing / `TaskRejectedException`→503 structure) → per-line processing that (1) checks the `bulk_import_line` dedup table before any TMDB call, (2) calls the existing `TmdbClient.search()` (extended with year + `original_title`) to match, and (3) on a unique match calls `MovieService.initiate()` + `EnrichmentService.enrich()` exactly as `/movies/save` does — never a bulk-specific save path.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| File upload control (`<input type="file">`) | Browser / Client | — | Standard HTML file input; no new FE library needed |
| Multipart upload endpoint + synchronous file read | API / Backend | — | First multipart endpoint in the app; `MultipartFile` must be fully read within the request thread before async handoff |
| Async batch job orchestration (parse → dedup-check → match → save) | API / Backend | — | Mirrors `WikiReloadService`'s dedicated-executor, fire-and-forget pattern |
| TMDB search + year/original-title matching | API / Backend | — | Extends existing `TmdbClient`; TMDB itself is an external service boundary, not a tier this app owns |
| Idempotent save (dedup + enrichment trigger) | API / Backend | — | 100% reuse of `MovieService.initiate()` + `EnrichmentService.enrich()` — no new save logic (D-12) |
| `bulk_import_line` persistence (per-line live status) | Database / Storage | API / Backend (writes) | New Postgres table; written per-line from within the async job (D-13), read later by Phase 11 |
| Movie / enrichment persistence | Database / Storage | — | Unchanged — existing `movies` table, existing OpenSearch index |

## Standard Stack

### Core
No new libraries required for this phase. All capabilities are covered by dependencies already declared in `backend/build.gradle.kts` [VERIFIED: backend/build.gradle.kts:27-66 — read this session; `implementation("org.springframework.boot:spring-boot-starter-web")` is present at line 30, `org.springframework.retry:spring-retry` at line 34, `org.springframework:spring-aspects` at line 35, `org.flywaydb:flyway-core`/`flyway-database-postgresql` at lines 38-39].

| Capability | Library already present | Purpose |
|---|---|---|
| Multipart file upload | `spring-boot-starter-web` (autoconfigures `StandardServletMultipartResolver` + `MultipartProperties`) | No `@MultipartConfig` or extra starter needed |
| Async job / bounded executor | `spring-boot-starter-web` (`@Async` core), `spring-retry` + `spring-aspects` | `@Retryable` reused on the extended `TmdbClient.search()` call, same as today |
| Schema migration | `flyway-core` + `flyway-database-postgresql` | New `bulk_import_line` table via `V9__*.sql` |
| CSV-style line parsing | JDK only (`String.split(";", -1)`, `BufferedReader`) | D-01/discretion: plain semicolon-split, no header, no RFC 4180 quoting — no CSV library needed |

### Supporting
None — this phase intentionally introduces zero new runtime dependencies.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Plain `String.split(";")` parsing | Apache Commons CSV / OpenCSV | Unnecessary — format has no quoting/escaping (per CONTEXT.md discretion), and one more dependency for a 3-field semicolon split is not justified |
| Reading `MultipartFile` synchronously in the controller | Spring's async multipart streaming (`StreamingResponseBody` equivalents for uploads) | Overkill for the expected file size (a personal film list, not bulk enterprise data); synchronous `getBytes()`/line-split is simpler and avoids the temp-file-lifecycle pitfall entirely |

**Installation:** None — no `build.gradle.kts` changes needed.

## Package Legitimacy Audit

**Not applicable.** This phase introduces no new external packages (see Standard Stack — all capabilities are covered by dependencies already present in `backend/build.gradle.kts`). Skip the Package Legitimacy Gate.

**Packages removed due to [SLOP] verdict:** none — no packages evaluated (none proposed).
**Packages flagged as suspicious [SUS]:** none.

## Architecture Patterns

### System Architecture Diagram

```
Browser (Add Film page)
   │  1. select file, POST multipart/form-data
   ▼
BulkImportController  (new)
   │  2. auth.getName() -> email; resolve User -> userId, tmdbKey (sync)
   │     - no TMDB key configured -> 422 immediately (NoTmdbKeyException, same as /movies/search)
   │  3. file.getBytes() / line-split the InputStream fully — MUST happen HERE,
   │     inside the request thread, before any @Async handoff (temp-file pitfall)
   │  4. bulkImportService.runImport(userId, email, tmdbKey, List<String> rawLines)
   ▼  returns 202 Accepted immediately
BulkImportService.runImport()  [@Async("bulkImportExecutor")]
   │  for each raw line (in order):
   │    a. parse line (D-01/D-02/D-03) -> parse error? -> self.upsertLine(..., PARSE_ERROR); continue
   │    b. normalize (title, year) -> dedup check against bulk_import_line where status=SAVED (D-08/D-10)
   │       -> match found? skip entirely (no TMDB call); continue
   │    c. self.processLine(userId, email, tmdbKey, title, originalTitle, year)  [@Transactional, via @Lazy self-proxy]
   │         - tmdbClient.search(title, tmdbKey)  [existing, @Retryable]
   │         - filter results: release year == line year exactly (D-05)
   │         - 0 matches -> upsert NOT_FOUND
   │         - 1 match -> movieService.initiate(email, tmdbId) + enrichmentService.enrich(movieId) -> upsert SAVED (D-12)
   │         - >1 matches -> if originalTitle given, case-insensitive exact match on original_title narrows to 1?
   │             -> yes: same as "1 match" path (D-06)
   │             -> no: upsert AMBIGUOUS (D-04/D-07)
   │    d. Thread.sleep(pacingDelayMs) between lines (not after the last), same as WikiReloadService (D-11)
   ▼
bulk_import_line table (Postgres, new)      movies table + OpenSearch index (existing, untouched pipeline)
   (read later by Phase 11's results UI)     (written via existing MovieService/EnrichmentService path)
```

### Recommended Project Structure
```
backend/src/main/java/de/moviearchive/
├── bulkimport/                          # new package, mirrors enrichment/ + admin/ split
│   ├── BulkImportController.java        # POST /movies/bulk-import (multipart), lives beside MovieController's domain
│   ├── BulkImportService.java           # @Async orchestrator, mirrors WikiReloadService structure
│   ├── BulkImportLine.java              # @Entity for bulk_import_line
│   ├── BulkImportLineRepository.java    # JpaRepository + dedup query
│   ├── BulkImportLineStatus.java        # enum SAVED, AMBIGUOUS, NOT_FOUND, PARSE_ERROR
│   └── ImportLineParser.java            # pure parsing logic (D-01/D-02/D-03), unit-testable in isolation
├── config/
│   └── AsyncConfig.java                 # add bulkImportExecutor bean, mirrors wikiReloadExecutor
├── enrichment/
│   └── TmdbClient.java                  # EXTEND: add originalTitle to search() mapping
├── movie/dto/
│   └── TmdbSearchResultItem.java        # EXTEND: add `String originalTitle` field
backend/src/main/resources/db/migration/
└── V9__create_bulk_import_line.sql      # next sequential migration (highest existing is V8 — verified)
frontend/
├── pages/add.vue                        # EXTEND: add upload control below/beside existing search form
└── composables/useMovies.ts             # EXTEND: add uploadBulkImport(file: File) function
```

### Pattern 1: Multipart upload endpoint with synchronous pre-read
**What:** The `MultipartFile` passed into a Spring MVC controller method is backed by request-scoped temporary storage. Once the HTTP request completes, that storage is cleared — a background thread that later calls `file.getBytes()` or `file.getInputStream()` risks `FileNotFoundException`/`IOException`, because the temp file it's reading may already be gone. [CITED: github.com/spring-projects/spring-framework — issue #33161 "Multipart files not deleted after upload is finished with async request"; docs.spring.io MultipartFile javadoc confirms lifecycle is tied to the request]
**When to use:** Any endpoint that both (a) accepts a file upload and (b) needs to process that file's content on a different thread (here: the `@Async` bulk-import job).
**Example:**
```java
// Source: pattern synthesized from Spring docs + WikiReloadController's async-trigger shape
// (WikiReloadController.java:51-58 — verified this session)
@PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<Map<String, String>> uploadBulkImport(
        @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
    if (file.isEmpty()) {
        throw new IllegalArgumentException("Uploaded file is empty.");
    }
    // Read content synchronously, in the request thread — MUST happen before any @Async
    // handoff (see Common Pitfalls). File is small (personal film list) so getBytes() is fine.
    List<String> rawLines = new String(file.getBytes(), StandardCharsets.UTF_8).lines().toList();

    // Sync TMDB-key check mirrors MovieService.search()'s NoTmdbKeyException 422 pattern —
    // fail fast, don't bury a missing key inside a fire-and-forget async job the user never sees.
    bulkImportService.runImport(auth.getName(), rawLines);
    return ResponseEntity.accepted().body(Map.of("status", "started"));
}
```

### Pattern 2: Async job orchestration mirroring WikiReloadService
**What:** `@Async("bulkImportExecutor")` fire-and-forget method on the orchestrating service; a `@Lazy`-injected self-proxy reference (`self`) is used to call the per-item `@Transactional` method, because same-class unqualified calls bypass Spring's AOP proxy for both `@Transactional` and `@Retryable`.
**When to use:** Exactly this phase's batch job — the pattern is already established and load-bearing in this codebase for `WikiReloadService`.
**Example:**
```java
// Source: WikiReloadService.java:33-65,105-138 — verified this session (structure only,
// not literal copy — bulk import's per-line unit does more: parse -> dedup-check -> match -> save)
@Service
@Slf4j
public class BulkImportService {
    private final BulkImportService self;
    // ... other deps (BulkImportLineRepository, TmdbClient, MovieService, EnrichmentService, SettingsService)

    public BulkImportService(/* deps */, @Lazy BulkImportService self) {
        // same @Lazy self-injection reasoning as WikiReloadService.java:57-65
        this.self = self;
    }

    @Async("bulkImportExecutor")
    public void runImport(String email, List<String> rawLines) {
        // resolve userId + tmdbKey ONCE up front (like WikiReloadService resolves eligible movies once)
        for (int i = 0; i < rawLines.size(); i++) {
            try {
                self.processLine(email, rawLines.get(i));   // routes through proxy -> @Transactional applies
            } catch (Exception e) {
                log.warn("Bulk import: unexpected error for line index={}: {}", i, e.getMessage());
                // per-item failure isolation — do not abort remaining lines
            }
            if (i < rawLines.size() - 1) {
                try {
                    Thread.sleep(pacingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Transactional
    public void processLine(String email, String rawLine) {
        // parse -> dedup check -> TMDB search -> match -> upsert bulk_import_line
    }
}
```

### Pattern 3: Matching algorithm (D-05/D-06/D-07)
**What:** Exact-year filter first, then an original-title narrowing step only when the candidate set is still ambiguous after year filtering.
**When to use:** Every parsed line that passed the dedup check.
**Example:**
```java
// Extends TmdbClient.search() results — TmdbSearchResultItem must gain `originalTitle`
// (verified absent today: TmdbSearchResultItem.java:3-8 has only tmdbId, title, year, posterPath;
// TmdbClient.java:34-53 maps id/title/release_date-year/poster_path but never original_title)
List<TmdbSearchResultItem> results = tmdbClient.search(line.title(), tmdbKey);
List<TmdbSearchResultItem> yearMatches = results.stream()
        .filter(r -> r.year() != null && r.year().equals(line.year()))   // D-05: exact year only
        .toList();

if (yearMatches.isEmpty()) {
    return LineOutcome.notFound();                                       // D-07
}
if (yearMatches.size() == 1) {
    return LineOutcome.saved(yearMatches.get(0).tmdbId());
}
// D-06: still ambiguous after year filter — try original-title narrowing
if (line.originalTitle() != null && !line.originalTitle().isBlank()) {
    List<TmdbSearchResultItem> narrowed = yearMatches.stream()
            .filter(r -> r.originalTitle() != null
                    && r.originalTitle().equalsIgnoreCase(line.originalTitle()))
            .toList();
    if (narrowed.size() == 1) {
        return LineOutcome.saved(narrowed.get(0).tmdbId());              // D-06
    }
}
return LineOutcome.ambiguous();                                          // D-04
```

### Pattern 4: Dedup-before-TMDB-call + upsert (D-08/D-09/D-10)
**What:** Check the `bulk_import_line` table for an existing `SAVED` row matching the normalized `(title, year)` key *before* calling TMDB. Non-`SAVED` rows (ambiguous/not_found/parse_error) from a previous run are retried and their row is **updated in place**, not duplicated.
**Example:**
```java
// Repository query — mirrors MovieRepository's findByUserIdAndTmdbId idempotency check style
// (MovieRepository.java:19 — verified this session)
Optional<BulkImportLine> existingSaved =
    bulkImportLineRepository.findByUserIdAndNormalizedTitleAndYearAndStatus(
        userId, normalize(title), year, BulkImportLineStatus.SAVED);
if (existingSaved.isPresent()) {
    return; // D-10: skip entirely, no TMDB call, no write
}
// Otherwise: find-or-create + update-in-place (check-then-insert, same rationale as
// MovieService.initiate()'s javadoc: JPA flushes at commit time, so catch-on-duplicate-
// constraint-violation would propagate past the catch block — MovieService.java:43-45 verified)
BulkImportLine row = bulkImportLineRepository
    .findByUserIdAndNormalizedTitleAndYear(userId, normalize(title), year)
    .orElseGet(() -> new BulkImportLine(userId, title, originalTitle, year));
row.setStatus(outcome.status());
row.setTmdbId(outcome.tmdbId());       // null unless SAVED
bulkImportLineRepository.save(row);
```

### Anti-Patterns to Avoid
- **Passing `MultipartFile` into the `@Async` method:** the temp file backing it is cleared once the HTTP request completes — read all needed content synchronously first (Pattern 1).
- **Self-invoking `self.processLine(...)` as `this.processLine(...)`:** bypasses the `@Transactional` proxy identically to the documented `@Async`/`@Retryable` self-invocation trap already called out in this project's CLAUDE.md and `WikiReloadService`'s own javadoc (`WikiReloadService.java:49-56`, verified).
- **Bulk-specific save logic:** D-12 is explicit — call `MovieService.initiate()` + `EnrichmentService.enrich()` exactly as `/movies/save` does. Do not reimplement the idempotency check or the enrichment pipeline trigger inside `BulkImportService`.
- **Applying `@Retryable` to the async orchestrating method (`runImport`)** instead of to the TMDB call it wraps — same pitfall this project's CLAUDE.md documents for `@Async`/`@Retryable` generally; the retry must wrap `TmdbClient.search()`, which already carries `@Retryable` (`TmdbClient.java:26` — verified, unchanged).
- **A single INSERT-only write per bulk_import_line row per upload:** would create duplicate rows for the same (title, year) across re-uploads of non-saved lines, breaking both D-10's "retry in place" semantics and Phase 11's ability to read one row per logical line.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Idempotent save + duplicate detection | A bulk-specific "already saved?" check | `MovieService.initiate(email, tmdbId)` (existing check-then-insert on `(user_id, tmdb_id)` unique constraint) | D-12 mandates reuse; this logic already handles the exact race-condition-safe idempotency semantics needed |
| TMDB search + enrichment pipeline | A parallel bulk-enrichment call path | `TmdbClient.search()` (extended) + `EnrichmentService.enrich()` (unchanged) | Reusing the existing `@Async("enrichmentExecutor")` pipeline means bulk-imported films get the same TMDB detail + OMDB + Wikipedia + OpenSearch indexing as manually-saved films, for free |
| CSV/delimited line parsing | A hand-rolled state-machine parser or a CSV library | `String.split(";", -1)` per trimmed, non-blank line | Format has no quoting/escaping (CONTEXT.md discretion default); `-1` limit arg preserves a trailing empty `OriginalTitle` field (`Title;;Year`) instead of Java's default of dropping trailing empty strings |
| Bounded background job execution | A raw `Thread`/`ExecutorService` in the service class | `ThreadPoolTaskExecutor` bean via `@Async`, mirroring `wikiReloadExecutor` | Spring's declarative `@Async` + named executor gives DI-friendly testability (override via `@DynamicPropertySource` for pacing) and consistent `TaskRejectedException` → 503 behavior already proven in `WikiReloadControllerTest` |

**Key insight:** Every hand-roll temptation in this phase (parsing, save/dedup, async execution, retry) already has a proven, tested implementation living in this exact codebase. The phase's actual difficulty is composition and edge-case sequencing (dedup-before-TMDB-call, retry-non-saved-only, self-proxy transactional boundaries) — not algorithm design.

## Common Pitfalls

### Pitfall 1: MultipartFile read after request completes
**What goes wrong:** `file.getBytes()` (or `getInputStream()`) is called inside the `@Async` method, after the HTTP request has already returned 202 to the client — throws `FileNotFoundException` because the underlying temp file was cleaned up.
**Why it happens:** `MultipartFile`'s backing storage is scoped to the request; Spring documents/GitHub issues confirm this lifecycle explicitly. [CITED: github.com/spring-projects/spring-framework/issues/33161]
**How to avoid:** Read the entire file content (`file.getBytes()` → `String` → `.lines()`) synchronously in the controller (or a plain, non-`@Async` service method called directly from the controller) *before* invoking the `@Async` orchestrator, and pass the extracted `List<String>` (not the `MultipartFile`) into the async method.
**Warning signs:** Intermittent `FileNotFoundException`/`IOException` in the async job's logs, especially under any request latency (proxy, slow client) that lets the request complete before the async thread starts reading.

### Pitfall 2: Self-invocation bypassing @Transactional / retry
**What goes wrong:** Calling `this.processLine(...)` (or an unqualified same-class call) from inside `runImport()` silently skips the `@Transactional` boundary — a per-line failure mid-processing leaves a half-committed state, or worse, an entity attached to no transaction throws `LazyInitializationException` downstream.
**Why it happens:** Spring AOP proxies (used for `@Transactional`, `@Retryable`, `@Async`) only intercept calls that go through the proxy object — same-class calls resolve directly against `this`, bypassing the proxy. Already documented in this project's CLAUDE.md and demonstrated by `WikiReloadService`'s `@Lazy self` pattern.
**How to avoid:** Inject a `@Lazy`-qualified self-reference (exactly as `WikiReloadService.java:57-65` does) and call `self.processLine(...)`, never `this.processLine(...)`.
**Warning signs:** `@Transactional` methods that "work" in manual testing but silently don't roll back on the failure paths covered by unit/integration tests; missing rows after a simulated per-line exception.

### Pitfall 3: Forgetting a fast test-suite pacing override
**What goes wrong:** Integration tests for the bulk-import batch job run slowly (or time out) because the real `pacing-delay-ms` (likely defaulting to something like 1000ms, matching `wiki.retry.pacing-delay-ms`) applies between every test line.
**Why it happens:** This exact issue already required a documented fix for Wikipedia batch-reload — `application-test.properties` sets `wiki.retry.pacing-delay-ms=1` globally for the suite, with individual tests overriding it back up via `@DynamicPropertySource` only when they need to observe real timing/concurrency behavior. [VERIFIED: backend/src/test/resources/application-test.properties:29-32 — `wiki.retry.pacing-delay-ms=1` with comment "Wiki batch-reload pacing — fast global default for the whole suite (RESEARCH.md Pitfall 5)."]
**How to avoid:** Add the new `bulk-import.pacing-delay-ms` (or similarly named) property to `application-test.properties` with a `1`ms fast default from day one, following the exact established convention.
**Warning signs:** Backend test suite runtime jumps noticeably after adding bulk-import tests; a multi-line fixture test takes seconds instead of milliseconds.

### Pitfall 4: Nullable-year dedup key collapsing distinctness for parse-error rows
**What goes wrong:** If the `bulk_import_line` unique/lookup key is `(user_id, normalized_title, year)` and `year` is `NULL` for a line that failed to parse a numeric year, Postgres treats every `NULL` as distinct in a UNIQUE constraint — so re-uploading the same malformed line every time either (a) fails to find the "existing" row to update in place (creating duplicate `PARSE_ERROR` rows per re-upload) or (b) requires an explicit `NULL`-safe lookup query (`year IS NULL` vs `year = :year`) rather than a naive equality match.
**Why it happens:** SQL `NULL = NULL` is `UNKNOWN`, not `TRUE` — a naive `WHERE year = :year` with a null parameter matches nothing.
**How to avoid:** Use an explicit null-safe repository query (`@Query` with `(year = :year OR (year IS NULL AND :year IS NULL))`, or normalize unparseable years to a sentinel value like `-1` before the lookup) so the same malformed line consistently updates the same row across re-uploads rather than accumulating duplicates. Flagged as an **Open Question** below for the planner to resolve explicitly — it is not addressed by any CONTEXT.md decision.
**Warning signs:** `bulk_import_line` row count grows on every re-upload of a file containing unparseable lines, even though no new distinct films are involved.

## Code Examples

### V9 migration — new bulk_import_line table
```sql
-- Source: pattern from V6__create_movies.sql (verified this session — gen_random_uuid() PK,
-- TIMESTAMPTZ timestamps, CHECK-constrained status column, FK + index on user_id)
-- backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql
CREATE TABLE bulk_import_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(500) NOT NULL,
    original_title VARCHAR(500),
    year INTEGER,
    tmdb_id INTEGER,
    status VARCHAR(20) NOT NULL,
    raw_line TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT bulk_import_line_status_check
        CHECK (status IN ('SAVED', 'AMBIGUOUS', 'NOT_FOUND', 'PARSE_ERROR'))
);
CREATE INDEX idx_bulk_import_line_user_id ON bulk_import_line(user_id);
-- Dedup lookup index — NOT a UNIQUE constraint, because NULL year (parse_error lines)
-- cannot be safely enforced unique in Postgres (see Common Pitfalls #4). Enforce
-- "one row per logical line" at the application layer via find-then-update, not the DB.
CREATE INDEX idx_bulk_import_line_dedup
    ON bulk_import_line(user_id, lower(title), year);
```
*Notes for planner: `raw_line` (nullable TEXT) is a discretionary addition beyond D-09's named columns — recommended so `PARSE_ERROR` rows have something displayable in Phase 11 even when `title`/`year` themselves are unparseable garbage. `status` values shown as an illustrative enum (`SAVED`/`AMBIGUOUS`/`NOT_FOUND`/`PARSE_ERROR`) — exact naming is Claude's discretion per CONTEXT.md; keep consistent with whatever `BulkImportLineStatus` Java enum the planner defines.*

### Multipart config — no application.properties changes needed at default sizes
```properties
# Only add if a real user's film list could exceed Spring Boot's defaults.
# Spring Boot 3.5 defaults: max-file-size=1MB, max-request-size=10MB (MultipartProperties).
# [CITED: docs.spring.io/spring-boot/3.5/api/.../MultipartProperties.html]
# A plain-text "Title;OriginalTitle;Year" file with even several thousand lines is well
# under 1MB — defaults are almost certainly sufficient. Only override if user testing
# shows otherwise:
# spring.servlet.multipart.max-file-size=5MB
# spring.servlet.multipart.max-request-size=5MB
```

### Frontend — extending useMovies.ts for upload
```typescript
// Source: pattern matches existing saveMovie()/searchTmdb() $fetch style
// (frontend/composables/useMovies.ts:40-47 — verified this session)
async function uploadBulkImport(file: File): Promise<{ status: string }> {
  const formData = new FormData()
  formData.append('file', file)
  return await $fetch<{ status: string }>('/api/movies/bulk-import', {
    method: 'POST',
    body: formData,               // ofetch auto-sets multipart/form-data + boundary; do NOT set Content-Type manually
    credentials: 'include',
    headers: authHeaders(),       // Authorization only — omit any Content-Type override
  })
}
```

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The new endpoint should live at `POST /movies/bulk-import` (extending `MovieController`'s domain, admin-style trigger like `WikiReloadController`'s `/admin/wiki-reload/{userId}`) rather than a brand-new top-level path | Architecture Patterns, Code Examples | Low — purely a routing/naming choice; easy to change without affecting any other design decision |
| A2 | `bulkImportExecutor` should be sized `core=1/max=1/queue=1`, mirroring `wikiReloadExecutor`, per CONTEXT.md's explicit discretion note ("follow the wikiReloadExecutor sizing pattern... unless there's a reason to differ") — but bulk import is a *user-initiated, single-shot* action (not a background maintenance job), so a slightly larger queue (e.g. `queue=2`) might better tolerate a user re-clicking "Upload" impatiently | Architecture Patterns §2 | Low-Medium — undersized queue causes an unnecessary 503 on accidental double-submit; easy to tune post-hoc, no schema/API impact |
| A3 | `raw_line` column addition (beyond D-09's named columns) is a reasonable discretionary extension for parse-error traceability | Code Examples (V9 migration) | Low — additive column, does not conflict with any locked decision; planner can drop it if deemed unnecessary |
| A4 | Dedup lookup should use a non-unique index + application-layer find-then-update, not a DB-level UNIQUE constraint, due to the nullable-year NULL-distinctness problem (Common Pitfalls #4) | Architecture Patterns §4, Common Pitfalls #4 | Medium — if the planner instead adds a UNIQUE constraint without a NULL-safe strategy, re-uploads of malformed lines will accumulate duplicate `PARSE_ERROR` rows, which will look like a bug to a user re-testing their file |

**All Standard Stack claims (multipart defaults, TMDB response fields, MultipartFile lifecycle) are [CITED] against official/authoritative sources found via WebSearch this session — see Sources. All in-repo code claims are [VERIFIED] against files read this session with line ranges.**

## Open Questions

1. **How should the dedup/upsert lookup key handle unparseable (`NULL`) years for `PARSE_ERROR` lines across re-uploads?**
   - What we know: D-08 defines the dedup key as normalized `(title, year)`; D-10 requires `PARSE_ERROR`/`AMBIGUOUS`/`NOT_FOUND` rows to be retried in place (not duplicated) on re-upload.
   - What's unclear: CONTEXT.md doesn't address the case where `year` itself failed to parse (so there's no valid year to key on) — see Common Pitfalls #4.
   - Recommendation: Use a NULL-safe repository query, or normalize an unparseable year to a sentinel (e.g., `-1`) purely for lookup purposes while `year` itself stores `null` for display. Planner should pick one and document it in the plan; either resolves the bug, this just needs to be a conscious choice.

2. **Does the upload endpoint need its own rate limiting (Bucket4j), like `/auth/*` endpoints?**
   - What we know: Bucket4j is already a dependency (`build.gradle.kts` — verified), used today only on `/auth/login`/`/auth/forgot-password` per CLAUDE.md.
   - What's unclear: CONTEXT.md doesn't mention rate limiting for bulk import; the executor's bounded queue (D-11) already provides a form of throttling (503 on overload), which may be sufficient given this is a single-user-first app.
   - Recommendation: Skip dedicated rate limiting for this phase — the bounded executor + 503 pattern already mirrors the accepted `WikiReloadController` precedent. Revisit if/when multi-user support (mentioned as a future architecture goal in CLAUDE.md) lands.

## Environment Availability

No new external dependencies. This phase relies entirely on infrastructure already required and verified working by prior phases (Postgres via Flyway migrations, the existing TMDB API integration with the user's own TMDB key via Settings). No probe of Docker/DB/OpenSearch availability is needed beyond what earlier phases already established.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ (unit), Testcontainers (Postgres, OpenSearch), WireMock 3.13.0 (TMDB stubbing), MockMvc (`@AutoConfigureMockMvc`) — [VERIFIED: backend/build.gradle.kts:68-77, read this session] |
| Config file | `backend/src/test/resources/application-test.properties` (verified this session) |
| Quick run command | `./gradlew test --tests "*BulkImport*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| IMPORT-01 | Multipart upload accepted, returns 202 | Web/Controller (`@AutoConfigureMockMvc`) | `./gradlew test --tests BulkImportControllerTest` | ❌ Wave 0 |
| IMPORT-02 | Line parsed, TMDB searched, year-filtered | Unit (parser) + External API Contract (WireMock) | `./gradlew test --tests ImportLineParserTest` / `BulkImportServiceTest` | ❌ Wave 0 |
| IMPORT-03 | Unique match auto-saved via `MovieService.initiate()`+`EnrichmentService.enrich()`, idempotent | Integration (`@SpringBootTest` + Testcontainers + WireMock) | `./gradlew test --tests BulkImportIntegrationTest` | ❌ Wave 0 |
| IMPORT-04 | Multiple year-matches without unambiguous original-title narrowing → AMBIGUOUS, not auto-saved | Unit (matching algorithm) | `./gradlew test --tests BulkImportServiceTest` | ❌ Wave 0 |
| IMPORT-07 | Re-upload of a saved line skips TMDB call and DB write entirely; non-saved lines retried | Integration (assert WireMock call count unchanged on 2nd upload) | `./gradlew test --tests BulkImportIntegrationTest` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*BulkImport*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java` — covers IMPORT-02 (D-01/D-02/D-03 parsing edge cases: blank lines, missing OriginalTitle, non-numeric year, wrong field count)
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` — covers IMPORT-02/IMPORT-04 (matching algorithm: unique, ambiguous, narrowed-by-original-title, not-found)
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportIntegrationTest.java` — covers IMPORT-03/IMPORT-07 (full flow incl. dedup-skip-on-reupload, WireMock call-count assertions), mirrors `WikiReloadControllerTest`'s structure (WireMock + `AbstractOpenSearchTest`)
- [ ] Add `bulk-import.pacing-delay-ms=1` to `application-test.properties` (Common Pitfalls #3) — required before any integration test with >1 line is written, or the suite will slow down measurably
- [ ] TMDB fixture additions in `backend/src/test/resources/fixtures/tmdb/` for a multi-candidate (ambiguous) search response with matching `original_title` variants — none of the existing fixtures (single-film TMDB responses) cover the ambiguous-search-results scenario this phase needs

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes (endpoint-level) | Existing `JwtAuthFilter` + `SecurityConfig`'s `anyRequest().authenticated()` — no new matcher needed; `/movies/bulk-import` is not in the `permitAll()` list [VERIFIED: backend/src/main/java/de/moviearchive/config/SecurityConfig.java:29-32 — `"/auth/**", "/actuator/health", "/settings/confirm-email", "/test/**"` permitAll, `.anyRequest().authenticated()` otherwise] |
| V3 Session Management | n/a | Stateless JWT, unchanged |
| V4 Access Control | yes | `auth.getName()` (JWT subject email) resolves the acting user server-side — never trust a client-supplied user identifier in the multipart request; scope all `bulk_import_line` reads/writes to this resolved `userId`, same IDOR-prevention pattern as `WikiReloadController.assertOwnership()` |
| V5 Input Validation | yes | Validate file is non-empty (`file.isEmpty()`), reject non-UTF-8-decodable content gracefully (D-02: UTF-8 only), cap per-line length defensively before persisting to `VARCHAR(500)` columns (matches existing `movies.title`/`movies.original_title` column widths — `V6__create_movies.sql:6-7`, verified) |
| V6 Cryptography | n/a | No new secrets/crypto in this phase — TMDB key retrieval reuses `SettingsService.getApiKeys()` (existing AES-256-GCM-at-rest decryption) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Oversized/malicious file upload (resource exhaustion) | Denial of Service | Rely on Spring Boot's default `max-file-size`/`max-request-size` (1MB/10MB) unless explicitly raised; a 413 Payload Too Large is returned automatically by Spring's multipart resolver, no custom code needed |
| IDOR via user-supplied identifiers | Tampering / Elevation of Privilege | Resolve `userId` exclusively from the JWT (`auth.getName()` → `UserRepository.findByEmail()`), never from a request parameter — exactly `WikiReloadController.assertOwnership()`'s established pattern, but simpler here since bulk import has no path-variable `userId` to validate against at all |
| Log injection via unsanitized line content | Tampering (log forging) | Uploaded titles are free-text; when logging per-line outcomes (`log.warn("...line={}...")`), rely on SLF4J's parameterized logging (already the codebase convention via `@Slf4j`) which does not interpret user content as format directives — do not use `String.format` with raw line content in log messages |

## Sources

### Primary (HIGH confidence — in-repo, read this session)
- `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` — async job template (self-proxy, pacing, per-item isolation)
- `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` — 202-trigger + `TaskRejectedException`→503 pattern
- `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` — executor bean sizing convention
- `backend/src/main/java/de/moviearchive/movie/MovieService.java`, `MovieController.java` — exact `initiate()`/save reuse contract
- `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java`, `backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java` — confirmed `original_title` NOT currently mapped
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` — confirms `enrich()` must be called from a different bean than the async orchestrator
- `backend/src/main/resources/db/migration/V6__create_movies.sql`, `V8__add_wiki_last_attempted_at_to_movies.sql` — migration + schema conventions, confirms V8 is the current highest migration
- `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` — confirms no new security matcher needed
- `backend/src/test/resources/application-test.properties` — confirms fast-pacing-override test convention
- `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` — test structure template for the new integration test
- `frontend/pages/add.vue`, `frontend/composables/useMovies.ts` — existing Add Film UI + composable pattern
- `backend/build.gradle.kts` — confirms no new dependency required

### Secondary (MEDIUM confidence — WebSearch, cross-checked against official docs)
- [MultipartProperties (Spring Boot 3.5 API)](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html) — confirms default `max-file-size=1MB`, `max-request-size=10MB`
- [Spring Framework GitHub issue #33161](https://github.com/spring-projects/spring-framework/issues/33161) — confirms `MultipartFile` temp-storage-cleared-after-request pitfall for async processing
- [TMDB API — Search & Query For Details](https://developer.themoviedb.org/docs/search-and-query-for-details) — confirms `/3/search/movie` result items include `original_title` alongside `title`

### Tertiary (LOW confidence)
- None used as the basis for any recommendation in this document — all external claims were cross-checked against an official/authoritative source before being cited.

## Metadata

**Confidence breakdown:**
- Standard Stack / no-new-dependencies conclusion: HIGH — directly read `build.gradle.kts`
- Architecture (async job, multipart, matching, dedup): HIGH for reuse pattern (verified against actual source files), MEDIUM for the dedup-upsert schema design specifically (novel to this phase, no exact precedent in repo — flagged via Assumptions Log / Open Questions)
- Pitfalls: MEDIUM — the two most important pitfalls (MultipartFile lifecycle, self-invocation) are each backed by one authoritative external source plus in-repo confirmation; the pacing-override and nullable-year pitfalls are directly derived from reading this repo's own prior-phase test conventions and schema

**Research date:** 2026-08-23
**Valid until:** 2026-09-22 (30 days — stable stack, no fast-moving external dependencies)
