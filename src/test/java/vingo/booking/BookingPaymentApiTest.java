package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vingo.support.CatalogFixture.VIP_PRICE;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.support.CatalogFixture.Showtime;

@ExtendWith(OutputCaptureExtension.class)
class BookingPaymentApiTest extends BookingTestSupport {

    private final BookingService booking;
    private final TransactionTemplate transactions;
    private Showtime showtime;
    private UUID buyer;
    private String token;

    @Autowired
    BookingPaymentApiTest(BookingService booking, PlatformTransactionManager transactionManager) {
        this.booking = booking;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void createBuyerAndShowtime() {
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        token = buyerToken(buyer);
    }

    @Test
    void lockOrderForPayment_returnsStoredIdsAmountStatusAndExpiry() {
        String json = holdOk(token, showtime.id(), seats(showtime.event().seat(1), showtime.event().seat(2)));
        UUID id = orderId(json);

        var result = transactions.execute(status -> booking.lockOrderForPayment(id));

        assertThat(result).isPresent();
        var order = result.orElseThrow();
        assertThat(order.id()).isEqualTo(id);
        assertThat(order.userId()).isEqualTo(buyer);
        assertThat(order.showtimeId()).isEqualTo(showtime.id());
        assertThat(order.status()).isEqualTo("PENDING");
        assertThat(order.totalAmount()).isEqualTo(2 * VIP_PRICE);
        assertThat(order.expiresAt().toInstant())
                .isEqualTo(OffsetDateTime.parse(text(json, "$.expires_at")).toInstant());
    }

    @Test
    void lockOrderForPayment_returnsEmpty_whenOrderDoesNotExist() {
        var result = transactions.execute(status -> booking.lockOrderForPayment(UUID.randomUUID()));
        assertThat(result).isEmpty();
    }

    @Test
    void lockOrderForPayment_keepsRowLockedUntilCallerTransactionEnds() {
        UUID id = holdOrder(true);
        var competing = new TransactionTemplate(transactions.getTransactionManager());
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactions.executeWithoutResult(status -> {
            assertThat(booking.lockOrderForPayment(id)).isPresent();
            var unlocked = competing.execute(other -> jdbc.sql("""
                            SELECT id FROM orders WHERE id = :id FOR UPDATE SKIP LOCKED
                            """)
                    .param("id", id).query(UUID.class).optional());
            assertThat(unlocked).isEmpty();
        });

        var unlocked = competing.execute(status -> jdbc.sql("""
                        SELECT id FROM orders WHERE id = :id FOR UPDATE SKIP LOCKED
                        """)
                .param("id", id).query(UUID.class).optional());
        assertThat(unlocked).contains(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lockOrderForPayment", "markSold", "markNeedsReview", "orderLines"})
    void paymentApi_rejectsCallsOutsideTransaction(String method) {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> {
            switch (method) {
                case "lockOrderForPayment" -> booking.lockOrderForPayment(id);
                case "markSold" -> booking.markSold(id);
                case "markNeedsReview" -> booking.markNeedsReview(id, ReviewReason.AMOUNT_MISMATCH);
                case "orderLines" -> booking.orderLines(id);
                default -> throw new AssertionError(method);
            }
        }).isInstanceOf(IllegalTransactionStateException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void markSold_sellsEveryHeldSeatAndRecordsSystemPayment_evenWhenPendingOrderIsOverdue(
            boolean overdue, CapturedOutput output) {
        UUID id = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1), showtime.event().seat(2))));
        if (overdue) makeOverdue(id);
        OffsetDateTime before = catalog.now().minusNanos(1_000_000);

        var outcome = transactions.execute(status -> booking.markSold(id));

        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);
        for (int number : List.of(1, 2)) {
            assertThat(seatState(showtime.id(), showtime.event().seat(number)))
                    .isEqualTo(new SeatState("SOLD", id, null));
        }
        assertThat(orderStatus(id)).isEqualTo("PAID");
        assertThat(paidAt(id)).isBetween(before, catalog.now().plusNanos(1_000_000));
        assertThat(historyCount(id, "PENDING", "PAID")).isEqualTo(1);
        assertHistoryActor(id, "PAID", null);
        assertEvent(id, "order.paid", "{\"order_id\":\"%s\"}".formatted(id));
        assertThat(eventCount(buyer, id, "order.needs_review")).isZero();
        assertTransitionLog(output, id, "PAID");
    }

    @Test
    void markSold_movesStandingQuantityFromHeldToSoldAndPreservesOtherOrders() {
        catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 1, 0);
        UUID id = holdOrder(false);
        holdOk(buyerToken(catalog.buyer()), showtime.id(), standing(showtime.event().standingZoneId(), 1));
        assertThat(held(showtime)).isEqualTo(4);

        var outcome = transactions.execute(status -> booking.markSold(id));

        assertThat(outcome).isEqualTo(SaleOutcome.SOLD);
        assertThat(held(showtime)).isEqualTo(1);
        assertThat(sold()).isEqualTo(4);
        assertThat(orderStatus(id)).isEqualTo("PAID");
        assertThat(paidAt(id)).isNotNull();
        assertThat(historyCount(id, "PENDING", "PAID")).isEqualTo(1);
        assertHistoryActor(id, "PAID", null);
        assertEvent(id, "order.paid", "{\"order_id\":\"%s\"}".formatted(id));
    }

    @Test
    void markSold_reviewsWholeOrderWithoutSellingAnySeat_whenOneSeatWasReheldByAnotherOrder() {
        UUID old = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1), showtime.event().seat(2))));
        makeOverdue(old);
        UUID fresh = orderId(holdOk(buyerToken(catalog.buyer()), showtime.id(), seats(showtime.event().seat(1))));
        var freshSeat = seatState(showtime.id(), showtime.event().seat(1));
        assertThat(freshSeat.status()).isEqualTo("HELD");
        assertThat(freshSeat.orderId()).isEqualTo(fresh);

        var outcome = transactions.execute(status -> booking.markSold(old));

        assertThat(outcome).isEqualTo(SaleOutcome.NEEDS_REVIEW);
        assertReview(old, "PENDING", ReviewReason.PAID_AFTER_EXPIRY);
        assertThat(paidAt(old)).isNull();
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(freshSeat);
        assertThat(orderStatus(fresh)).isEqualTo("PENDING");
        assertAvailable(showtime, showtime.event().seat(2));
        assertThat(soldSeats()).isZero();
    }

    @Test
    void markSold_reviewsStandingOrderWithoutSellingOrMakingHeldNegative_whenHeldQuantityIsMissing() {
        UUID id = holdOrder(false);
        // Mô phỏng tồn kho đã được nhả để kiểm nhánh UPDATE không đủ held.
        catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 0, 0);

        var outcome = transactions.execute(status -> booking.markSold(id));

        assertThat(outcome).isEqualTo(SaleOutcome.NEEDS_REVIEW);
        assertReview(id, "PENDING", ReviewReason.PAID_AFTER_EXPIRY);
        assertThat(held(showtime)).isZero();
        assertThat(sold()).isZero();
        assertThat(paidAt(id)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "EXPIRED", "PAID", "NEEDS_REVIEW", "REFUNDED"})
    void markSold_rejectsNonPendingOrderWithoutChangingData(String state) {
        UUID id = holdOrder(true);
        prepareState(id, state, true);
        var before = paymentState(id);

        assertThatThrownBy(() -> transactions.execute(status -> booking.markSold(id)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_STATE_INVALID));

        assertThat(paymentState(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void markNeedsReview_releasesPendingInventoryAndRecordsReasonWithoutTouchingOtherOrders(
            boolean seated, CapturedOutput output) {
        UUID id = holdOrder(seated);
        UUID other = orderId(holdOk(buyerToken(catalog.buyer()), showtime.id(), seated
                ? seats(showtime.event().seat(2)) : standing(showtime.event().standingZoneId(), 2)));
        var otherSeat = seatState(showtime.id(), showtime.event().seat(2));

        transactions.executeWithoutResult(status -> booking.markNeedsReview(id, ReviewReason.AMOUNT_MISMATCH));

        assertReview(id, "PENDING", ReviewReason.AMOUNT_MISMATCH);
        assertThat(paidAt(id)).isNull();
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(seatState(showtime.id(), showtime.event().seat(2))).isEqualTo(otherSeat);
        assertThat(orderStatus(other)).isEqualTo("PENDING");
        assertThat(held(showtime)).isEqualTo(seated ? 0 : 2);
        assertThat(sold()).isZero();
        assertThat(soldSeats()).isZero();
        assertTransitionLog(output, id, "NEEDS_REVIEW");
    }

    @ParameterizedTest
    @CsvSource({
            "EXPIRED, PAID_AFTER_EXPIRY, true", "EXPIRED, PAID_AFTER_EXPIRY, false",
            "CANCELLED, PAID_AFTER_CANCEL, true", "CANCELLED, PAID_AFTER_CANCEL, false"
    })
    void markNeedsReview_acceptsReleasedOrderWithoutReleasingInventoryTwice(
            String from, ReviewReason reason, boolean seated) {
        UUID id = holdOrder(seated);
        prepareState(id, from, seated);
        assertAvailable(showtime, showtime.event().seat(1));
        assertThat(held(showtime)).isZero();
        UUID other = orderId(holdOk(buyerToken(catalog.buyer()), showtime.id(), seated
                ? seats(showtime.event().seat(1)) : standing(showtime.event().standingZoneId(), 2)));
        var otherSeat = seatState(showtime.id(), showtime.event().seat(1));

        transactions.executeWithoutResult(status -> booking.markNeedsReview(id, reason));

        assertReview(id, from, reason);
        assertThat(paidAt(id)).isNull();
        assertThat(held(showtime)).isEqualTo(seated ? 0 : 2);
        assertThat(sold()).isZero();
        assertThat(seatState(showtime.id(), showtime.event().seat(1))).isEqualTo(otherSeat);
        assertThat(orderStatus(other)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @CsvSource({
            "PAID, true", "PAID, false", "NEEDS_REVIEW, true", "NEEDS_REVIEW, false",
            "REFUNDED, true", "REFUNDED, false"
    })
    void markNeedsReview_rejectsPaidReviewedOrRefundedOrderWithoutChangingData(String state, boolean seated) {
        UUID id = holdOrder(seated);
        prepareState(id, state, seated);
        var before = paymentState(id);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                booking.markNeedsReview(id, ReviewReason.AMOUNT_MISMATCH)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_STATE_INVALID));

        assertThat(paymentState(id)).isEqualTo(before);
    }

    @Test
    void orderLines_returnsOnlyRequestedOrderItemsWithSeatOrStandingQuantity() {
        UUID seated = orderId(holdOk(token, showtime.id(), seats(showtime.event().seat(1), showtime.event().seat(2))));
        UUID standing = orderId(holdOk(buyerToken(catalog.buyer()), showtime.id(),
                standing(showtime.event().standingZoneId(), 3)));

        var seatedLines = transactions.execute(status -> booking.orderLines(seated));
        var standingLines = transactions.execute(status -> booking.orderLines(standing));

        assertThat(seatedLines).containsExactlyInAnyOrder(
                new OrderLine(itemId(seated, showtime.event().seat(1)), showtime.id(),
                        showtime.event().seatedZoneId(), showtime.event().seat(1), 1),
                new OrderLine(itemId(seated, showtime.event().seat(2)), showtime.id(),
                        showtime.event().seatedZoneId(), showtime.event().seat(2), 1));
        assertThat(standingLines).containsExactly(new OrderLine(itemId(standing, null), showtime.id(),
                showtime.event().standingZoneId(), null, 3));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(180)
    void markSold_andExpiryLeaveExactlyOneConsistentTerminalState_whenRacingTenFreshOrders(boolean seated)
            throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            showtime = catalog.onSaleShowtime();
            buyer = catalog.buyer();
            token = buyerToken(buyer);
            UUID id = holdOrder(seated);
            makeOverdue(id);
            List<Callable<String>> actions = List.of(
                    () -> {
                        try {
                            return transactions.execute(status -> {
                                assertThat(booking.lockOrderForPayment(id)).isPresent();
                                return booking.markSold(id).name();
                            });
                        } catch (DomainException ex) {
                            assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_STATE_INVALID);
                            return ex.code().name();
                        }
                    },
                    () -> { booking.expireOverdueHolds(100); return "job"; });

            var results = race(actions);

            String state = orderStatus(id);
            assertThat(state).isIn("PAID", "EXPIRED");
            boolean paid = state.equals("PAID");
            assertThat(results.getFirst()).isEqualTo(paid ? "SOLD" : "ORDER_STATE_INVALID");
            assertThat(held(showtime)).isZero();
            assertThat(sold()).isEqualTo(!seated && paid ? 3 : 0);
            assertThat(soldSeats()).isEqualTo(seated && paid ? 1 : 0);
            assertThat(seatState(showtime.id(), showtime.event().seat(1)))
                    .isEqualTo(seated && paid ? new SeatState("SOLD", id, null)
                            : new SeatState("AVAILABLE", null, null));
            if (paid) assertThat(paidAt(id)).isNotNull();
            else assertThat(paidAt(id)).isNull();
            assertThat(historyCount(id, "PENDING", state)).isEqualTo(1);
            assertThat(historyCount(id, "PENDING", paid ? "EXPIRED" : "PAID")).isZero();
            assertHistoryActor(id, state, null);
            assertThat(eventCount(buyer, id, "order.paid")).isEqualTo(paid ? 1 : 0);
            assertThat(eventCount(buyer, id, "order.expired")).isEqualTo(paid ? 0 : 1);
            assertThat(eventCount(buyer, id, "order.needs_review")).isZero();
            var before = paymentState(id);
            booking.expireOverdueHolds(100);
            assertThat(paymentState(id)).isEqualTo(before);
        }
    }

    private UUID holdOrder(boolean seated) {
        return orderId(holdOk(token, showtime.id(), seated ? seats(showtime.event().seat(1))
                : standing(showtime.event().standingZoneId(), 3)));
    }

    private void prepareState(UUID id, String state, boolean seated) {
        switch (state) {
            case "CANCELLED" -> body(cancel(token, id), 200);
            case "EXPIRED" -> {
                makeOverdue(id);
                booking.expireOverdueHolds(100);
            }
            case "PAID" -> {
                jdbc.sql("UPDATE orders SET status = 'PAID', paid_at = :now WHERE id = :id")
                        .param("now", catalog.now()).param("id", id).update();
                if (seated) catalog.setSeat(showtime.id(), showtime.event().seat(1), "SOLD", id, null);
                else catalog.setZoneStock(showtime.id(), showtime.event().standingZoneId(), 3, 0);
            }
            case "NEEDS_REVIEW", "REFUNDED" -> {
                body(cancel(token, id), 200);
                jdbc.sql("UPDATE orders SET status = :status, review_reason = 'AMOUNT_MISMATCH' WHERE id = :id")
                        .param("status", state).param("id", id).update();
            }
            default -> throw new AssertionError(state);
        }
        assertThat(orderStatus(id)).isEqualTo(state);
    }

    private void assertReview(UUID id, String from, ReviewReason reason) {
        assertThat(orderStatus(id)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.sql("SELECT review_reason FROM orders WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo(reason.name());
        assertThat(historyCount(id, from, "NEEDS_REVIEW")).isEqualTo(1);
        assertHistoryActor(id, "NEEDS_REVIEW", null);
        assertThat(historyCount(id, "PENDING", "PAID")).isZero();
        assertEvent(id, "order.needs_review", "{\"order_id\":\"%s\",\"reason\":\"%s\"}".formatted(id, reason.name()));
        assertThat(eventCount(buyer, id, "order.paid")).isZero();
    }

    private void assertEvent(UUID id, String type, String payload) {
        assertThat(eventCount(buyer, id, type)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT payload = CAST(:payload AS jsonb) FROM realtime_event
                        WHERE user_id = :buyer AND type = :type AND payload->>'order_id' = :id
                        """)
                .param("payload", payload).param("buyer", buyer).param("type", type).param("id", id.toString())
                .query(Boolean.class).single()).isTrue();
    }

    private void assertTransitionLog(CapturedOutput output, UUID id, String to) {
        assertThat(output.getAll().lines()
                .filter(line -> line.startsWith("{") && line.contains(id.toString()) && line.contains("\"order_id\""))
                .filter(line -> text(line, "$.message").contains(to)).toList())
                .anySatisfy(line -> {
                    assertThat(text(line, "$.level")).isEqualTo("INFO");
                    assertThat(text(line, "$.order_id")).isEqualTo(id.toString());
                    assertThat(text(line, "$.user_id")).isEqualTo(buyer.toString());
                });
    }

    private OffsetDateTime paidAt(UUID id) {
        return jdbc.sql("SELECT paid_at FROM orders WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getObject("paid_at", OffsetDateTime.class)).list().getFirst();
    }

    private int sold() {
        return jdbc.sql("SELECT sold FROM showtime_zone_stock WHERE showtime_id = :showtime AND zone_id = :zone")
                .param("showtime", showtime.id()).param("zone", showtime.event().standingZoneId())
                .query(Integer.class).single();
    }

    private int soldSeats() {
        return jdbc.sql("SELECT count(*) FROM showtime_seat WHERE showtime_id = :showtime AND status = 'SOLD'")
                .param("showtime", showtime.id()).query(Integer.class).single();
    }

    private UUID itemId(UUID orderId, UUID seatId) {
        return jdbc.sql("""
                        SELECT id FROM order_item
                        WHERE order_id = :order AND seat_id IS NOT DISTINCT FROM CAST(:seat AS uuid)
                        """)
                .param("order", orderId).param("seat", seatId, java.sql.Types.OTHER).query(UUID.class).single();
    }

    private List<Object> paymentState(UUID id) {
        return List.of(
                jdbc.sql("SELECT * FROM orders WHERE id = :id").param("id", id).query().singleRow(),
                jdbc.sql("SELECT * FROM showtime_seat WHERE showtime_id = :id ORDER BY seat_id")
                        .param("id", showtime.id()).query().listOfRows(),
                jdbc.sql("SELECT * FROM showtime_zone_stock WHERE showtime_id = :id ORDER BY zone_id")
                        .param("id", showtime.id()).query().listOfRows(),
                jdbc.sql("SELECT * FROM order_status_history WHERE order_id = :id ORDER BY id")
                        .param("id", id).query().listOfRows(),
                jdbc.sql("SELECT * FROM realtime_event WHERE payload->>'order_id' = :id ORDER BY id")
                        .param("id", id.toString()).query().listOfRows());
    }
}
