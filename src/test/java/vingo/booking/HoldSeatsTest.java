package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static vingo.support.CatalogFixture.GA_PRICE;
import static vingo.support.CatalogFixture.VIP_PRICE;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vingo.support.CatalogFixture.EventSpec;
import vingo.support.CatalogFixture.Showtime;
import vingo.support.CatalogFixture.Window;

class HoldSeatsTest extends BookingTestSupport {

    private Showtime showtime;
    private UUID buyer;
    private String token;

    @BeforeEach
    void createBuyerAndShowtime() {
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        token = buyerToken(buyer);
    }

    @Test
    void holdSeats_returns201_withPendingOrderPricesAndTenMinuteExpiry() {
        var event = showtime.event();
        var expectedExpiry = catalog.now().plusMinutes(10);
        String json = holdOk(token, showtime.id(), seats(event.seat(1), event.seat(2)));
        UUID id = orderId(json);
        var expiresAt = OffsetDateTime.parse(text(json, "$.expires_at"));

        assertThat(text(json, "$.status")).isEqualTo("PENDING");
        assertThat(text(json, "$.code")).hasSize(6);
        assertThat(text(json, "$.currency")).isEqualTo("VND");
        assertThat(number(json, "$.total_amount")).isEqualTo(2 * VIP_PRICE);
        assertThat(strings(json, "$.items[*].seat_id"))
                .containsExactlyInAnyOrder(event.seat(1).toString(), event.seat(2).toString());
        assertThat(strings(json, "$.items[*].tier")).containsExactly("VIP", "VIP");
        assertThat(number(json, "$.items[0].unit_price")).isEqualTo(VIP_PRICE);
        assertThat(number(json, "$.items[1].unit_price")).isEqualTo(VIP_PRICE);
        assertThat(text(json, "$.showtime.id")).isEqualTo(showtime.id().toString());
        assertThat(text(json, "$.event.id")).isEqualTo(event.id().toString());
        assertNear(expiresAt, expectedExpiry);
        assertThat(orderStatus(id)).isEqualTo("PENDING");
        for (UUID seat : new UUID[]{event.seat(1), event.seat(2)}) {
            var state = seatState(showtime.id(), seat);
            assertThat(state.status()).isEqualTo("HELD");
            assertThat(state.orderId()).isEqualTo(id);
            assertNear(state.heldUntil(), expiresAt);
        }
        assertAvailable(showtime, event.seats().subList(2, 10).toArray(UUID[]::new));
    }

    @Test
    void holdSeats_recordsInitialHistoryAndExactlyOneHeldEvent() {
        UUID id = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1))));

        assertThat(historyCount(id, null, "PENDING")).isEqualTo(1);
        assertThat(eventCount(buyer, id, "order.held")).isEqualTo(1);
    }

    @Test
    void holdSeats_returns201_whenAnotherBuyersHeldSeatHasExpired() {
        UUID seat = showtime.event().seat(1);
        var past = catalog.now().minusMinutes(1);
        UUID old = catalog.order(catalog.buyer(), showtime.id(), "PENDING", past);
        catalog.setSeat(showtime.id(), seat, "HELD", old, past);

        UUID id = orderId(holdOk(token, showtime.id(), seats(seat)));

        assertThat(seatState(showtime.id(), seat).status()).isEqualTo("HELD");
        assertThat(seatState(showtime.id(), seat).orderId()).isEqualTo(id).isNotEqualTo(old);
    }

    @Test
    void holdSeats_holdsBothCoupleSeats_whenOnlySeatNineRequested() {
        var event = showtime.event();
        String json = holdOk(token, showtime.id(), seats(event.seat(9)));
        UUID id = orderId(json);

        assertThat(strings(json, "$.items[*].seat_id"))
                .containsExactlyInAnyOrder(event.seat(9).toString(), event.seat(10).toString());
        assertThat(number(json, "$.total_amount")).isEqualTo(2 * VIP_PRICE);
        for (UUID seat : new UUID[]{event.seat(9), event.seat(10)}) {
            assertThat(seatState(showtime.id(), seat).status()).isEqualTo("HELD");
            assertThat(seatState(showtime.id(), seat).orderId()).isEqualTo(id);
        }
    }

    @Test
    void holdSeats_deduplicatesCoupleExpansion_whenBothCoupleSeatsRequested() {
        var limited = limitedShowtime();

        String json = holdOk(token, limited.id(), seats(limited.event().seat(9), limited.event().seat(10)));

        assertThat(strings(json, "$.items[*].seat_id")).containsExactlyInAnyOrder(
                limited.event().seat(9).toString(), limited.event().seat(10).toString());
        assertThat(number(json, "$.total_amount")).isEqualTo(2 * VIP_PRICE);
        assertThat(jdbc.sql("SELECT count(*) FROM order_item WHERE order_id = :id")
                .param("id", orderId(json)).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void holdSeats_expiresOwnOverdueOrderAndReleasesItsOtherSeat() {
        UUID old = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1))));
        makeOverdue(old);

        UUID fresh = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(2))));

        assertThat(fresh).isNotEqualTo(old);
        assertThat(orderStatus(old)).isEqualTo("EXPIRED");
        assertThat(historyCount(old, "PENDING", "EXPIRED")).isEqualTo(1);
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(seatState(showtime.id(), showtime.event().seat(2)).orderId()).isEqualTo(fresh);
        assertThat(orderCount(buyer)).isEqualTo(2);
        assertThat(pendingCount(showtime.id())).isEqualTo(1);
    }

    @Test
    void holdSeats_returns409_whenSeatAlreadyHeld() {
        UUID taken = showtime.event().seat(1);
        occupy(taken, "HELD");

        String json = problem(hold(token, showtime.id(), seats(taken, showtime.event().seat(2))), 409, "SEAT_TAKEN");

        assertThat(strings(json, "$.taken_seat_ids")).containsExactly(taken.toString());
        var suggested = strings(json, "$.suggested_seat_ids");
        assertThat(suggested).hasSize(2).doesNotHaveDuplicates().doesNotContain(taken.toString());
        for (String seat : suggested) {
            UUID id = UUID.fromString(seat);
            assertThat(showtime.event().seats()).contains(id);
            assertAvailable(showtime, id);
        }
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_rollsBackAllSeatsAndOrder_whenOneOfThreeSeatsIsTaken() {
        var event = showtime.event();
        occupy(event.seat(2), "HELD");

        problem(hold(token, showtime.id(), seats(event.seat(1), event.seat(2), event.seat(3))), 409, "SEAT_TAKEN");

        assertAvailable(showtime, event.seat(1), event.seat(3));
        assertThat(orderCount(buyer)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM realtime_event WHERE user_id = :user")
                .param("user", buyer).query(Integer.class).single()).isZero();
    }

    @Test
    void holdSeats_returns409_whenSeatSold() {
        occupy(showtime.event().seat(1), "SOLD");
        assertSeatTaken(showtime.event().seat(1));
    }

    @Test
    void holdSeats_returns409_whenSeatBlocked() {
        catalog.setSeat(showtime.id(), showtime.event().seat(1), "BLOCKED", null, null);
        assertSeatTaken(showtime.event().seat(1));
    }

    @Test
    void holdSeats_returns409_withoutHoldingSeatNine_whenCoupleSeatTenTaken() {
        occupy(showtime.event().seat(10), "HELD");
        var before = seatState(showtime.id(), showtime.event().seat(10));

        String json = problem(hold(token, showtime.id(), seats(showtime.event().seat(9))), 409, "SEAT_TAKEN");

        assertThat(strings(json, "$.taken_seat_ids")).containsExactly(showtime.event().seat(10).toString());
        assertAvailable(showtime, showtime.event().seat(9));
        assertThat(seatState(showtime.id(), showtime.event().seat(10))).isEqualTo(before);
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_returns201_andIncrementsStandingHeldWithOneQuantityItem() {
        catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 0, 1);

        String json = holdOk(token, showtime.id(), standing(showtime.event().standingZoneId(), 3));

        assertThat(text(json, "$.status")).isEqualTo("PENDING");
        assertThat(number(json, "$.total_amount")).isEqualTo(3 * GA_PRICE);
        assertThat(held(showtime)).isEqualTo(4);
        var items = jdbc.sql("SELECT seat_id, quantity, unit_price FROM order_item WHERE order_id = :id")
                .param("id", orderId(json)).query().listOfRows();
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("seat_id", null).containsEntry("quantity", 3)
                .containsEntry("unit_price", GA_PRICE);
    }

    @Test
    void holdSeats_returns409WithSeatsLeft_whenStandingStockInsufficient() {
        catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 2, 2);

        String json = problem(hold(token, showtime.id(), standing(showtime.event().standingZoneId(), 2)),
                409, "NOT_ENOUGH_STOCK");

        assertThat(number(json, "$.seats_left")).isEqualTo(1);
        assertThat(held(showtime)).isEqualTo(2);
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_returns409_whenSaleNotOpenYet() { assertNotOnSale(Window.notOpenYet()); }

    @Test
    void holdSeats_returns409_whenSalesClosed() { assertNotOnSale(Window.salesClosed()); }

    @Test
    void holdSeats_returns409_whenShowtimeCancelled() { assertNotOnSale(Window.cancelled()); }

    @Test
    void holdSeats_returns409_whenEventCancelledDespiteOnSaleShowtime() {
        var cancelled = catalog.showtime(catalog.event(EventSpec.defaults().withStatus("CANCELLED")), Window.onSale());

        problem(hold(token, cancelled.id(), seats(cancelled.event().seat(1))), 409, "SHOWTIME_NOT_ON_SALE");

        assertAvailable(cancelled, cancelled.event().seats().toArray(UUID[]::new));
        assertThat(held(cancelled)).isZero();
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_returns409_whenShowtimeFinished() { assertNotOnSale(Window.finished()); }

    @Test
    void holdSeats_returns404_whenShowtimeDoesNotExist() {
        problem(hold(token, UUID.randomUUID(), seats(showtime.event().seat(1))), 404, "NOT_FOUND");
        assertAvailable(showtime, showtime.event().seats().toArray(UUID[]::new));
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_returns404_whenShowtimeDraft() {
        var draft = catalog.showtime(showtime.event(), Window.draft());
        problem(hold(token, draft.id(), seats(draft.event().seat(1))), 404, "NOT_FOUND");
        assertAvailable(draft, draft.event().seats().toArray(UUID[]::new));
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_returns422_whenThreeSeatsExceedEventMaximumTwo() {
        var limited = limitedShowtime();
        assertMaximum(limited, seats(limited.event().seat(1), limited.event().seat(2), limited.event().seat(3)));
    }

    @Test
    void holdSeats_returns422_whenCoupleExpansionExceedsEventMaximumTwo() {
        var limited = limitedShowtime();
        assertMaximum(limited, seats(limited.event().seat(8), limited.event().seat(9)));
    }

    @Test
    void holdSeats_returns422_whenStandingQuantityExceedsEventMaximumTwo() {
        var limited = limitedShowtime();
        assertMaximum(limited, standing(limited.event().standingZoneId(), 3));
    }

    @Test
    void holdSeats_returns409_whenBuyerAlreadyHasLivePendingOrderForShowtime() {
        UUID first = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1))));

        problem(hold(token, showtime.id(), seats(showtime.event().seat(2))), 409, "ORDER_STATE_INVALID");

        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(orderStatus(first)).isEqualTo("PENDING");
        assertAvailable(showtime, showtime.event().seat(2));
    }

    @Test
    void holdSeats_returnsSame201Order_whenIdempotencyKeyAndBodyRepeated() {
        String key = UUID.randomUUID().toString();
        String request = seats(showtime.event().seat(1));
        UUID first = orderId(holdOk(token, showtime.id(), key, request));

        UUID second = orderId(holdOk(token, showtime.id(), key, request));

        assertThat(second).isEqualTo(first);
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(historyCount(first, null, "PENDING")).isEqualTo(1);
        assertThat(eventCount(buyer, first, "order.held")).isEqualTo(1);
    }

    @Test
    void holdSeats_returns409_whenIdempotencyKeyReusedWithDifferentBody() {
        String key = UUID.randomUUID().toString();
        holdOk(token, showtime.id(), key, seats(showtime.event().seat(1)));

        problem(hold(token, showtime.id(), key, seats(showtime.event().seat(2))), 409, "IDEMPOTENCY_CONFLICT");

        assertAvailable(showtime, showtime.event().seat(2));
        assertThat(orderCount(buyer)).isEqualTo(1);
    }

    @Test
    void holdSeats_allowsRetryWithSameKey_afterFailedAttempt() {
        UUID seat = showtime.event().seat(1);
        String key = UUID.randomUUID().toString();
        String request = seats(seat);
        occupy(seat, "HELD");

        problem(hold(token, showtime.id(), key, request), 409, "SEAT_TAKEN");

        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key WHERE user_id = :user AND key = :key")
                .param("user", buyer).param("key", key).query(Integer.class).single()).isZero();
        catalog.setSeat(showtime.id(), seat, "AVAILABLE", null, null);

        UUID id = orderId(holdOk(token, showtime.id(), key, request));

        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(seatState(showtime.id(), seat).orderId()).isEqualTo(id);
        assertThat(historyCount(id, null, "PENDING")).isEqualTo(1);
        assertThat(eventCount(buyer, id, "order.held")).isEqualTo(1);
    }

    @Test
    void holdSeats_treatsSameIdempotencyKeyIndependentlyForDifferentBuyers() {
        String key = UUID.randomUUID().toString();
        UUID other = catalog.buyer();
        UUID first = orderId(holdOk(token, showtime.id(), key, seats(showtime.event().seat(1))));
        UUID second = orderId(holdOk(buyerToken(other), showtime.id(), key, seats(showtime.event().seat(2))));

        assertThat(second).isNotEqualTo(first);
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(orderCount(other)).isEqualTo(1);
    }

    @Test
    void holdSeats_returns400_whenBothSeatedAndStandingFormsPresent() {
        assertValidation("{\"seat_ids\":[\"%s\"],\"zone_id\":\"%s\",\"quantity\":1}"
                .formatted(showtime.event().seat(1), showtime.event().standingZoneId()));
    }

    @Test
    void holdSeats_returns400_whenBodyEmpty() { assertValidation("{}"); }

    @Test
    void holdSeats_returns400_whenZoneIdMissingQuantity() {
        assertValidation("{\"zone_id\":\"%s\"}".formatted(showtime.event().standingZoneId()));
    }

    @Test
    void holdSeats_returns400_whenQuantityMissingZoneId() { assertValidation("{\"quantity\":1}"); }

    @Test
    void holdSeats_returns400_whenSeatIdsEmpty() { assertValidation(seats()); }

    @Test
    void holdSeats_returns400_whenSeatBelongsToDifferentShowtime() {
        var other = catalog.onSaleShowtime();
        assertValidation(seats(other.event().seat(1)));
        assertAvailable(other, other.event().seat(1));
    }

    @Test
    void holdSeats_returns400_whenStandingZoneIdRefersToSeatedZone() {
        assertValidation(standing(showtime.event().seatedZoneId(), 1));
    }

    @Test
    void holdSeats_returns400_whenQuantityTwentyOne() {
        assertValidation(standing(showtime.event().standingZoneId(), 21));
    }

    @Test
    void holdSeats_returns400_whenIdempotencyKeyMissing() {
        problem(hold(token, showtime.id(), null, seats(showtime.event().seat(1))), 400, "VALIDATION_ERROR");
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(orderCount(buyer)).isZero();
    }

    @Test
    void holdSeats_returns401_whenTokenMissing() {
        problem(hold(null, showtime.id(), seats(showtime.event().seat(1))), 401, "UNAUTHORIZED");
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(pendingCount(showtime.id())).isZero();
    }

    private void occupy(UUID seat, String status) {
        var expires = catalog.now().plusMinutes(10);
        UUID order = catalog.order(catalog.buyer(), showtime.id(), status.equals("SOLD") ? "PAID" : "PENDING", expires);
        catalog.setSeat(showtime.id(), seat, status, order, status.equals("HELD") ? expires : null);
    }

    private void assertSeatTaken(UUID seat) {
        var before = seatState(showtime.id(), seat);
        String json = problem(hold(token, showtime.id(), seats(seat)), 409, "SEAT_TAKEN");
        assertThat(strings(json, "$.taken_seat_ids")).containsExactly(seat.toString());
        assertThat(seatState(showtime.id(), seat)).isEqualTo(before);
        assertThat(orderCount(buyer)).isZero();
    }

    private void assertNotOnSale(Window window) {
        var closed = catalog.showtime(showtime.event(), window);
        problem(hold(token, closed.id(), seats(closed.event().seat(1))), 409, "SHOWTIME_NOT_ON_SALE");
        assertAvailable(closed, closed.event().seats().toArray(UUID[]::new));
        assertThat(orderCount(buyer)).isZero();
    }

    private Showtime limitedShowtime() {
        return catalog.showtime(catalog.event(EventSpec.defaults().withMaxTicketsPerOrder(2)
                .withStandingCapacity(100)), Window.onSale());
    }

    private void assertMaximum(Showtime limited, String request) {
        problem(hold(token, limited.id(), request), 422, "MAX_TICKETS_EXCEEDED");
        assertAvailable(limited, limited.event().seats().toArray(UUID[]::new));
        assertThat(held(limited)).isZero();
        assertThat(orderCount(buyer)).isZero();
    }

    private void assertValidation(String request) {
        problem(hold(token, showtime.id(), request), 400, "VALIDATION_ERROR");
        assertAvailable(showtime, showtime.event().seats().toArray(UUID[]::new));
        assertThat(held(showtime)).isZero();
        assertThat(orderCount(buyer)).isZero();
    }
}
