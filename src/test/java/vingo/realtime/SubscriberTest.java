package vingo.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import vingo.realtime.internal.RealtimeEventRepository.Event;
import vingo.realtime.internal.Subscriber;

class SubscriberTest {

    @Test
    void replay_sendsReplayBeforeBufferedLiveEventsAndDeduplicatesIds() throws Exception {
        UUID user = UUID.randomUUID();
        Event first = event(user);
        Event second = event(user);
        Event live = event(user);
        var replayStarted = new CountDownLatch(1);
        var releaseReplay = new CountDownLatch(1);
        var sent = new LinkedBlockingQueue<UUID>();
        var subscriber = new Subscriber(4, event -> {
            if (event.id().equals(first.id())) {
                replayStarted.countDown();
                await(releaseReplay);
            }
            sent.add(event.id());
        }, () -> { });
        try {
            assertThat(subscriber.offer(second)).isTrue();
            subscriber.replay(List.of(first, second));
            await(replayStarted);

            assertThat(subscriber.offer(second)).isTrue();
            assertThat(subscriber.offer(live)).isTrue();
            assertThat(sent).isEmpty();
            releaseReplay.countDown();

            assertThat(sent.poll(5, TimeUnit.SECONDS)).isEqualTo(first.id());
            assertThat(sent.poll(5, TimeUnit.SECONDS)).isEqualTo(second.id());
            assertThat(sent.poll(5, TimeUnit.SECONDS)).isEqualTo(live.id());
            assertThat(sent.poll(1, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseReplay.countDown();
            subscriber.close();
        }
    }

    @Test
    void offer_closesOnOverflowWithoutBlockingCallerWhenSinkIsBlocked() throws Exception {
        UUID user = UUID.randomUUID();
        var sinkEntered = new CountDownLatch(1);
        var releaseSink = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var subscriber = new Subscriber(1, event -> {
            sinkEntered.countDown();
            await(releaseSink);
        }, closed::countDown);
        try {
            subscriber.replay(List.of());
            assertThat(subscriber.offer(event(user))).isTrue();
            await(sinkEntered);
            assertThat(subscriber.offer(event(user))).isTrue();

            assertTimeoutPreemptively(Duration.ofMillis(500),
                    () -> assertThat(subscriber.offer(event(user))).isFalse());

            assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseSink.getCount()).isEqualTo(1);
            assertTimeoutPreemptively(Duration.ofMillis(500),
                    () -> assertThat(subscriber.offer(event(user))).isFalse());
        } finally {
            releaseSink.countDown();
            subscriber.close();
        }
    }

    private static Event event(UUID user) {
        return new Event(UUID.randomUUID(), user, "order.held", "{}",
                OffsetDateTime.parse("2026-11-12T00:00:00Z"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
