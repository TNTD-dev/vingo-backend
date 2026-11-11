package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HoldRateLimitTest extends BookingTestSupport {

    @BeforeEach
    void setUp() {
        // Cố định giờ để chuỗi 11 request không vô tình đi qua hai cửa sổ phút.
        clock.set(Instant.parse("2026-11-11T00:00:15Z"));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void holdSeats_limitsEleventhRequestIncludingReplays_andAllowsAnotherBuyer() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        String token = buyerToken(buyer);
        String key = UUID.randomUUID().toString();
        String request = seats(showtime.event().seat(1));

        UUID first = orderId(holdOk(token, showtime.id(), key, request));
        for (int i = 1; i < 10; i++) {
            assertThat(orderId(holdOk(token, showtime.id(), key, request))).isEqualTo(first);
        }

        var limited = hold(token, showtime.id(), key, request);
        limited.expectHeader().value("Retry-After", value -> {
            assertThat(value).matches("[0-9]+");
            assertThat(Integer.parseInt(value)).isBetween(1, 60);
        });
        problem(limited, 429, "RATE_LIMITED");

        UUID other = catalog.buyer();
        holdOk(buyerToken(other), showtime.id(), seats(showtime.event().seat(2)));
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(orderCount(other)).isEqualTo(1);
    }

    @Test
    void holdSeats_countsFailedAttemptsTowardsRateLimit() {
        var showtime = catalog.onSaleShowtime();
        String token = buyerToken(catalog.buyer());
        UUID seat = showtime.event().seat(1);
        var expiry = catalog.now().plusMinutes(10);
        UUID owner = catalog.order(catalog.buyer(), showtime.id(), "PENDING", expiry);
        catalog.setSeat(showtime.id(), seat, "HELD", owner, expiry);

        for (int i = 0; i < 10; i++) {
            problem(hold(token, showtime.id(), seats(seat)), 409, "SEAT_TAKEN");
        }
        problem(hold(token, showtime.id(), seats(seat)), 429, "RATE_LIMITED");
    }

}
