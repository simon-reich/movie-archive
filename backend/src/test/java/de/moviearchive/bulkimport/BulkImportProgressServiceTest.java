package de.moviearchive.bulkimport;

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
 * needed (mirrors this codebase's existing pure-unit-test style for service classes, e.g.
 * BulkImportServiceTest). SseEmitter is a concrete, non-final class — mockable via
 * Mockito.mock().
 */
class BulkImportProgressServiceTest {

    private final BulkImportProgressService progressService = new BulkImportProgressService();

    /** Extracts the ProgressState payload from a captured SseEmitter.SseEventBuilder. */
    private BulkImportProgressService.ProgressState capturedState(SseEmitter.SseEventBuilder builder) {
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            if (part.getData() instanceof BulkImportProgressService.ProgressState state) {
                return state;
            }
        }
        throw new AssertionError("No ProgressState payload found in SSE event");
    }

    @Test
    void register_withNoPriorState_immediatelySendsSyntheticComplete() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID batchId = UUID.randomUUID();

        progressService.register(batchId, emitter, 5);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        verify(emitter, times(1)).onCompletion(any());
        verify(emitter, times(1)).onTimeout(any());

        BulkImportProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isEqualTo(5);
        assertThat(state.total()).isEqualTo(5);
        assertThat(state.complete()).isTrue();
    }

    @Test
    void register_afterPublish_replaysLastPublishedState() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID batchId = UUID.randomUUID();

        progressService.publish(batchId, 2, 10);
        progressService.register(batchId, emitter, 10);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());

        BulkImportProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isEqualTo(2);
        assertThat(state.total()).isEqualTo(10);
        assertThat(state.complete()).isFalse();
    }

    @Test
    void publishThenRegisterThenPublishThenComplete_sendsThreeEvents_andCompletesEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID batchId = UUID.randomUUID();

        // Publish before any emitter is registered: stores lastKnown state, broadcasts to
        // nobody yet (no send() call).
        progressService.publish(batchId, 1, 10);
        // register() replays the state from the publish above (1st send — a "progress" replay,
        // not a synthetic complete, since lastKnown already exists).
        progressService.register(batchId, emitter, 10);
        // 2nd send — a live progress broadcast to the now-registered emitter.
        progressService.publish(batchId, 2, 10);
        // 3rd send — the terminal complete event.
        progressService.complete(batchId);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(captor.capture());
        verify(emitter, times(1)).complete();

        var states = captor.getAllValues().stream().map(this::capturedState).toList();
        assertThat(states.get(0)).isEqualTo(new BulkImportProgressService.ProgressState(1, 10, false));
        assertThat(states.get(1)).isEqualTo(new BulkImportProgressService.ProgressState(2, 10, false));
        assertThat(states.get(2)).isEqualTo(new BulkImportProgressService.ProgressState(10, 10, true));
    }

    @Test
    void register_afterComplete_getsSyntheticCompleteFallback_notReplayOfRealCompletion() throws Exception {
        SseEmitter firstEmitter = mock(SseEmitter.class);
        UUID batchId = UUID.randomUUID();

        progressService.publish(batchId, 1, 10);
        progressService.register(batchId, firstEmitter, 10);
        progressService.publish(batchId, 2, 10);
        progressService.complete(batchId);

        // A fresh emitter registering after complete() must NOT see a replay of the real
        // completion state (total=10) — lastKnown was evicted by complete(), so it must hit the
        // synthetic-complete fallback using the NEW totalLinesFallback passed here (99),
        // proving eviction rather than a stale replay.
        SseEmitter secondEmitter = mock(SseEmitter.class);
        progressService.register(batchId, secondEmitter, 99);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(secondEmitter, times(1)).send(captor.capture());

        BulkImportProgressService.ProgressState state = capturedState(captor.getValue());
        assertThat(state.processed()).isEqualTo(99);
        assertThat(state.total()).isEqualTo(99);
        assertThat(state.complete()).isTrue();
    }
}
