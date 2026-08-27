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

        progressService.publish(userId, 2, 10, "Inception", "SUCCESS");
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
    void publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andCompletesEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        // Publish before any emitter is registered: stores lastKnown state, broadcasts to
        // nobody yet (no send() call).
        progressService.publish(userId, 1, 10, "Inception", "SUCCESS");
        // register() replays the state from the publish above (1st send).
        progressService.register(userId, emitter);
        // 2nd send — a live progress broadcast to the now-registered emitter.
        progressService.publish(userId, 2, 10, "Whiplash", "NOT_FOUND");
        // 3rd send — the terminal complete event.
        progressService.complete(userId);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(captor.capture());
        verify(emitter, times(1)).complete();

        var states = captor.getAllValues().stream().map(this::capturedState).toList();
        assertThat(states.get(0)).isEqualTo(
                new WikiReloadProgressService.ProgressState(1, 10, false, "Inception", "SUCCESS"));
        assertThat(states.get(1)).isEqualTo(
                new WikiReloadProgressService.ProgressState(2, 10, false, "Whiplash", "NOT_FOUND"));
        assertThat(states.get(2)).isEqualTo(
                new WikiReloadProgressService.ProgressState(10, 10, true, "Whiplash", "NOT_FOUND"));
    }

    @Test
    void register_afterComplete_getsSyntheticCompleteFallback_notReplayOfRealCompletion() throws Exception {
        SseEmitter firstEmitter = mock(SseEmitter.class);
        UUID userId = UUID.randomUUID();

        progressService.publish(userId, 1, 10, "Inception", "SUCCESS");
        progressService.register(userId, firstEmitter);
        progressService.publish(userId, 2, 10, "Whiplash", "NOT_FOUND");
        progressService.complete(userId);

        // A fresh emitter registering after complete() must NOT see a replay of the real
        // completion state (total=10) — lastKnown was evicted by complete(), so it must hit the
        // zero-value synthetic-complete fallback, proving eviction rather than a stale replay.
        SseEmitter secondEmitter = mock(SseEmitter.class);
        progressService.register(userId, secondEmitter);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(secondEmitter, times(1)).send(captor.capture());

        WikiReloadProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isZero();
        assertThat(state.total()).isZero();
        assertThat(state.complete()).isTrue();
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
}
