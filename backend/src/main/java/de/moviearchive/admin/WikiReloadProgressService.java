package de.moviearchive.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory SSE emitter registry for live wiki-reload progress (D-04, D-14-03), structurally
 * cloned from {@link de.moviearchive.bulkimport.BulkImportProgressService} — same
 * Map&lt;UUID, List&lt;SseEmitter&gt;&gt; + last-known-state registry shape, appropriate for
 * this app's single-instance, no-queue-infrastructure architecture (CLAUDE.md's "Async: @Async
 * + @Retryable (no queue infrastructure)" line).
 *
 * Keyed on {@code userId} rather than a batch/run id — there is no persisted batch entity for
 * wiki-reload, and {@code wikiReloadExecutor} (core=1/max=1/queue=1) already enforces a single
 * global run-slot, so one concurrent run per app instance is already the invariant. {@code
 * register()} therefore takes no {@code totalFallback} parameter (unlike the bulk-import
 * version, which reads a persisted {@code batch.getTotalLines()}): when no {@code lastKnown}
 * state exists for {@code userId}, there is no persisted total to fall back to, so a
 * zero-progress synthetic complete event is sent instead.
 *
 * Also owns the net-new Stop-flag mechanism (D-08, D-14-04) — no precedent exists elsewhere in
 * this codebase for cross-run cancellation. {@code resetRun(userId)} MUST be called by {@code
 * WikiReloadService.batchReload()} at the very top of the method, before its per-movie loop, so
 * a second "Start" after a "Stop" never inherits a stale {@code true} flag (RESEARCH.md
 * Pitfall 4's stale-flag self-inflicted-DoS fix).
 *
 * <p><b>Registry lifecycle differs from the bulk-import model it was cloned from</b>
 * (wiki-reload-progress-blind-window fix, 2026-08-28): bulk-import's emitter registry is keyed
 * per-batch-id, so closing it when that one batch finishes is correct. This registry is keyed
 * per-userId and the frontend opens exactly one SSE subscription per page load, covering EVERY
 * future run — so {@link #complete(UUID)} must never close the emitter or evict the registry
 * entry on a run merely finishing; only an actual client disconnect (via {@code
 * onCompletion}/{@code onTimeout} in {@link #register}, or a failed send in {@code sendEvent})
 * may do that. See {@link #complete(UUID)}'s javadoc for the full incident writeup.
 */
@Service
@Slf4j
public class WikiReloadProgressService {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressState> lastKnown = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> durationWindowsMs = new ConcurrentHashMap<>();

    /** Fixed-size rolling window of the last N per-movie call durations feeding the D-07/D-14-03
     * ETA computation — balances responsiveness (adapts quickly once 429 backoff kicks in)
     * against a single-outlier-movie skewing the estimate (RESEARCH.md Open Question 2). */
    private static final int ETA_WINDOW_SIZE = 5;

    /**
     * SSE JSON payload shape — Jackson serializes records natively, no extra annotation needed.
     *
     * <p>{@code stopped} (WR-02, deferred from Phase 14, closed in Phase 16): distinguishes a run
     * that ended because {@link #requestStop(UUID)} was called from a run that genuinely reached
     * its last eligible movie. Trailing field placement is deliberate — 14-REVIEW.md originally
     * sketched inserting it mid-record, but Jackson serializes records positionally by field name
     * (not position), so appending at the end avoids reordering every existing construction
     * site's argument meaning for no benefit.
     */
    public record ProgressState(int processed, int total, boolean complete,
                                 String lastMovieTitle, String lastMovieStatus, long etaSeconds,
                                 boolean stopped) {
    }

    /**
     * Registers a newly-opened SSE connection for userId. Wires onCompletion/onTimeout to
     * remove the emitter from the registry (prevents unbounded growth across app uptime).
     * Replays the last-known state immediately if one exists for userId (reconnect /
     * already-in-flight case); otherwise synthesizes and sends an immediate "complete" event
     * with (0, 0, true, null, null) — there is no persisted total to fall back to for
     * wiki-reload, unlike bulk-import's batch entity.
     */
    public void register(UUID userId, SseEmitter emitter) {
        emitters.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));

        ProgressState state = lastKnown.get(userId);
        if (state != null) {
            sendEvent(emitter, userId, state.complete() ? "complete" : "progress", state);
        } else {
            ProgressState synthesized = new ProgressState(0, 0, true, null, null, 0L, true);
            sendEvent(emitter, userId, "complete", synthesized);
        }
    }

    /**
     * Publishes a zero-progress "run has started" state for userId — MUST be called by
     * {@code WikiReloadService.batchReload()} right after computing the eligible list, before
     * the (potentially slow, rate-limit-backoff-prone) Wikidata SPARQL prefetch call. Without
     * this, the frontend receives no "progress" event — and therefore never shows the Stop
     * button or progress panel — until the first movie is actually processed, which can be
     * minutes away under real rate-limiting for a large eligible count; a Stop click during
     * that window is honored as soon as the per-movie loop's first iteration runs (it already
     * checks {@link #isStopRequested(UUID)} before processing movie 0), but the user had no way
     * to see a run was in progress or to click Stop at all. Deliberately does NOT touch
     * durationWindowsMs — this is not a real per-movie call duration and must not skew the
     * ETA rolling average.
     */
    public void start(UUID userId, int total) {
        ProgressState state = new ProgressState(0, total, false, null, null, 0L, false);
        lastKnown.put(userId, state);
        broadcast(userId, "progress", state);
    }

    /**
     * Stores the given progress as userId's last-known state and sends a "progress" event to
     * every currently registered emitter for userId. {@code durationMs} is the just-completed
     * movie's FULL real per-movie cycle time — the wall-clock fetch-call duration (including any
     * time spent inside an active 429 backoff, since that wait happens synchronously inside the
     * same call — D-07/D-14-03) PLUS the pacing-delay sleep {@code batchReload()} applies after
     * it. The pacing delay must be included: it dominates the real per-movie cadence under
     * normal (non-rate-limited) conditions, and omitting it — publishing only the sub-second
     * fetch-call time — was found live in UAT (2026-08-27) to produce a wildly-too-low ETA (e.g.
     * ~40min shown for a run whose real pace implied 190min+). Pushed onto userId's rolling
     * window (capped at {@link #ETA_WINDOW_SIZE}), whose average feeds the etaSeconds
     * computation: remaining-count times the average duration. Returns the constructed
     * ProgressState (callers may ignore it) so tests can assert etaSeconds directly without
     * mocking an SseEmitter.
     */
    public ProgressState publish(UUID userId, int processed, int total, String lastMovieTitle,
                                  String lastMovieStatus, long durationMs) {
        Deque<Long> window = durationWindowsMs.computeIfAbsent(userId, id -> new ArrayDeque<>());
        window.addLast(durationMs);
        while (window.size() > ETA_WINDOW_SIZE) {
            window.removeFirst();
        }
        double average = window.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long etaSeconds = Math.round(average * (total - processed) / 1000.0);

        ProgressState state = new ProgressState(
                processed, total, false, lastMovieTitle, lastMovieStatus, etaSeconds, false);
        lastKnown.put(userId, state);
        broadcast(userId, "progress", state);
        return state;
    }

    /**
     * Stores a terminal (total, total, true, ...) state as userId's last-known state and
     * broadcasts a "complete" event to every currently-registered emitter for userId. Clears
     * the per-RUN state (stop flag, ETA duration window) but deliberately does NOT touch the
     * `emitters` registry entry or call emitter.complete() on anything — see the fix note below.
     *
     * <p>Bug history (wiki-reload-progress-blind-window, 2026-08-28): this method used to also
     * call {@code emitter.complete()} on each emitter and then remove userId's entry from both
     * {@code emitters} and {@code lastKnown}. That closed the underlying SSE HTTP stream. The
     * frontend (settings.vue) opens exactly ONE {@code fetchEventSource()} subscription per page
     * mount — covering every future run, not just the current one — and
     * {@code @microsoft/fetch-event-source} does NOT auto-reconnect after a clean
     * server-initiated stream close (only after a thrown/network error; confirmed by reading its
     * installed source). So closing the emitter here permanently killed the page's only SSE
     * connection the moment the FIRST run finished: every subsequent run's start()/publish()
     * calls broadcast into an empty emitter list and silently no-op (see broadcast()'s early
     * return), leaving the frontend frozen on the prior terminal state — no progress panel, no
     * Stop button — for the rest of the page session. The registry is keyed per-userId
     * (page-lifetime scope), not per-run, so its lifecycle must be driven by the CLIENT actually
     * disconnecting (register()'s onCompletion/onTimeout wiring, or a failed send in
     * sendEvent()/removeEmitter()), never by a run merely finishing. lastKnown is likewise kept
     * (not evicted) so a genuine reconnect after a completed run replays the real terminal state
     * instead of a synthesized placeholder.
     *
     * <p>Bug fix (WR-02, deferred from Phase 14, closed in Phase 16): this method used to always
     * report {@code processed == total} and never surfaced whether the run was genuinely
     * finished or ended early via {@link #requestStop(UUID)} — a stopped run's terminal panel
     * misleadingly looked 100% complete before vanishing. Now reads {@link
     * #isStopRequested(UUID)} BEFORE {@code stopFlags.remove(userId)} below (reading after would
     * always observe a cleared flag, silently reintroducing this exact bug) and reports the real
     * last-published {@code processed} count instead of always {@code total}.
     */
    public void complete(UUID userId) {
        ProgressState prior = lastKnown.get(userId);
        int total = prior != null ? prior.total() : 0;
        boolean stopped = isStopRequested(userId);
        ProgressState state = new ProgressState(
                prior != null ? prior.processed() : total, total, true,
                prior != null ? prior.lastMovieTitle() : null,
                prior != null ? prior.lastMovieStatus() : null,
                0L, stopped);
        lastKnown.put(userId, state);

        broadcast(userId, "complete", state);

        stopFlags.remove(userId);
        durationWindowsMs.remove(userId);
    }

    /**
     * Test-support only: completes every currently-registered emitter for userId, simulating a
     * genuine client disconnect — the only mechanism that actually completes a wiki-reload SSE
     * emitter's underlying async servlet request in production (see {@link #complete(UUID)}'s
     * javadoc: that method intentionally stopped calling {@code emitter.complete()} as of the
     * wiki-reload-progress-blind-window fix, 2026-08-28, so it can no longer be used to trigger a
     * real ASYNC servlet redispatch). Package-private — used by {@code WikiReloadControllerTest}
     * to exercise the AuthorizationDeniedException-on-async-redispatch regression (debug session
     * sse-auth-denied-on-complete.md) without depending on {@link #complete(UUID)}'s current
     * (deliberately emitter-preserving) behavior.
     */
    void completeAllEmittersForTest(UUID userId) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            for (SseEmitter emitter : userEmitters) {
                emitter.complete();
            }
        }
    }

    /**
     * Resets userId's stop flag to false. MUST be called by batchReload() at the very top of
     * the method, before the per-movie loop, so a fresh Start after a prior Stop never inherits
     * a stale true flag (RESEARCH.md Pitfall 4).
     */
    public void resetRun(UUID userId) {
        stopFlags.put(userId, new AtomicBoolean(false));
    }

    /** Requests that userId's in-progress run stop cleanly at its next loop-boundary check. */
    public void requestStop(UUID userId) {
        stopFlags.computeIfAbsent(userId, id -> new AtomicBoolean()).set(true);
    }

    /** Returns false when no stop-flag entry exists yet for userId, else the flag's current value. */
    public boolean isStopRequested(UUID userId) {
        AtomicBoolean flag = stopFlags.get(userId);
        return flag != null && flag.get();
    }

    private void broadcast(UUID userId, String eventName, ProgressState state) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) {
            return;
        }
        for (SseEmitter emitter : userEmitters) {
            sendEvent(emitter, userId, eventName, state);
        }
    }

    /**
     * Sends a single named SSE event with a JSON-serialized ProgressState payload. On
     * IOException (client disconnected), removes the emitter from the registry and does NOT call
     * emitter.completeWithError() afterward — the container's own AsyncListener machinery
     * already handles completion once send() throws; a manual completeWithError() call after
     * that risks a double-completion error. Returns true if the send succeeded.
     */
    private boolean sendEvent(SseEmitter emitter, UUID userId, String eventName, ProgressState state) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(state, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException e) {
            log.warn("Wiki-reload progress: emitter send failed for userId={}, removing: {}",
                    userId, e.getMessage());
            removeEmitter(userId, emitter);
            return false;
        }
    }

    private void removeEmitter(UUID userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
        }
    }
}
