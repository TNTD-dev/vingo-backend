package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vingo.support.CatalogFixture.Showtime;

/**
 * Chặn giữ lại cùng một ghế sau khi đã giữ rồi để hết hạn (hoặc huỷ) quá 3 lần: từ lần thứ 4 phải chờ cooldown.
 * Mặc định: tối đa 3 lần, cửa sổ đếm 1 giờ, cooldown 10 phút tính từ lần nhả gần nhất.
 */
class HoldReholdCooldownTest extends BookingTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-16T03:00:00Z");

    @BeforeEach
    void setUp() {
        clock.set(T0);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    /** Giữ ghế rồi để hết hạn ba lần liên tiếp; kết thúc ở T0 + 33 phút, đơn thứ ba đã quá hạn từ T0 + 32 phút. */
    private void holdAndLetExpireThreeTimes(UUID buyer, Showtime showtime, UUID seat) {
        for (int i = 0; i < 3; i++) {
            holdOk(buyerToken(buyer), showtime.id(), seats(seat));
            clock.advance(Duration.ofMinutes(11));
        }
    }

    @Test
    void holdSeats_returns429WithRetryAfter_whenSameSeatHeldAFourthTimeAfterThreeExpiredHolds() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID seat = showtime.event().seat(1);

        holdAndLetExpireThreeTimes(buyer, showtime, seat);

        var blocked = hold(buyerToken(buyer), showtime.id(), seats(seat));
        blocked.expectHeader().value("Retry-After", value ->
                // Đơn thứ ba hết hạn lúc T0 + 32 phút, cooldown tới T0 + 42 phút, giờ hiện tại là T0 + 33 phút.
                assertThat(Integer.parseInt(value)).isBetween(539, 541));
        problem(blocked, 429, "RATE_LIMITED");
        assertThat(orderCount(buyer)).isEqualTo(3);
    }

    @Test
    void holdSeats_allowsHoldAgain_whenCooldownHasPassed() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID seat = showtime.event().seat(1);
        holdAndLetExpireThreeTimes(buyer, showtime, seat);
        problem(hold(buyerToken(buyer), showtime.id(), seats(seat)), 429, "RATE_LIMITED");

        clock.advance(Duration.ofMinutes(9).plusSeconds(1));

        holdOk(buyerToken(buyer), showtime.id(), seats(seat));
        assertThat(orderCount(buyer)).isEqualTo(4);
    }

    @Test
    void holdSeats_stillBlocksAfterTheFourthHoldExpires_untilAnotherCooldownPasses() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID seat = showtime.event().seat(1);
        holdAndLetExpireThreeTimes(buyer, showtime, seat);
        clock.advance(Duration.ofMinutes(9).plusSeconds(1));
        holdOk(buyerToken(buyer), showtime.id(), seats(seat));

        // Đơn thứ tư hết hạn lúc T0 + 52 phút 1 giây; lúc T0 + 55 phút vẫn trong cooldown mới.
        clock.advance(Duration.ofMinutes(13));

        problem(hold(buyerToken(buyer), showtime.id(), seats(seat)), 429, "RATE_LIMITED");
    }

    @Test
    void holdSeats_doesNotBlockAnotherSeatOrAnotherBuyer_whileSeatIsInCooldown() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID seat = showtime.event().seat(1);
        holdAndLetExpireThreeTimes(buyer, showtime, seat);
        problem(hold(buyerToken(buyer), showtime.id(), seats(seat)), 429, "RATE_LIMITED");

        UUID other = catalog.buyer();
        holdOk(buyerToken(other), showtime.id(), seats(seat));
        holdOk(buyerToken(buyer), showtime.id(), seats(showtime.event().seat(2)));
    }

    @Test
    void holdSeats_rejectsWholeRequest_whenAnyRequestedSeatIsInCooldown() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID seat = showtime.event().seat(1);
        UUID freshSeat = showtime.event().seat(2);
        holdAndLetExpireThreeTimes(buyer, showtime, seat);

        problem(hold(buyerToken(buyer), showtime.id(), seats(seat, freshSeat)), 429, "RATE_LIMITED");

        assertThat(orderCount(buyer)).isEqualTo(3);
        assertAvailable(showtime, freshSeat);
    }

    @Test
    void holdSeats_countsCancelledHoldsTowardsCooldown() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = catalog.buyer();
        UUID seat = showtime.event().seat(1);
        for (int i = 0; i < 3; i++) {
            UUID order = orderId(holdOk(buyerToken(buyer), showtime.id(), seats(seat)));
            body(cancel(buyerToken(buyer), order), 200);
        }

        var blocked = hold(buyerToken(buyer), showtime.id(), seats(seat));

        blocked.expectHeader().value("Retry-After", value ->
                assertThat(Integer.parseInt(value)).isBetween(599, 601));
        problem(blocked, 429, "RATE_LIMITED");
        assertThat(orderCount(buyer)).isEqualTo(3);
    }
}
