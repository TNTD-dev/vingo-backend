package vingo.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OutboxJobTest {
    @Test
    void scheduledRun_drainsMultipleMessagesButLeavesRoomForOtherWorkerWork() {
        var dispatcher = mock(OutboxDispatcher.class);
        when(dispatcher.dispatchOne()).thenReturn(true);

        new OutboxJob(dispatcher).sendPending();

        verify(dispatcher, times(20)).dispatchOne();
    }

    @Test
    void scheduledRun_stopsWhenNoMoreMessagesAreDue() {
        var dispatcher = mock(OutboxDispatcher.class);
        when(dispatcher.dispatchOne()).thenReturn(true, true, false);

        new OutboxJob(dispatcher).sendPending();

        verify(dispatcher, times(3)).dispatchOne();
    }

    @Test
    void scheduledRun_stopsStartingNewMessagesAfterTimeBudget_evenBelowBatchLimit() {
        var elapsed = new AtomicLong();
        var dispatcher = mock(OutboxDispatcher.class);
        when(dispatcher.dispatchOne()).thenAnswer(invocation -> {
            elapsed.addAndGet(Duration.ofSeconds(16).toNanos());
            return true;
        });

        new OutboxJob(dispatcher, elapsed::get).sendPending();

        verify(dispatcher, times(2)).dispatchOne();
        assertThat(elapsed.get()).isLessThan(Duration.ofMinutes(5).toNanos());
    }

    @Test
    void scheduledRun_preservesInterruptionAndDoesNotStartAnotherDeliveryDuringShutdown() {
        var dispatcher = mock(OutboxDispatcher.class);
        when(dispatcher.dispatchOne()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return true;
        });
        try {
            new OutboxJob(dispatcher).sendPending();
            verify(dispatcher).dispatchOne();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
