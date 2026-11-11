package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

class ExpireHoldsTest extends BookingTestSupport {

    private final BookingService booking;

    @Autowired
    ExpireHoldsTest(BookingService booking) {
        this.booking = booking;
    }

    @Test
    void expireOverdueHolds_expiresSeatedAndStandingOrdersAndPublishesOneEventEach() {
        var showtime = catalog.onSaleShowtime();
        UUID seatedBuyer = catalog.buyer();
        UUID standingBuyer = catalog.buyer();
        UUID seated = orderId(holdOk(buyerToken(seatedBuyer), showtime.id(), seats(showtime.event().seat(1))));
        UUID standing = orderId(holdOk(buyerToken(standingBuyer), showtime.id(),
                standing(showtime.event().standingZoneId(), 3)));
        makeOverdue(seated);
        makeOverdue(standing);

        booking.expireOverdueHolds(100);

        assertThat(orderStatus(seated)).isEqualTo("EXPIRED");
        assertThat(orderStatus(standing)).isEqualTo("EXPIRED");
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(held(showtime)).isZero();
        for (UUID id : List.of(seated, standing)) {
            assertThat(historyCount(id, "PENDING", "EXPIRED")).isEqualTo(1);
            assertHistoryActor(id, "EXPIRED", null);
        }
        assertThat(eventCount(seatedBuyer, seated, "order.expired")).isEqualTo(1);
        assertThat(eventCount(standingBuyer, standing, "order.expired")).isEqualTo(1);
    }

    @Test
    void expireOverdueHolds_isIdempotent_whenRunTwice() {
        var showtime = catalog.onSaleShowtime();
        UUID seatedBuyer = catalog.buyer();
        UUID standingBuyer = catalog.buyer();
        UUID seated = orderId(holdOk(buyerToken(seatedBuyer), showtime.id(), seats(showtime.event().seat(1))));
        UUID standing = orderId(holdOk(buyerToken(standingBuyer), showtime.id(),
                standing(showtime.event().standingZoneId(), 2)));
        makeOverdue(seated);
        makeOverdue(standing);
        booking.expireOverdueHolds(100);
        var released = seatState(showtime.id(), showtime.event().seat(1));

        booking.expireOverdueHolds(100);

        assertThat(orderStatus(seated)).isEqualTo("EXPIRED");
        assertThat(orderStatus(standing)).isEqualTo("EXPIRED");
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(released);
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(held(showtime)).isZero();
        assertThat(historyCount(seated, "PENDING", "EXPIRED")).isEqualTo(1);
        assertThat(historyCount(standing, "PENDING", "EXPIRED")).isEqualTo(1);
        assertThat(eventCount(seatedBuyer, seated, "order.expired")).isEqualTo(1);
        assertThat(eventCount(standingBuyer, standing, "order.expired")).isEqualTo(1);
    }

    @Test
    void expireOverdueHolds_leavesLiveSeatedAndStandingOrdersUntouched() {
        var showtime = catalog.onSaleShowtime();
        UUID seatedBuyer = catalog.buyer();
        UUID standingBuyer = catalog.buyer();
        UUID seated = orderId(holdOk(buyerToken(seatedBuyer), showtime.id(), seats(showtime.event().seat(1))));
        UUID standing = orderId(holdOk(buyerToken(standingBuyer), showtime.id(),
                standing(showtime.event().standingZoneId(), 2)));
        var before = seatState(showtime.id(), showtime.event().seat(1));

        booking.expireOverdueHolds(100);

        assertThat(orderStatus(seated)).isEqualTo("PENDING");
        assertThat(orderStatus(standing)).isEqualTo("PENDING");
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(before);
        assertThat(held(showtime)).isEqualTo(2);
        assertThat(historyCount(seated, "PENDING", "EXPIRED")).isZero();
        assertThat(historyCount(standing, "PENDING", "EXPIRED")).isZero();
        assertThat(eventCount(seatedBuyer, seated, "order.expired")).isZero();
        assertThat(eventCount(standingBuyer, standing, "order.expired")).isZero();
    }

    @Test
    void expireOverdueHolds_skipsPaidOrderEvenWhenExpiryIsInPast() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID id = orderId(holdOk(buyerToken(buyer), showtime.id(), seats(showtime.event().seat(1))));
        makeOverdue(id);
        jdbc.sql("UPDATE orders SET status = 'PAID', paid_at = :now WHERE id = :id")
                .param("now", catalog.now()).param("id", id).update();
        catalog.setSeat(showtime.id(), showtime.event().seat(1), "SOLD", id, null);
        var before = seatState(showtime.id(), showtime.event().seat(1));

        booking.expireOverdueHolds(100);

        assertThat(orderStatus(id)).isEqualTo("PAID");
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(before);
        assertThat(historyCount(id, "PENDING", "EXPIRED")).isZero();
        assertThat(eventCount(buyer, id, "order.expired")).isZero();
    }

    @Test
    void expireOverdueHolds_preservesSeatAlreadyReheldByNewOrder() {
        var showtime = catalog.onSaleShowtime();
        UUID oldBuyer = catalog.buyer();
        UUID old = orderId(holdOk(buyerToken(oldBuyer), showtime.id(), seats(showtime.event().seat(1))));
        makeOverdue(old);
        UUID fresh = orderId(holdOk(buyerToken(catalog.buyer()), showtime.id(), seats(showtime.event().seat(1))));
        var before = seatState(showtime.id(), showtime.event().seat(1));

        booking.expireOverdueHolds(100);

        assertThat(orderStatus(old)).isEqualTo("EXPIRED");
        assertThat(orderStatus(fresh)).isEqualTo("PENDING");
        assertThat(before.status()).isEqualTo("HELD");
        assertThat(before.orderId()).isEqualTo(fresh);
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(before);
        assertThat(historyCount(old, "PENDING", "EXPIRED")).isEqualTo(1);
        assertThat(eventCount(oldBuyer, old, "order.expired")).isEqualTo(1);
    }

    @Test
    @Timeout(90)
    void expireOverdueHolds_andCancelProduceExactlyOneTerminalTransition_whenRacing() throws Exception {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        String token = buyerToken(buyer);
        UUID id = orderId(holdOk(token, showtime.id(), standing(showtime.event().standingZoneId(), 3)));
        makeOverdue(id);
        List<Callable<Integer>> actions = List.of(
                () -> cancel(token, id).expectBody().returnResult().getStatus().value(),
                () -> booking.expireOverdueHolds(100));

        var results = race(actions);

        String status = orderStatus(id);
        assertThat(status).isIn("CANCELLED", "EXPIRED");
        assertThat(results.getFirst()).isEqualTo(status.equals("CANCELLED") ? 200 : 409);
        assertThat(held(showtime)).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM order_status_history WHERE order_id = :id AND from_status = 'PENDING'
                        """)
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(historyCount(id, "PENDING", status)).isEqualTo(1);
        assertHistoryActor(id, status, status.equals("CANCELLED") ? buyer : null);
        assertThat(eventCount(buyer, id, "order.expired")).isEqualTo(status.equals("EXPIRED") ? 1 : 0);
        booking.expireOverdueHolds(100);
        assertThat(orderStatus(id)).isEqualTo(status);
        assertThat(held(showtime)).isZero();
        assertThat(historyCount(id, "PENDING", status)).isEqualTo(1);
    }

    @Test
    @Timeout(90)
    void expireOverdueHolds_preservesNewHold_whenJobRacesWithHoldOnExpiredSeat() throws Exception {
        var showtime = catalog.onSaleShowtime();
        UUID oldBuyer = catalog.buyer();
        UUID old = orderId(holdOk(buyerToken(oldBuyer), showtime.id(), seats(showtime.event().seat(1))));
        makeOverdue(old);
        String freshToken = buyerToken(catalog.buyer());
        List<Callable<String>> actions = List.of(
                () -> holdOk(freshToken, showtime.id(), seats(showtime.event().seat(1))),
                () -> { booking.expireOverdueHolds(100); return "expired"; });

        var results = race(actions);

        UUID fresh = orderId(results.getFirst());
        assertThat(fresh).isNotEqualTo(old);
        assertThat(orderStatus(old)).isEqualTo("EXPIRED");
        assertThat(orderStatus(fresh)).isEqualTo("PENDING");
        var state = seatState(showtime.id(), showtime.event().seat(1));
        assertThat(state.status()).isEqualTo("HELD");
        assertThat(state.orderId()).isEqualTo(fresh);
        assertNear(state.heldUntil(), java.time.OffsetDateTime.parse(text(results.getFirst(), "$.expires_at")));
        assertThat(pendingCount(showtime.id())).isEqualTo(1);
        assertThat(historyCount(old, "PENDING", "EXPIRED")).isEqualTo(1);
        assertThat(eventCount(oldBuyer, old, "order.expired")).isEqualTo(1);
    }
}
