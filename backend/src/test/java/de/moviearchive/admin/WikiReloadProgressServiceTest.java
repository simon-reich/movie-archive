package de.moviearchive.admin;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Plain JUnit 5 + Mockito unit test for the in-memory SSE emitter registry — no Spring context
 * needed, mirroring {@code BulkImportProgressServiceTest}'s structure. SseEmitter is a
 * concrete, non-final class — mockable via Mockito.mock().
 */
class WikiReloadProgressServiceTest {

    private final WikiReloadProgressService progressService = new WikiReloadProgressService();

    /** Extracts the ProgressState payload from a captured SseEmitter.SseEventBuilder. */
    private WikiReloadProgressService.ProgressState capturedState(SseEmitter.SseEventBuilder builder) {
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            if (part.getData() instanceof WikiReloadProgressService.ProgressState state) {
                return state;
            }
        }
        throw new AssertionError("No ProgressState payload found in SSE event");
    }

    @Test
    void register_withNoPriorState_immediatelySendsSyntheticComplete() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        progressService.register(userId, emitter);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        verify(emitter, times(1)).onCompletion(any());
        verify(emitter, times(1)).onTimeout(any());

        WikiReloadProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isZero();
        assertThat(state.total()).isZero();
        assertThat(state.complete()).isTrue();
        assertThat(state.lastMovieTitle()).isNull();
        assertThat(state.lastMovieStatus()).isNull();
    }

    @Test
    void register_afterPublish_replaysLastPublishedState() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        progressService.publish(userId, 2, 10, "Inception", "SUCCESS", 500L);
        progressService.register(userId, emitter);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());

        WikiReloadProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isEqualTo(2);
        assertThat(state.total()).isEqualTo(10);
        assertThat(state.complete()).isFalse();
        assertThat(state.lastMovieTitle()).isEqualTo("Inception");
        assertThat(state.lastMovieStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andKeepsEmitterOpen() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        // Publish before any emitter is registered: stores lastKnown state, broadcasts to
        // nobody yet (no send() call). Both publishes use the same 1000ms duration so the
        // rolling-average etaSeconds is easy to hand-verify: avg=1000ms * remaining.
        progressService.publish(userId, 1, 10, "Inception", "SUCCESS", 1000L);
        // register() replays the state from the publish above (1st send).
        progressService.register(userId, emitter);
        // 2nd send — a live progress broadcast to the now-registered emitter.
        progressService.publish(userId, 2, 10, "Whiplash", "NOT_FOUND", 1000L);
        // 3rd send — the terminal complete event.
        progressService.complete(userId);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(captor.capture());
        // wiki-reload-progress-blind-window fix: complete() must NOT close the emitter — the
        // page's single SSE subscription must survive to carry future runs' events too.
        verify(emitter, org.mockito.Mockito.never()).complete();

        var states = captor.getAllValues().stream().map(this::capturedState).toList();
        assertThat(states.get(0)).isEqualTo(
                new WikiReloadProgressService.ProgressState(1, 10, false, "Inception", "SUCCESS", 9L));
        assertThat(states.get(1)).isEqualTo(
                new WikiReloadProgressService.ProgressState(2, 10, false, "Whiplash", "NOT_FOUND", 8L));
        assertThat(states.get(2)).isEqualTo(
                new WikiReloadProgressService.ProgressState(10, 10, true, "Whiplash", "NOT_FOUND", 0L));
    }

    @Test
    void register_afterComplete_replaysRealCompletionState_notSyntheticFallback() throws Exception {
        SseEmitter firstEmitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        progressService.publish(userId, 1, 10, "Inception", "SUCCESS", 1000L);
        progressService.register(userId, firstEmitter);
        progressService.publish(userId, 2, 10, "Whiplash", "NOT_FOUND", 1000L);
        progressService.complete(userId);

        // A fresh emitter registering after complete() (e.g. after the client's connection
        // genuinely dropped and it reconnects) must see a replay of the REAL completion state —
        // lastKnown is no longer evicted by complete() (wiki-reload-progress-blind-window fix).
        SseEmitter secondEmitter = mock(SseEmitter.class);
        progressService.register(userId, secondEmitter);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(secondEmitter, times(1)).send(captor.capture());

        WikiReloadProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isEqualTo(10);
        assertThat(state.total()).isEqualTo(10);
        assertThat(state.complete()).isTrue();
        assertThat(state.lastMovieTitle()).isEqualTo("Whiplash");
        assertThat(state.lastMovieStatus()).isEqualTo("NOT_FOUND");
    }

    @Test
    void secondRun_afterComplete_broadcastsToStillRegisteredEmitter_noReReg() throws Exception {
        // Reproduces the exact bug scenario: one SSE connection registered once (as settings.vue
        // does in onMounted), a full run completes, then a SECOND run starts and publishes —
        // all WITHOUT any new register() call. The original emitter must still receive both
        // runs' events, proving the page's single persistent subscription survives a run
        // boundary (wiki-reload-progress-blind-window fix).
        SseEmitter emitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        progressService.register(userId, emitter); // page mount — synthetic complete (1st send)
        progressService.start(userId, 5);           // run 1 starts (2nd send)
        progressService.publish(userId, 5, 5, "Movie A", "SUCCESS", 1000L); // run 1 last movie (3rd send)
        progressService.complete(userId);            // run 1 ends (4th send)

        progressService.start(userId, 3);            // run 2 starts — NO re-register() call (5th send)
        progressService.publish(userId, 1, 3, "Movie B", "SUCCESS", 500L); // (6th send)
        progressService.complete(userId);             // run 2 ends (7th send)

        verify(emitter, times(7)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, org.mockito.Mockito.never()).complete();
    }

    @Test
    void start_publishesZeroProgressState_andDoesNotAffectEtaWindow() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        progressService.start(userId, 382);
        progressService.register(userId, emitter);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());

        WikiReloadProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isZero();
        assertThat(state.total()).isEqualTo(382);
        assertThat(state.complete()).isFalse();
        assertThat(state.etaSeconds()).isZero();

        // A subsequent real publish()'s ETA must be computed purely from its own duration,
        // proving start() did not seed a spurious 0ms entry into the rolling window.
        WikiReloadProgressService.ProgressState afterFirstMovie =
                progressService.publish(userId, 1, 382, "Movie A", "SUCCESS", 1000L);
        assertThat(afterFirstMovie.etaSeconds()).isEqualTo(381L);
    }

    @Test
    void resetRun_withNoPriorRequestStop_isStopRequestedReturnsFalse() {
        UUID userId = UUID.randomUUID();

        progressService.resetRun(userId);

        assertThat(progressService.isStopRequested(userId)).isFalse();
    }

    @Test
    void requestStop_thenIsStopRequested_returnsTrue() {
        UUID userId = UUID.randomUUID();

        progressService.requestStop(userId);

        assertThat(progressService.isStopRequested(userId)).isTrue();
    }

    @Test
    void resetRun_afterPriorRequestStop_clearsFlagBackToFalse() {
        UUID userId = UUID.randomUUID();

        progressService.requestStop(userId);
        assertThat(progressService.isStopRequested(userId)).isTrue();

        progressService.resetRun(userId);

        assertThat(progressService.isStopRequested(userId)).isFalse();
    }

    @Test
    void publish_computesEtaSeconds_asRollingAverageTimesRemaining() {
        UUID userId = UUID.randomUUID();

        // A 5-movie run: durations 1000ms, 2000ms, 3000ms for the first 3 movies processed.
        progressService.publish(userId, 1, 5, "Movie A", "SUCCESS", 1000L);
        progressService.publish(userId, 2, 5, "Movie B", "SUCCESS", 2000L);
        WikiReloadProgressService.ProgressState third =
                progressService.publish(userId, 3, 5, "Movie C", "SUCCESS", 3000L);

        // average(1000, 2000, 3000) = 2000ms; remaining = 5 - 3 = 2 -> etaSeconds = round(2000*2/1000) = 4
        assertThat(third.etaSeconds()).isEqualTo(4L);
    }

    @Test
    void publish_windowCapsAtFiveEntries() {
        UUID userId = UUID.randomUUID();

        // First 5 publishes use a 1000ms duration; the 6th uses a very different 6000ms
        // duration. If the window correctly caps at 5 entries, the oldest 1000ms entry is
        // evicted and the rolling average shifts to reflect only the most recent 5 durations
        // (1000, 1000, 1000, 1000, 6000) -> avg=2000ms, NOT the naive all-6-average (~1833ms).
        progressService.publish(userId, 1, 100, "Movie 1", "SUCCESS", 1000L);
        progressService.publish(userId, 2, 100, "Movie 2", "SUCCESS", 1000L);
        progressService.publish(userId, 3, 100, "Movie 3", "SUCCESS", 1000L);
        progressService.publish(userId, 4, 100, "Movie 4", "SUCCESS", 1000L);
        progressService.publish(userId, 5, 100, "Movie 5", "SUCCESS", 1000L);
        WikiReloadProgressService.ProgressState sixth =
                progressService.publish(userId, 6, 100, "Movie 6", "SUCCESS", 6000L);

        // windowed avg(1000,1000,1000,1000,6000) = 2000ms; remaining = 100-6 = 94
        // -> etaSeconds = round(2000*94/1000) = 188 (not 172, which the naive 6-value average would give)
        assertThat(sixth.etaSeconds()).isEqualTo(188L);
    }
}
