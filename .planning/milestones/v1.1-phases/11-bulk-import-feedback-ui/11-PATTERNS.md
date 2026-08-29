# Phase 11: Bulk Import Feedback UI - Pattern Map

**Mapped:** 2026-08-24
**Files analyzed:** 15
**Analogs found:** 15 / 15

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `backend/src/main/resources/db/migration/V10__create_bulk_import_batch.sql` | migration | CRUD | `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql` | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatch.java` | model | CRUD | `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatchRepository.java` | model (repository) | CRUD | `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` (modified: +batchId, +posterPath) | model | CRUD | itself (existing file) | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` (modified: +findByBatchId, status-count query) | model (repository) | CRUD | itself; `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` (Pageable "top N" precedent) | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` (modified: runImport gains batchId param + progress push; saveAndUpsert gains posterPath) | service | event-driven (async loop) | itself (existing file) | exact |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` | service | streaming (SSE emitter registry) | none (new capability) — closest structural analog: `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` (in-process singleton `@Service`, `@Lazy` self-proxy convention) | role-match |
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` (modified: +3 GET endpoints, +1 SSE endpoint) | controller | request-response + streaming | itself (existing file); ownership-check portion from `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` | exact (CRUD endpoints) / role-match (SSE endpoint) |
| `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportBatchSummary.java` | model (DTO) | transform | `backend/src/main/java/de/moviearchive/movie/dto/MovieInitiateResult.java` (record DTO convention — see Movie dto package) | role-match |
| `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportBatchDetail.java` | model (DTO) | transform | same as above | role-match |
| `backend/src/main/java/de/moviearchive/bulkimport/dto/BulkImportLineResult.java` | model (DTO) | transform | `backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java` | exact |
| `frontend/pages/imports/index.vue` | component (page) | request-response | `frontend/pages/add.vue` (poster-grid results section) | role-match |
| `frontend/pages/imports/[batchId].vue` | component (page) | request-response + streaming | `frontend/pages/add.vue` (whole file: polling pattern + poster-grid/status-overlay markup) | exact |
| `frontend/composables/useBulkImport.ts` | service (composable) | request-response + streaming | `frontend/composables/useMovies.ts` | exact |
| `frontend/pages/add.vue` (modified: after upload, link to new batch page) | component (page) | request-response | itself (existing file) | exact |

## Pattern Assignments

### `backend/src/main/resources/db/migration/V10__create_bulk_import_batch.sql` (migration, CRUD)

**Analog:** `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql` (full file, 25 lines, read above)

**Style to copy:**
- `UUID PRIMARY KEY DEFAULT gen_random_uuid()` for the id column
- `user_id UUID NOT NULL REFERENCES users(id)` FK pattern
- `TIMESTAMPTZ NOT NULL DEFAULT now()` for timestamps
- Trailing `CREATE INDEX idx_<table>_<col> ON <table>(<cols>)` statements with a comment explaining *why* the index shape was chosen (not just what it is) — every index in V9 has a rationale comment
- RESEARCH.md's Code Examples section already has the exact recommended SQL body for V10 (dedicated `bulk_import_batch` table + `batch_id`/`poster_path` columns added to `bulk_import_line`) — use that as the literal starting point, written in this V9 comment style.

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatch.java` (model, CRUD)

**Analog:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLine.java` (full file, 58 lines, read above)

**Entity pattern to copy exactly:**
```java
@Entity
@Table(name = "bulk_import_batch")
@Getter
@Setter
@NoArgsConstructor
public class BulkImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "total_lines", nullable = false)
    private Integer totalLines;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public BulkImportBatch(User user, int totalLines) {
        this.user = user;
        this.totalLines = totalLines;
        this.createdAt = Instant.now();
    }
}
```
Conventions confirmed from `BulkImportLine.java`: Lombok `@Getter @Setter @NoArgsConstructor` (never `@Data`), `@GeneratedValue(strategy = GenerationType.UUID)` (not DB-generated default), explicit constructor for the fields known at creation time, `Instant.now()` default for timestamps set both as a field default and inside the constructor.

**Add to `BulkImportLine.java` (modified):** two new fields following the exact same `@Column` style —
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "batch_id")
private BulkImportBatch batch;   // nullable — pre-migration rows have no batch

@Column(name = "poster_path")
private String posterPath;
```

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportBatchRepository.java` (model/repository, CRUD)

**Analog:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java` (full file, 57 lines, read above)

**Pattern to copy:**
- `extends JpaRepository<BulkImportBatch, UUID>`
- Custom queries via `@Query` + `@Param`, each with a javadoc explaining the *why*, not just the *what* — every query in the analog has a comment referencing the decision ID (D-xx) it implements
- Needed methods per RESEARCH.md: `findByUserIdOrderByCreatedAtDesc(UUID userId)` (batch list, D-03) — can likely be a derived query, no `@Query` needed, mirroring the simplicity of `findByUserIdAndRawLineAndYearIsNull` in the analog (derived query, no annotation)

**Add to `BulkImportLineRepository.java` (modified):**
```java
List<BulkImportLine> findByBatchIdOrderByTitle(UUID batchId);

@Query("SELECT b.status AS status, COUNT(b) AS count FROM BulkImportLine b "
        + "WHERE b.batch.id = :batchId GROUP BY b.status")
List<StatusCount> countByBatchIdGroupByStatus(@Param("batchId") UUID batchId);
```
(exact projection interface shape is a planner-level decision; the `@Query` + `@Param` style above is the pattern to follow)

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java` (service, event-driven — modified)

**Analog:** itself (existing file, full file read above, 231 lines)

**Core pattern already established — extend, do not replace:**
- `@Async("bulkImportExecutor")` on `runImport()`, `@Transactional` on `processLine()`, both routed through the `@Lazy` self-proxy field (`self.processLine(...)`) — same-class unqualified calls bypass Spring AOP (documented in the class javadoc, lines 24-30)
- Per-line failure isolation: `try { self.processLine(...) } catch (Exception e) { log.warn(...) }` inside the loop — never let one bad line abort the batch
- `Thread.sleep(pacingDelayMs)` paced between lines, guarded by `i < rawLines.size() - 1` (never sleep after the last line)

**Exact modification per RESEARCH.md Pattern 1 (lines 182-187 of RESEARCH.md, grounded in `BulkImportService.java:68-91`):**
```java
@Async("bulkImportExecutor")
public void runImport(String email, String tmdbKey, List<String> rawLines, UUID batchId) {
    log.info("Bulk import starting email={} lines={} batchId={}", email, rawLines.size(), batchId);
    for (int i = 0; i < rawLines.size(); i++) {
        try {
            self.processLine(email, tmdbKey, rawLines.get(i)).ifPresent(enrichmentService::enrich);
        } catch (Exception e) {
            log.warn("Bulk import: unexpected error for line index={}: {}", i, e.getMessage());
        }
        progressService.publish(batchId, i + 1, rawLines.size()); // NEW
        if (i < rawLines.size() - 1) {
            try {
                Thread.sleep(pacingDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Bulk import interrupted for email={} at index={}", email, i);
                return;
            }
        }
    }
    progressService.complete(batchId); // NEW
    log.info("Bulk import complete email={} processed={}", email, rawLines.size());
}
```

**`saveAndUpsert()` modification (RESEARCH.md Code Examples, grounded in `BulkImportService.java:180-184`):** change signature from `int tmdbId` to the whole `TmdbSearchResultItem match` so `match.posterPath()` is available without an extra TMDB call:
```java
private Optional<UUID> saveAndUpsert(User user, String email, ParsedLine parsed, TmdbSearchResultItem match) {
    MovieInitiateResult result = movieService.initiate(email, match.tmdbId());
    upsertLine(user, parsed, BulkImportLineStatus.SAVED, match.tmdbId(), match.posterPath()); // NEW param
    return result.isNew() ? Optional.of(result.id()) : Optional.empty();
}
```
Callers at lines 155 and 165 change from `saveAndUpsert(user, email, parsed, yearMatches.get(0).tmdbId())` to `saveAndUpsert(user, email, parsed, yearMatches.get(0))` (pass the whole item, not just the int id).

**Error handling pattern to reuse (lines 133-144):** wrap external calls (TMDB search) in try/catch, log with `log.warn`, and persist a status row rather than propagating — same style applies to any error handling inside the progress-push call (never let an `SseEmitter.send()` `IOException` propagate up into `runImport()`'s loop and abort the batch).

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` (service, streaming — new)

**No direct analog** (first SSE feature in this codebase). Structural conventions to inherit from `BulkImportService.java`/`WikiReloadService` even though the SSE mechanism itself is new:
- `@Service` + `@Slf4j`
- Constructor injection, no field injection
- In-memory state guarded appropriately (RESEARCH.md: `Map<UUID, List<SseEmitter>>`, single-instance-backend assumption, no distributed broker — see RESEARCH.md "Don't Hand-Roll" table)

**Concrete pattern from RESEARCH.md Pattern 2 (backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java, endpoint to add):**
```java
@GetMapping(value = "/bulk-import/{batchId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter progress(@PathVariable UUID batchId, Authentication auth) {
    assertOwnership(auth, batchId); // same IDOR pattern as WikiReloadController.assertOwnership
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // never rely on container default timeout (10-30s)
    progressService.register(batchId, emitter);
    return emitter;
}
```
**Cleanup pattern (RESEARCH.md Security Domain, DoS mitigation note):** register `emitter.onTimeout(() -> registry.remove(...))` and `emitter.onCompletion(...)` to avoid unbounded growth of stale emitter references.

---

### `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` (controller — modified)

**Analog (CRUD endpoints):** itself, full file read above (114 lines) — copy the existing style exactly:
- `@RestController @RequestMapping("/movies") @Slf4j`
- Constructor injection, no `@Autowired` field injection
- `@ExceptionHandler` methods at the bottom of the same controller class (not a global `@ControllerAdvice`) — `IllegalArgumentException` → 400, `NoTmdbKeyException` → 422, `TaskRejectedException` → 503

**Analog (ownership/IDOR check for the new `{batchId}` path-variable endpoints):** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (full file, 92 lines, read above)
```java
private void assertOwnership(Authentication auth, UUID batchId) {
    String email = auth.getName();
    // load batch, compare batch.getUser().getId() against the JWT-resolved user's id
    ...
    if (!batch.getUser().getId().equals(resolvedUserId)) {
        throw new AccessDeniedException("Access denied.");
    }
}

@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
}
```

**Existing `uploadBulkImport()` modification (RESEARCH.md Code Examples, grounded in `BulkImportController.java:51-91`):**
```java
BulkImportBatch batch = bulkImportService.createBatch(email, rawLines.size()); // NEW — synchronous insert
log.info("Bulk import requested email={} lines={} batchId={}", email, rawLines.size(), batch.getId());
bulkImportService.runImport(email, tmdbKey, rawLines, batch.getId()); // batchId now threaded through
return ResponseEntity.accepted().body(Map.of("status", "started", "batchId", batch.getId().toString()));
```

**New GET endpoints follow the same request-response shape as any simple read endpoint in this codebase** — `ResponseEntity<T>` returning a DTO, no manual JSON building (unlike the `Map.of(...)` used for the small ad-hoc status responses above — GET endpoints return typed DTOs/records per the RESEARCH.md `dto/` package plan).

---

### DTOs: `BulkImportBatchSummary.java`, `BulkImportBatchDetail.java`, `BulkImportLineResult.java` (model/DTO, transform)

**Analog:** `backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java` — confirmed record shape: `record TmdbSearchResultItem(int tmdbId, String title, String originalTitle, Integer year, String posterPath)` (referenced in RESEARCH.md line 325-326). Use Java `record` types, not classes with Lombok, for read-only response DTOs in this package.

```java
public record BulkImportBatchSummary(UUID batchId, Instant createdAt, int totalLines, Map<String, Long> statusCounts) {}

public record BulkImportBatchDetail(UUID batchId, Instant createdAt, int totalLines, List<BulkImportLineResult> lines) {}

public record BulkImportLineResult(String title, String originalTitle, Integer year, String status, String posterPath) {}
```

---

### `frontend/composables/useBulkImport.ts` (service/composable, request-response + streaming)

**Analog:** `frontend/composables/useMovies.ts` (full file, 77 lines, read above)

**Imports/auth pattern to copy exactly (lines 24-31 of the analog):**
```typescript
export function useBulkImport() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }
  ...
}
```

**Core request-response pattern (lines 33-38, 49-54 of the analog — `$fetch` with `credentials: 'include'` + `headers: authHeaders()`):**
```typescript
async function getBatches(): Promise<BulkImportBatchSummary[]> {
  return await $fetch<BulkImportBatchSummary[]>('/api/movies/bulk-import/batches', {
    credentials: 'include',
    headers: authHeaders(),
  })
}

async function getBatchDetail(batchId: string): Promise<BulkImportBatchDetail> {
  return await $fetch<BulkImportBatchDetail>(`/api/movies/bulk-import/batches/${batchId}`, {
    credentials: 'include',
    headers: authHeaders(),
  })
}
```

**Streaming pattern — new capability, exact code from RESEARCH.md Pattern 3 (grounded in the same `authHeaders()` helper reused, not a new auth mechanism):**
```typescript
import { fetchEventSource } from '@microsoft/fetch-event-source'

function subscribeToProgress(batchId: string, onProgress: (p: { processed: number; total: number; complete: boolean }) => void) {
  const ctrl = new AbortController()
  fetchEventSource(`/api/movies/bulk-import/${batchId}/progress`, {
    headers: authHeaders(),
    signal: ctrl.signal,
    onmessage(ev) {
      if (ev.event === 'progress' || ev.event === 'complete') {
        onProgress(JSON.parse(ev.data))
      }
    },
    onerror(err) {
      throw err // stop the library's default retry-forever behavior on fatal errors (Pitfall 3)
    },
  })
  return () => ctrl.abort() // call from onUnmounted
}
```
**Reuse existing `uploadBulkImport()` unchanged** (lines 64-73 of `useMovies.ts`) — move it into this new composable or keep it in `useMovies.ts` and just add the batch-list/detail/progress functions here; either placement follows this codebase's one-composable-per-domain convention (`useMovies`, `useDashboard`, `useSettings`, etc. each own one feature area).

---

### `frontend/pages/imports/[batchId].vue` (component/page, request-response + streaming)

**Analog:** `frontend/pages/add.vue` (full file, 237 lines, read above)

**Cleanup-on-unmount pattern to copy (lines 21, 98-101):**
```typescript
const pollingIntervals = new Map<string, ReturnType<typeof setInterval>>()
// ...
onUnmounted(() => {
  pollingIntervals.forEach(interval => clearInterval(interval))
  pollingIntervals.clear()
})
```
For this page, replace the `Map<string, Interval>` + `clearInterval` cleanup with the SSE `unsubscribe` callback returned by `subscribeToProgress()` — same `onUnmounted` lifecycle hook, same "always clean up async resources" discipline, per RESEARCH.md Pattern 3's comment ("call from onUnmounted, mirroring add.vue's existing pollingIntervals cleanup pattern").

**`posterUrl()` helper — copy verbatim (lines 103-106):**
```typescript
function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}
```

**Poster-grid/status-overlay markup — RESEARCH.md already provides the exact adapted markup** (Code Examples section, "Frontend: reusing the existing poster-grid/status-overlay vocabulary for the results list"), mapped from `add.vue`'s 3 `PosterState` values (lines 161-210 of `add.vue`, `bg-background/70` overlay + `lucide-vue-next` `CheckCircle2`/`XCircle` icons) to the 4 `BulkImportLineStatus` values, with a text-only fallback (`v-else` div with `line.title`) for rows with no `posterPath` (AMBIGUOUS/NOT_FOUND/PARSE_ERROR per D-04).

**Error/loading banner pattern — reuse `FormErrorBanner.vue` component** (already imported in `add.vue` line 5, used at line 159/233) for any load-failure state on this page.

---

### `frontend/pages/imports/index.vue` (component/page, request-response)

**Analog:** `frontend/pages/add.vue`'s results-grid section (structural pattern only — a grid of clickable cards) and `frontend/pages/index.vue`/`frontend/composables/useDashboard.ts` (list/summary page precedent — read for the "no pagination, plain list" convention referenced in RESEARCH.md Open Question 2, `MovieRepository.java:52,56`'s `Pageable`/`PageRequest.of(0, N)` "top N" pattern, NOT cursor pagination).

**Pattern:** `onMounted` fetch via the new composable's `getBatches()`, render a list/grid of `BulkImportBatchSummary` (date, line count, status distribution), each item a `<NuxtLink :to="\`/imports/${batch.batchId}\`">` — no existing exact analog for a "list of past runs" page in this codebase; follow the general page-component shape (`<script setup lang="ts">` + composable call in `onMounted` + template) established by every other page in `frontend/pages/`.

---

## Shared Patterns

### Ownership / IDOR check on path-variable IDs
**Source:** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:64-71`
**Apply to:** All 3 new `{batchId}` path-variable endpoints (batch detail GET, progress SSE GET) — NOT the existing POST upload endpoint (no path variable there)
```java
private void assertOwnership(Authentication auth, UUID batchId) {
    String email = auth.getName();
    User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    // load batch by id, then:
    if (!batch.getUser().getId().equals(user.getId())) {
        throw new AccessDeniedException("Access denied.");
    }
}
```

### Frontend Bearer-header auth helper
**Source:** `frontend/composables/useMovies.ts:27-31`
**Apply to:** All new `useBulkImport.ts` functions, including the SSE `fetchEventSource` call — reuse the exact same `authHeaders()` helper, do not create a second auth mechanism.

### `@Lazy` self-proxy for same-class async/transactional calls
**Source:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:41,52,76` (constructor-injected `@Lazy BulkImportService self`, used as `self.processLine(...)`)
**Apply to:** Any new method added to `BulkImportService` that needs its own `@Transactional`/`@Async` boundary honored when called from another method in the same class (e.g., if `createBatch()` needs `@Transactional` and is called from `runImport()` — unlikely here since `createBatch()` will be called from the controller directly, but the constraint applies to any same-class call chain).

### Migration style (Flyway, V1-V9 precedent)
**Source:** `backend/src/main/resources/db/migration/V9__create_bulk_import_line.sql`
**Apply to:** `V10__create_bulk_import_batch.sql` — `UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `REFERENCES users(id)`, `TIMESTAMPTZ NOT NULL DEFAULT now()`, indexes with rationale comments.

### Controller exception-handler-per-controller convention
**Source:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:93-114` and `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:73-91`
**Apply to:** Any new exception types the batch/progress endpoints introduce (e.g., "batch not found" → 404) — add as an `@ExceptionHandler` method on `BulkImportController` itself, not a global `@ControllerAdvice` (this codebase does not use one for this domain).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` | service | streaming (SSE emitter registry) | First SSE feature in this codebase — no existing in-memory pub/sub or emitter-registry pattern to copy. Use RESEARCH.md's Pattern 1/2 code examples directly (already concrete and codebase-grounded) plus the general `@Service`/`@Slf4j`/constructor-injection conventions from `BulkImportService.java`. |
| `frontend/pages/imports/index.vue` | component (page) | request-response | No existing "list of past batch runs" page in this codebase to copy structurally; use the generic page-component shape shared by all files in `frontend/pages/` plus `add.vue`'s grid/card visual vocabulary for consistency. |

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/bulkimport/`, `backend/src/main/java/de/moviearchive/admin/`, `backend/src/main/java/de/moviearchive/movie/dto/`, `backend/src/main/resources/db/migration/`, `frontend/pages/`, `frontend/composables/`
**Files scanned:** 15 read directly (BulkImportController.java, BulkImportService.java, BulkImportLine.java, BulkImportLineRepository.java, WikiReloadController.java, add.vue, useMovies.ts, V9 migration) + RESEARCH.md's own already-verified code excerpts reused where they were more concrete than a fresh read would add
**Pattern extraction date:** 2026-08-24
