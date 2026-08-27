package de.moviearchive.admin;

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
 */
@Service
@Slf4j
public class WikiReloadProgressService {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressState> lastKnown = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    /** SSE JSON payload shape — Jackson serializes records natively, no extra annotation needed. */
    public record ProgressState(int processed, int total, boolean complete,
                                 String lastMovieTitle, String lastMovieStatus) {
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
            ProgressState synthesized = new ProgressState(0, 0, true, null, null);
            sendEvent(emitter, userId, "complete", synthesized);
        }
    }

    /**
     * Stores the given progress as userId's last-known state and sends a "progress" event to
     * every currently registered emitter for userId.
     */
    public void publish(UUID userId, int processed, int total, String lastMovieTitle, String lastMovieStatus) {
        ProgressState state = new ProgressState(processed, total, false, lastMovieTitle, lastMovieStatus);
        lastKnown.put(userId, state);
        broadcast(userId, "progress", state);
    }

    /**
     * Stores a terminal (total, total, true, ...) state as userId's last-known state, sends a
     * "complete" event to every registered emitter, calls emitter.complete() on each (unless its
     * send() already failed — see sendEvent()), then removes the emitter list, the lastKnown
     * entry, AND the stop-flag entry for userId. A later register() for this userId correctly
     * hits the synthesized-complete fallback above, since the run is genuinely done.
     */
    public void complete(UUID userId) {
        ProgressState prior = lastKnown.get(userId);
        int total = prior != null ? prior.total() : 0;
        ProgressState state = new ProgressState(
                total, total, true,
                prior != null ? prior.lastMovieTitle() : null,
                prior != null ? prior.lastMovieStatus() : null);
        lastKnown.put(userId, state);

        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            for (SseEmitter emitter : userEmitters) {
                if (sendEvent(emitter, userId, "complete", state)) {
                    emitter.complete();
                }
            }
        }
        emitters.remove(userId);
        lastKnown.remove(userId);
        stopFlags.remove(userId);
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
