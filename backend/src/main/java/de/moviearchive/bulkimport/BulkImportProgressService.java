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

/**
 * In-memory SSE emitter registry for live bulk-import progress (D-01, IMPORT-05).
 *
 * This app runs as a single backend instance with no queue infrastructure (CLAUDE.md's own
 * "Async: @Async + @Retryable (no queue infrastructure)" line) — an in-process
 * Map&lt;UUID, List&lt;SseEmitter&gt;&gt; is the correct minimal solution (RESEARCH.md "Don't
 * Hand-Roll"), not a distributed pub/sub broker.
 *
 * Tracks the last-known (processed, total, complete) state per batchId so a client that
 * connects (or reconnects) mid-import immediately sees current progress instead of waiting for
 * the next publish() (RESEARCH.md Open Question 1). complete() evicts both the emitter list and
 * the lastKnown entry to bound memory growth across app uptime (T-11-05) — a register() call for
 * a batchId with no lastKnown entry (never run this process lifetime, or already evicted after
 * completion) synthesizes an immediate "complete" event instead of leaving the client waiting
 * forever, since this app's single global run-slot (bulkImportExecutor core=1/max=1) means an
 * untracked batchId is, for all practical purposes, not currently running.
 */
@Service
@Slf4j
public class BulkImportProgressService {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressState> lastKnown = new ConcurrentHashMap<>();

    /** SSE JSON payload shape — Jackson serializes records natively, no extra annotation needed. */
    public record ProgressState(int processed, int total, boolean complete) {
    }

    /**
     * Registers a newly-opened SSE connection for batchId. Wires onCompletion/onTimeout to
     * remove the emitter from the registry (prevents unbounded growth across app uptime,
     * T-11-05). Replays the last-known state immediately if one exists for batchId (reconnect /
     * already-in-flight case); otherwise synthesizes and sends an immediate "complete" event
     * using totalLinesFallback for both processed and total.
     */
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

    /**
     * Stores the given (processed, total) as batchId's last-known state and sends a "progress"
     * event to every currently registered emitter for batchId.
     */
    public void publish(UUID batchId, int processed, int total) {
        ProgressState state = new ProgressState(processed, total, false);
        lastKnown.put(batchId, state);
        broadcast(batchId, "progress", state);
    }

    /**
     * Stores a terminal (total, total, true) state as batchId's last-known state, sends a
     * "complete" event to every registered emitter, calls emitter.complete() on each (unless its
     * send() already failed — see sendEvent()), then removes both the emitter list and the
     * lastKnown entry for batchId. A later register() for this batchId correctly hits the
     * synthesized-complete fallback above, since the batch is genuinely done.
     */
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

    /**
     * Sends a single named SSE event with a JSON-serialized ProgressState payload. On
     * IOException (client disconnected), removes the emitter from the registry and does NOT call
     * emitter.completeWithError() afterward — the container's own AsyncListener machinery
     * already handles completion once send() throws (RESEARCH.md Anti-Patterns); a manual
     * completeWithError() call after that risks a double-completion error. Returns true if the
     * send succeeded.
     */
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
