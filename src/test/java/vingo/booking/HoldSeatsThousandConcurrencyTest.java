package vingo.booking;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAccumulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class HoldSeatsThousandConcurrencyTest extends BookingTestSupport {
    @Test
    @Timeout(240)
    void holdSeats_allowsOnlyOneOfThousandBuyers_whenReleasedTogether() throws Exception {
        int count = 1000;
        var show = catalog.onSaleShowtime();
        var seat = show.event().seat(1);
        var tokens = new ArrayList<String>();
        for (int i = 0; i < count; i++) tokens.add(buyerToken(catalog.buyer()));
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var first = new LongAccumulator(Long::min, Long.MAX_VALUE);
        var last = new LongAccumulator(Long::max, Long.MIN_VALUE);
        var futures = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String token : tokens) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timeout");
                    long dispatched = System.nanoTime();
                    first.accumulate(dispatched);
                    last.accumulate(dispatched);
                    try {
                        return hold(token, show.id(), seats(seat)).expectBody().returnResult().getStatus().value();
                    } catch (org.springframework.web.client.ResourceAccessException ex) {
                        return 0; // Transport failure: report separately and fail the assertion below.
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            long began = System.nanoTime();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(180, TimeUnit.SECONDS));
            System.out.printf("THOUSAND_RACE requests=%d dispatch_span_ms=%.3f elapsed_ms=%.3f statuses=%s%n",
                    count, (last.get() - first.get()) / 1_000_000.0,
                    (System.nanoTime() - began) / 1_000_000.0,
                    statuses.stream().collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting())));
            assertThat(statuses).hasSize(count).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(count - 1);
            assertThat(pendingCount(show.id())).isEqualTo(1);
            var state = seatState(show.id(), seat);
            assertThat(state.status()).isEqualTo("HELD");
            assertThat(orderStatus(state.orderId())).isEqualTo("PENDING");
        } finally {
            start.countDown();
        }
    }
}
