# Phase 14: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI - Pattern Map

**Mapped:** 2026-08-27
**Files analyzed:** 8 (2 modified backend, 2 new backend, 2 modified frontend, 2 test files with gaps)
**Analogs found:** 8 / 8 (all files have a strong existing analog — this phase is a disciplined clone of the bulk-import SSE trio plus two small targeted bugfixes)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/src/main/resources/application.properties` (line 65) | config | — | same file, same property | exact (1-line value change) |
| `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` (`doRetryWikipedia`, `batchReload`) | service | event-driven / batch | same file (self-modification) | exact |
| `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java` (NEW) | service | pub-sub (SSE) | `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` | exact (role + data flow) |
| `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (add `progress()`/`stop()`) | controller | streaming (SSE) + request-response | `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` (`progress()` endpoint) | exact |
| `frontend/composables/useSettings.ts` (add `subscribeToWikiReloadProgress`, `stopWikiReload`) | hook/composable | streaming | `frontend/composables/useBulkImport.ts` (`subscribeToProgress`) | exact |
| `frontend/pages/settings.vue` (`#wikipedia-data` section) | component | streaming / request-response | `frontend/pages/imports/[batchId].vue` | exact (role), close (context: inline block vs. dedicated page) |
| `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java` (NEW) | test | — | `backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java` | exact |
| `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` (extend) | test | — | same file (self-extension) | exact |

## Pattern Assignments

### `backend/src/main/resources/application.properties` (config)

**Analog:** same file, lines 63-65

**Current state:**
```properties
# Wiki batch-reload (Phase 8: cooldown window + inter-request pacing, D-04/D-08)
wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}
wiki.retry.pacing-delay-ms=${WIKI_RETRY_PACING_DELAY_MS:1000}
```

**Change (D-01):** only the numeric default on line 65 changes, `1000` → `30000`. `WikipediaClient`'s `wikipedia.request-pacing-ms` and `wikidata.request-pacing-ms` properties (lines ~55-56, ~68-69 of `WikipediaClient.java`) are explicitly untouched — do not confuse the two "pacing" properties (RESEARCH.md Pitfall 1).

---

### `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` (service, event-driven/batch)

**Analog:** self — this is a bugfix + instrumentation pass on the existing file, not a clone of another file.

**Current buggy unconditional-set** (lines 90-122, to be fixed per D-03):
```java
private void doRetryWikipedia(Movie movie, Map<String, String> preResolvedTitles) {
    movie.setWikiLastAttemptedAt(Instant.now());  // BUG: unconditional, fires before any attempt
    try {
        int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
        String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
        String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
        WikipediaResult wiki = preResolvedTitles != null
                ? wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId(), preResolvedTitles)
                : wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId());
        movie.setWikiUrl(wiki.url());
        movie.setWikiSummary(wiki.summary());
        movie.setWikiPlot(wiki.plot());
        movie.setWikiCritics(wiki.critics());
        movieRepository.save(movie);
        log.info("Wiki retry succeeded movieId={}", movie.getId());
        try {
            indexingService.index(movie);
            movie.setIndexedAt(Instant.now());
            movieRepository.save(movie);
        } catch (Exception e) {
            log.warn("Wiki retry: OpenSearch re-index failed movieId={}: {}", movie.getId(), e.getMessage());
        }
    } catch (WikipediaNotFoundException e) {
        movieRepository.save(movie);            // must ALSO set wikiLastAttemptedAt here (genuine result)
        log.warn("Wiki retry: still not found movieId={}", movie.getId());
    } catch (Exception e) {
        movieRepository.save(movie);            // must NOT set wikiLastAttemptedAt here (technical failure)
        log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
    }
}
```
**Fix shape (D-03):** delete line 91's unconditional set; add `movie.setWikiLastAttemptedAt(Instant.now())` in the success path (near the `movieRepository.save(movie)` before/at line 103) and in the `catch (WikipediaNotFoundException e)` block (currently lines 115-117). Do NOT add it to the generic `catch (Exception e)` block. Three locations touched, not one (RESEARCH.md Pitfall 2).

**Current per-movie pacing loop** (lines 149-166, D-01 config-only + D-08 stop-flag insertion point):
```java
for (int i = 0; i < eligible.size(); i++) {
    Movie movie = eligible.get(i);
    try {
        self.retryWikipedia(movie, resolvedTitles);
    } catch (Exception e) {
        log.warn("Wiki batch-reload: unexpected error for movieId={}: {}", movie.getId(), e.getMessage());
    }
    if (i < eligible.size() - 1) {
        try {
            Thread.sleep(pacingDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Wiki batch-reload interrupted for userId={} at index={}", userId, i);
            return;
        }
    }
    // D-08 stop-flag check belongs HERE, at the same loop boundary as the pacing sleep above
    // D-04/D-06/D-07 progress-publish call belongs HERE too, per movie, after retryWikipedia() returns
}
```
Field declarations to reference for the new work: `@Value("${wiki.retry.pacing-delay-ms:1000}") private long pacingDelayMs;` (line 46-47). No `AtomicBoolean` precedent exists anywhere in this codebase (confirmed via grep) — the Stop flag is genuinely new ground; follow the idiom of `WikipediaClient.backoffUntil` (`AtomicReference<Instant>`) as the closest precedent for cross-thread mutable state, using `AtomicBoolean` as its natural boolean analogue. **Pitfall 4 (must-avoid):** `batchReload()` must reset/clear the stop flag for `userId` at the very top of the method (before the per-movie loop starts), not rely on the stop-button handler to reset it — otherwise a second "Start" after "Stop" silently inherits a stale `true` flag and exits on iteration 0.

---

### `backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java` (NEW — service, pub-sub/SSE)

**Analog:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java` (full file, 135 lines — read above)

**Imports pattern** (lines 1-13):
```java
package de.moviearchive.bulkimport;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
```
Adapt package to `de.moviearchive.admin`; the new registry keys on `UUID userId` (not `UUID batchId` — there is no batch entity for wiki-reload; RESEARCH.md Pattern 1 recommends `userId` over inventing a new `runId`, matching the single-global-run-slot invariant of `wikiReloadExecutor` core=1/max=1/queue=1).

**Core registry pattern** (lines 32-97, full class body — clone verbatim, adapt types):
```java
@Service
@Slf4j
public class BulkImportProgressService {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressState> lastKnown = new ConcurrentHashMap<>();

    /** SSE JSON payload shape — Jackson serializes records natively, no extra annotation needed. */
    public record ProgressState(int processed, int total, boolean complete) {
    }

    public void register(UUID batchId, SseEmitter emitter, int totalLinesFallback) {
        emitters.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(batchId, emitter));
        emitter.onTimeout(() -> removeEmitter(batchId, emitter));

        ProgressState state = lastKnown.get(batchId);
        if (state != null) {
            sendEvent(emitter, batchId, state.complete() ? "complete" : "progress", state);
        } else {
            ProgressState synthesized = new ProgressState(totalLinesFallback, totalLinesFallback, true);
            sendEvent(emitter, batchId, "complete", synthesized);
        }
    }

    public void publish(UUID batchId, int processed, int total) {
        ProgressState state = new ProgressState(processed, total, false);
        lastKnown.put(batchId, state);
        broadcast(batchId, "progress", state);
    }

    public void complete(UUID batchId) {
        ProgressState prior = lastKnown.get(batchId);
        int total = prior != null ? prior.total() : 0;
        ProgressState state = new ProgressState(total, total, true);
        lastKnown.put(batchId, state);

        List<SseEmitter> batchEmitters = emitters.get(batchId);
        if (batchEmitters != null) {
            for (SseEmitter emitter : batchEmitters) {
                if (sendEvent(emitter, batchId, "complete", state)) {
                    emitter.complete();
                }
            }
        }
        emitters.remove(batchId);
        lastKnown.remove(batchId);
    }

    private void broadcast(UUID batchId, String eventName, ProgressState state) {
        List<SseEmitter> batchEmitters = emitters.get(batchId);
        if (batchEmitters == null) {
            return;
        }
        for (SseEmitter emitter : batchEmitters) {
            sendEvent(emitter, batchId, eventName, state);
        }
    }

    private boolean sendEvent(SseEmitter emitter, UUID batchId, String eventName, ProgressState state) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(state, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException e) {
            log.warn("Bulk import progress: emitter send failed for batchId={}, removing: {}",
                    batchId, e.getMessage());
            removeEmitter(batchId, emitter);
            return false;
        }
    }

    private void removeEmitter(UUID batchId, SseEmitter emitter) {
        List<SseEmitter> batchEmitters = emitters.get(batchId);
        if (batchEmitters != null) {
            batchEmitters.remove(emitter);
        }
    }
}
```

**Adaptation needed (D-06/D-07):** `ProgressState` must grow to carry a per-movie title+status entry and an `etaSeconds` field. RESEARCH.md's Open Question 1 recommends sending only the most-recently-completed movie's title+status in each `progress` event (not the full accumulated list) — smallest payload, frontend accumulates the list client-side. Suggested shape:
```java
public record ProgressState(int processed, int total, boolean complete,
                             String lastMovieTitle, String lastMovieStatus, // "SUCCESS" | "NOT_FOUND" | "FAILED"
                             long etaSeconds) { }
```
ETA rolling average: a fixed-size `Deque<Long>` (capped at last 5 movie durations per RESEARCH.md recommendation) averaged on each publish — no external stats library needed.

**Error handling pattern:** identical `IOException` → `removeEmitter` + log.warn, no `completeWithError()` (container's AsyncListener already handles it — double-completion risk if added).

---

### `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` (controller, streaming + request-response, MODIFIED)

**Analog for the new SSE endpoint:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java` lines 131-137:
```java
@GetMapping(value = "/bulk-import/{batchId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter progress(@PathVariable UUID batchId, Authentication auth) {
    BulkImportBatch batch = loadOwnedBatch(auth, batchId);
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    progressService.register(batchId, emitter, batch.getTotalLines());
    return emitter;
}
```

**Existing file's own ownership pattern to reuse (not re-derive):** `WikiReloadController.java` lines 40-71 (full content read above) — `assertOwnership(Authentication auth, UUID userId)` (private method, lines 64-71) throws `AccessDeniedException` on JWT-subject/path-`userId` mismatch, already wired to a 403 `@ExceptionHandler` at lines 75-78. The new `GET /admin/wiki-reload/{userId}/progress` and new `POST /admin/wiki-reload/{userId}/stop` endpoints must call this exact existing private method — do not reimplement ownership logic.

**Existing trigger endpoint pattern to follow for the new stop endpoint** (lines 51-58):
```java
@PostMapping("/{userId}")
public ResponseEntity<Map<String, String>> triggerReload(
        @PathVariable UUID userId, Authentication auth) {
    assertOwnership(auth, userId);
    log.info("Wiki batch-reload requested for userId={}", userId);
    wikiReloadService.batchReload(userId);
    return ResponseEntity.accepted().body(Map.of("status", "started"));
}
```
Existing 503 handler for `TaskRejectedException` (lines 86-90) stays unchanged — new endpoints don't submit to the executor so won't trigger it.

---

### `frontend/composables/useSettings.ts` (composable/hook, streaming, MODIFIED)

**Analog:** `frontend/composables/useBulkImport.ts` (full file, 78 lines — read above)

**Types + SSE subscription pattern to clone** (lines 26-30, 55-77 of `useBulkImport.ts`):
```typescript
export interface BulkImportProgress {
  processed: number
  total: number
  complete: boolean
}

function subscribeToProgress(batchId: string, onProgress: (p: BulkImportProgress) => void): () => void {
  const ctrl = new AbortController()
  fetchEventSource(`/api/movies/bulk-import/${batchId}/progress`, {
    headers: authHeaders(),
    signal: ctrl.signal,
    async onopen() {
      // no-op: default fetch-event-source behavior already validates content-type on open
    },
    onmessage(ev) {
      if (ev.event === 'progress' || ev.event === 'complete') {
        onProgress(JSON.parse(ev.data) as BulkImportProgress)
      }
    },
    onerror(err) {
      throw err
    },
  })
  return () => ctrl.abort()
}
```
`useSettings.ts` already has its own `authHeaders()` (lines 8-12) and `getCurrentUserId()` (lines 18-27) — reuse these, don't duplicate. Add `import { fetchEventSource } from '@microsoft/fetch-event-source'` at the top (currently absent from `useSettings.ts`).

**Existing trigger pattern in `useSettings.ts` to extend alongside** (lines 32-46):
```typescript
async function triggerWikiReload(): Promise<'started' | 'already-running'> {
  const userId = await getCurrentUserId()
  try {
    await $fetch(`/api/admin/wiki-reload/${userId}` as string, {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders(),
    })
    return 'started'
  } catch (err: unknown) {
    const e = err as { response?: { status?: number } }
    if (e?.response?.status === 503) return 'already-running'
    throw err
  }
}
```
New `subscribeToWikiReloadProgress(userId, onProgress)` should hit `GET /api/admin/wiki-reload/${userId}/progress`; new `stopWikiReload()` should hit `POST /api/admin/wiki-reload/${userId}/stop` following the same `$fetch` + `authHeaders()` shape as `triggerWikiReload`.

---

### `frontend/pages/settings.vue` `#wikipedia-data` section (component, streaming + request-response, MODIFIED)

**Analog:** `frontend/pages/imports/[batchId].vue` (full file read above)

**Existing lifecycle pattern to clone** (lines 1-49):
```typescript
import { ref, computed, onMounted, onUnmounted } from 'vue'
...
const { subscribeToProgress, getBatchDetail } = useBulkImport()

const progress = ref<BulkImportProgress | null>(null)
let unsubscribe: (() => void) | null = null

const progressPercent = computed(() => {
  if (!progress.value || progress.value.total === 0) return 0
  return Math.round((progress.value.processed / progress.value.total) * 100)
})

onMounted(() => {
  unsubscribe = subscribeToProgress(batchId, async (p) => {
    progress.value = p
    if (p.complete) {
      unsubscribe?.()
      await loadDetail()
    }
  })
})

onUnmounted(() => {
  unsubscribe?.()
})
```
**Adaptation for settings.vue:** subscription should be conditional (only after a successful "Start" trigger, or discovered-active on page load) rather than unconditional `onMounted` — settings.vue is a persistent page, not a per-run route like `imports/[batchId].vue`. Consider gating the `onMounted` subscribe call behind a check of whether a run might already be active (the existing `register()`'s synthesized-complete fallback in `WikiReloadProgressService` handles the "nothing running" case gracefully either way).

**Existing template progress-bar pattern to clone** (lines 76-86):
```html
<div v-if="progress === null" class="flex items-center gap-2 text-sm text-muted-foreground">
  <SpinnerIcon class="w-4 h-4" />
  <span>Connecting...</span>
</div>

<div v-else-if="!progress.complete" class="space-y-2" data-testid="import-progress">
  <p class="text-sm text-foreground">{{ progress.processed }} / {{ progress.total }} processed</p>
  <div class="w-full h-2 bg-card border border-border">
    <div class="h-full bg-primary" :style="{ width: `${progressPercent}%` }" />
  </div>
</div>
```
Per D-06, add a per-movie list below the bar (title + success/fail status, accumulated client-side as each `progress` event arrives — see `WikiReloadProgressService` adaptation note above) and an ETA display (`etaSeconds` from the payload). Use `CheckCircle2`/`XCircle` from `lucide-vue-next` (already imported in `imports/[batchId].vue` line 3) for per-movie success/fail icons.

**Existing insertion point in `settings.vue`** (lines 408-415, current state):
```html
<!-- Section 4: Wikipedia Data -->
<section id="wikipedia-data">
  <h1 class="text-xl font-semibold tracking-wide mb-6">Wikipedia Data</h1>
  <ButtonPrimary type="button" :loading="wikiReloadTriggering" :disabled="wikiReloadTriggering" @click="onTriggerWikiReload">
    {{ wikiReloadTriggering ? 'Starting...' : 'Reload missing Wikipedia data' }}
  </ButtonPrimary>
  <p v-if="wikiReloadMessage" class="text-sm text-foreground mt-2">{{ wikiReloadMessage }}</p>
</section>
```
Existing script state to extend: `wikiReloadTriggering` (line 42), `wikiReloadMessage` (line 43), `onTriggerWikiReload()` (line 177-188 in `settings.vue`) — add a Stop button + progress block directly below, inside this same `<section>`, per D-05.

---

### Test files (analogs for Wave 0 gaps)

- `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java` (NEW) — clone structure of `backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java` (`mock(SseEmitter.class)` + `ArgumentCaptor<SseEmitter.SseEventBuilder>` pattern for register/publish/complete lifecycle assertions).
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` — extend existing file; add a case asserting `wikiLastAttemptedAt` is NOT set when `wikipediaClient.fetch(...)` throws a generic `Exception` (currently only success and `WikipediaNotFoundException` paths are covered per existing tests `shouldSetTimestampAndWikiFields_onRetrySuccess`, `shouldSetTimestampOnly_whenWikipediaNotFound`).
- `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` — extend with SSE progress + stop endpoint cases, mirroring the existing 403 ownership-mismatch test already present for `triggerReload`.
- `frontend/test/unit/composables/useSettings.spec.ts` — extend using the `vi.mock('@/composables/...')` + captured-callback pattern from `frontend/test/unit/pages/imports-batchId.spec.ts` (lines 1-40).
- `frontend/test/unit/pages/settings.spec.ts` — extend existing wiki-reload-trigger tests with progress block + Stop button interaction cases.

## Shared Patterns

### SSE Registry (in-memory, no queue infra)
**Source:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportProgressService.java`
**Apply to:** `WikiReloadProgressService` (new)
`Map<UUID, List<SseEmitter>>` + `Map<UUID, ProgressState>` "last-known-state" registry — `register`/`publish`/`complete`/`sendEvent`/`removeEmitter`. Per CLAUDE.md's "no queue infrastructure" constraint, this is the only correct shape — do not introduce WebSockets, polling, or an external broker.

### Ownership check (IDOR protection)
**Source:** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` lines 64-71 (`assertOwnership`)
**Apply to:** All new endpoints on `WikiReloadController` (`progress()`, `stop()`) — call the existing private method, do not re-derive.

### Authenticated SSE consumption
**Source:** `frontend/composables/useBulkImport.ts` lines 55-74 (`subscribeToProgress`)
**Apply to:** `useSettings.ts`'s new `subscribeToWikiReloadProgress`
`@microsoft/fetch-event-source` with header-based `Authorization` — never native `EventSource` (cannot carry a header; would force a query-param token leak into logs).

### Executor-scoped single-run invariant
**Source:** `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` (`wikiReloadExecutor`, core=1/max=1/queue=1)
**Apply to:** `WikiReloadProgressService`'s registry keying decision (use `userId`, not a new `runId`) and the Stop-flag's reset lifecycle in `batchReload()`.

## No Analog Found

None — every file in this phase's scope has a direct, recently-verified analog already in the codebase. The one genuinely novel construct is the Stop-flag mechanism (`AtomicBoolean`, keyed by `userId`, checked at the per-movie loop boundary) — no prior `AtomicBoolean` usage exists anywhere in this codebase (confirmed via grep), so while the *idiom* (JDK atomics for cross-thread flags, per `WikipediaClient.backoffUntil`'s `AtomicReference<Instant>`) is established, the specific Stop-flag construct itself should be planned as new ground, not "just like X" — see RESEARCH.md's explicit callout.

## Metadata

**Analog search scope:** `backend/src/main/java/de/moviearchive/{enrichment,admin,bulkimport,config,movie}`, `frontend/{composables,pages}`, corresponding test directories
**Files read in full this session:** `WikiReloadService.java` (targeted sections), `WikiReloadController.java`, `BulkImportProgressService.java`, `useBulkImport.ts`, `useSettings.ts`, `imports/[batchId].vue` (script + template), `settings.vue` (targeted sections), plus grep-located line ranges in `BulkImportController.java`, `MovieRepository.java`, `application.properties`
**Pattern extraction date:** 2026-08-27
