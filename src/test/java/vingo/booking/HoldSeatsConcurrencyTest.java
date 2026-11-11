package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Window;

class HoldSeatsConcurrencyTest extends BookingTestSupport {

    @Test
    @Timeout(90)
    void holdSeats_allowsExactlyOneOfFiftyBuyers_whenSameSeatRequested() throws Exception {
        var showtime = catalog.onSaleShowtime();
        UUID seat = showtime.event().seat(1);
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String token = buyerToken(catalog.buyer());
            requests.add(() -> hold(token, showtime.id(), seats(seat))
                    .expectBody().returnResult().getStatus().value());
        }

        var statuses = race(requests);

        assertThat(statuses).hasSize(50).containsOnly(201, 409);
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(49);
        var state = seatState(showtime.id(), seat);
        assertThat(state.status()).isEqualTo("HELD");
        assertThat(state.orderId()).isNotNull();
        assertThat(orderStatus(state.orderId())).isEqualTo("PENDING");
        assertThat(pendingCount(showtime.id())).isEqualTo(1);
    }

    @Test
    @Timeout(90)
    void holdSeats_allowsExactlyTenOfFiftyBuyers_whenStandingCapacityTen() throws Exception {
        var showtime = catalog.showtime(catalog.event(EventSpec.defaults().withStandingCapacity(10)), Window.onSale());
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String token = buyerToken(catalog.buyer());
            requests.add(() -> hold(token, showtime.id(), standing(showtime.event().standingZoneId(), 1))
                    .expectBody().returnResult().getStatus().value());
        }

        var statuses = race(requests);

        assertThat(statuses).hasSize(50).containsOnly(201, 409);
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(40);
        assertThat(held(showtime)).isEqualTo(10);
        assertThat(pendingCount(showtime.id())).isEqualTo(10);
    }

    @Test
    @Timeout(90)
    void holdSeats_createsOneOrder_whenTenConcurrentRequestsShareBuyerAndIdempotencyKey() throws Exception {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        String token = buyerToken(buyer);
        String key = UUID.randomUUID().toString();
        String request = seats(showtime.event().seat(1));
        List<Callable<UUID>> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(() -> orderId(holdOk(token, showtime.id(), key, request)));
        }

        var ids = race(requests);

        assertThat(ids).hasSize(10).containsOnly(ids.getFirst());
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(pendingCount(showtime.id())).isEqualTo(1);
        assertThat(historyCount(ids.getFirst(), null, "PENDING")).isEqualTo(1);
        assertThat(eventCount(buyer, ids.getFirst(), "order.held")).isEqualTo(1);
    }

    @Test
    @Timeout(180)
    void holdSeats_avoidsDeadlock_whenOverlappingSeatsRequestedInReverseOrderTwentyTimes() throws Exception {
        for (int i = 0; i < 20; i++) {
            var showtime = catalog.onSaleShowtime();
            UUID first = showtime.event().seat(1);
            UUID second = showtime.event().seat(2);
            String tokenA = buyerToken(catalog.buyer());
            String tokenB = buyerToken(catalog.buyer());
            List<Callable<Integer>> requests = List.of(
                    () -> hold(tokenA, showtime.id(), seats(first, second))
                            .expectBody().returnResult().getStatus().value(),
                    () -> hold(tokenB, showtime.id(), seats(second, first))
                            .expectBody().returnResult().getStatus().value());

            var statuses = race(requests);

            assertThat(statuses).as("Iteration %s", i).containsExactlyInAnyOrder(201, 409);
            assertThat(pendingCount(showtime.id())).isEqualTo(1);
            var state = seatState(showtime.id(), first);
            assertThat(state.status()).isEqualTo("HELD");
            assertThat(seatState(showtime.id(), second).status()).isEqualTo("HELD");
            assertThat(seatState(showtime.id(), second).orderId()).isEqualTo(state.orderId());
        }
    }
}
