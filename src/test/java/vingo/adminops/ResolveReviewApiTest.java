package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.security.Role;
import vingo.ticketing.TicketingService;

class ResolveReviewApiTest extends AdminOpsTestSupport {

    @Autowired
    ResolveReviewApiTest(BookingService bookingService, TicketingService ticketingService,
                         PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void issueTickets_reclaimsBothSeatsAndWritesHistoryAuditOutboxAndRealtime() {
        UUID first = showtime.event().seat(1);
        UUID second = showtime.event().seat(2);
        UUID order = reviewSeats(first, second);

        String json = body(resolve(order, "ISSUE_TICKETS"), 200);

        assertThat(text(json, "$.id")).isEqualTo(order.toString());
        assertThat(text(json, "$.status")).isEqualTo("PAID");
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(jdbc.sql("SELECT paid_at FROM orders WHERE id = :id").param("id", order)
                .query(OffsetDateTime.class).single()).isEqualTo(catalog.now());
        for (UUID seat : List.of(first, second)) {
            assertThat(seatRow(seat)).containsEntry("status", "SOLD").containsEntry("order_id", order);
        }
        var tickets = ticketIds(order);
        assertThat(tickets).hasSize(2).doesNotHaveDuplicates();
        tickets.forEach(id -> assertThat(ticketRow(id)).containsEntry("status", "ISSUED"));
        assertHistory(order, "PAID", admin);
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "PAID");
        assertOutbox(tickets);
        assertThat(eventCount(order, "order.paid")).isEqualTo(1);
        assertThat(eventCount(order, "ticket.issued")).isEqualTo(1);
        String issued = jdbc.sql("""
                        SELECT payload::text FROM realtime_event WHERE user_id = :user AND type = 'ticket.issued'
                        AND payload->>'order_id' = :order
                        """)
                .param("user", buyer).param("order", order.toString()).query(String.class).single();
        assertThat(strings(issued, "$.ticket_ids"))
                .containsExactlyInAnyOrderElementsOf(tickets.stream().map(UUID::toString).toList());
    }

    @Test
    void issueTickets_enqueuesExactlyOneEmailAndRetryingIssuanceDoesNotDuplicateIt() {
        UUID order = reviewSeats(showtime.event().seat(1), showtime.event().seat(2));
        assertThat(outbox()).isEmpty();

        body(resolve(order, "ISSUE_TICKETS"), 200);

        var tickets = ticketIds(order);
        assertThat(tickets).hasSize(2);
        UUID message = assertOutbox(tickets);
        var repeated = transactions.execute(status -> ticketingService.issueForOrder(order));
        assertThat(repeated).containsExactlyInAnyOrderElementsOf(tickets);
        assertThat(assertOutbox(tickets)).isEqualTo(message);
    }

    @Test
    void issueTickets_reclaimsThreeStandingPlaces() {
        UUID order = reviewStanding(3);
        assertThat(standingStock()).containsEntry("held", 0).containsEntry("sold", 0);
        payAndIssue(hold(tokenFor(catalog.buyer(), Role.BUYER), standing(2)));
        assertThat(standingStock()).containsEntry("held", 0).containsEntry("sold", 2);
        clearSetupTicketEmails();

        assertThat(text(body(resolve(order, "ISSUE_TICKETS"), 200), "$.status")).isEqualTo("PAID");

        assertThat(standingStock()).containsEntry("sold", 5).containsEntry("held", 0);
        var tickets = ticketIds(order);
        assertThat(tickets).hasSize(3);
        tickets.forEach(id -> assertThat(ticketRow(id)).containsEntry("status", "ISSUED").containsEntry("seat_id", null));
        assertHistory(order, "PAID", admin);
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "PAID");
        assertOutbox(tickets);
    }

    @Test
    void issueTickets_succeedsWithoutEmailAndDoesNotEnqueue() {
        UUID order = reviewSeats(showtime.event().seat(1));
        removeEmail(order);

        assertThat(text(body(resolve(order, "ISSUE_TICKETS"), 200), "$.status")).isEqualTo("PAID");

        var tickets = ticketIds(order);
        assertThat(tickets).hasSize(1);
        assertThat(outbox()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_message WHERE payload->'ticket_ids' @> CAST(:ids AS jsonb)")
                .param("ids", "[\"" + tickets.getFirst() + "\"]").query(Integer.class).single()).isZero();
        assertHistory(order, "PAID", admin);
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "PAID");
        assertThat(eventCount(order, "ticket.issued")).isEqualTo(1);
    }

    @Test
    void issueTickets_rollsBackAllSeatsWhenOneIsHeldByAnotherBuyer() {
        UUID first = showtime.event().seat(1);
        UUID second = showtime.event().seat(2);
        UUID order = reviewSeats(first, second);
        UUID competing = hold(tokenFor(catalog.buyer(), Role.BUYER), seats(second));
        var freeBefore = seatRow(first);
        var heldBefore = seatRow(second);

        String json = problem(resolve(order, "ISSUE_TICKETS"), 409, "SEAT_TAKEN");

        assertNoResolutionEffects(order);
        assertThat(seatRow(first)).isEqualTo(freeBefore).containsEntry("status", "AVAILABLE");
        assertThat(seatRow(second)).isEqualTo(heldBefore)
                .containsEntry("status", "HELD").containsEntry("order_id", competing);
        assertThat(object(json)).doesNotContainKey("suggested_seat_ids");
    }

    @Test
    void issueTickets_rollsBackWhenStandingCapacityIsInsufficient() {
        UUID order = reviewStanding(3);
        UUID competing = hold(tokenFor(catalog.buyer(), Role.BUYER), standing(3));
        var before = standingStock();

        problem(resolve(order, "ISSUE_TICKETS"), 409, "NOT_ENOUGH_STOCK");

        assertNoResolutionEffects(order);
        assertThat(standingStock()).isEqualTo(before).containsEntry("sold", 0).containsEntry("held", 3);
        assertThat(orderStatus(competing)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void issueTickets_returns409AtOrAfterShowtimeEnd(long secondsAfterEnd) {
        UUID order = reviewSeats(showtime.event().seat(1));
        OffsetDateTime end = jdbc.sql("SELECT ends_at FROM showtime WHERE id = :id")
                .param("id", showtime.id()).query(OffsetDateTime.class).single();
        clock.set(end.plusSeconds(secondsAfterEnd).toInstant());

        problem(resolve(order, "ISSUE_TICKETS"), 409, "ORDER_STATE_INVALID");

        assertNoResolutionEffects(order);
    }

    @Test
    void issueTickets_returns409WhenShowtimeIsCancelled() {
        UUID order = reviewSeats(showtime.event().seat(1));
        jdbc.sql("UPDATE showtime SET status = 'CANCELLED' WHERE id = :id").param("id", showtime.id()).update();

        problem(resolve(order, "ISSUE_TICKETS"), 409, "ORDER_STATE_INVALID");

        assertNoResolutionEffects(order);
    }

    @Test
    void issueTickets_succeedsAfterSalesCloseBeforeShowtimeEnds() {
        UUID order = reviewSeats(showtime.event().seat(1));
        OffsetDateTime close = jdbc.sql("SELECT sale_closes_at FROM showtime WHERE id = :id")
                .param("id", showtime.id()).query(OffsetDateTime.class).single();
        clock.set(close.toInstant());

        assertThat(text(body(resolve(order, "ISSUE_TICKETS"), 200), "$.status")).isEqualTo("PAID");

        assertThat(ticketIds(order)).hasSize(1);
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "PAID");
    }

    @Test
    void issueTickets_reclaimsAnExpiredHoldFromAnotherOrder() {
        UUID seat = showtime.event().seat(1);
        UUID order = reviewSeats(seat);
        hold(tokenFor(catalog.buyer(), Role.BUYER), seats(seat));
        clock.advance(Duration.ofMinutes(11));

        body(resolve(order, "ISSUE_TICKETS"), 200);

        assertThat(seatRow(seat)).containsEntry("status", "SOLD").containsEntry("order_id", order);
        assertThat(ticketIds(order)).hasSize(1);
    }

    @Test
    void issueTickets_cannotReclaimAHoldExactlyAtItsDeadline() {
        UUID seat = showtime.event().seat(1);
        UUID order = reviewSeats(seat);
        hold(tokenFor(catalog.buyer(), Role.BUYER), seats(seat));
        OffsetDateTime until = jdbc.sql("SELECT held_until FROM showtime_seat WHERE showtime_id = :showtime AND seat_id = :seat")
                .param("showtime", showtime.id()).param("seat", seat).query(OffsetDateTime.class).single();
        clock.set(until.toInstant());

        problem(resolve(order, "ISSUE_TICKETS"), 409, "SEAT_TAKEN");

        assertNoResolutionEffects(order);
    }

    @Test
    void refund_recordsHistoryAndAuditWithoutReleasingAnotherOrdersSeats() {
        UUID seat = showtime.event().seat(1);
        UUID order = reviewSeats(seat);
        hold(tokenFor(catalog.buyer(), Role.BUYER), seats(seat));
        var before = seatRow(seat);

        assertThat(text(body(resolve(order, "REFUND"), 200), "$.status")).isEqualTo("REFUNDED");

        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertHistory(order, "REFUNDED", admin);
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "REFUNDED");
        assertThat(ticketIds(order)).isEmpty();
        assertThat(seatRow(seat)).isEqualTo(before);
        assertThat(outbox()).isEmpty();
        assertThat(eventCount(order, "order.paid")).isZero();
        assertThat(eventCount(order, "ticket.issued")).isZero();
    }

    @Test
    void refund_doesNotDecrementStandingStockAgain() {
        UUID order = reviewStanding(3);
        hold(tokenFor(catalog.buyer(), Role.BUYER), standing(2));
        var before = standingStock();

        body(resolve(order, "REFUND"), 200);

        assertThat(standingStock()).isEqualTo(before);
        assertThat(ticketIds(order)).isEmpty();
        assertHistory(order, "REFUNDED", admin);
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "REFUNDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID"})
    void resolveReview_returns409ForAnOrderNotNeedingReview(String state) {
        UUID order = state.equals("PAID") ? paidSeats(showtime.event().seat(1))
                : hold(buyerToken, seats(showtime.event().seat(1)));
        var tickets = ticketIds(order);
        var seat = seatRow(showtime.event().seat(1));
        var messagesBefore = outbox();

        problem(resolve(order, "ISSUE_TICKETS"), 409, "ORDER_STATE_INVALID");

        assertThat(orderStatus(order)).isEqualTo(state);
        assertThat(ticketIds(order)).isEqualTo(tickets);
        assertThat(seatRow(showtime.event().seat(1))).isEqualTo(seat);
        assertThat(audits(order)).isEmpty();
        assertThat(outbox()).containsExactlyInAnyOrderElementsOf(messagesBefore);
    }

    @Test
    void resolveReview_returns404ForUnknownOrder() {
        problem(resolve(UUID.randomUUID(), "ISSUE_TICKETS"), 404, "NOT_FOUND");
    }

    @Test
    void resolveReview_replaysSameKeyAndBodyWithoutDuplicatingSideEffects() {
        UUID order = reviewSeats(showtime.event().seat(1), showtime.event().seat(2));
        UUID key = UUID.randomUUID();
        String request = resolution("ISSUE_TICKETS");

        String first = body(resolve(adminToken, order, key, request), 200);
        String second = body(resolve(adminToken, order, key, request), 200);

        assertThat(object(second)).isEqualTo(object(first));
        var tickets = ticketIds(order);
        assertThat(tickets).hasSize(2).doesNotHaveDuplicates();
        assertStatusAudit(order, "order", "RESOLVE_REVIEW", "NEEDS_REVIEW", "PAID");
        assertHistory(order, "PAID", admin);
        assertOutbox(tickets);
        assertThat(eventCount(order, "order.paid")).isEqualTo(1);
        assertThat(eventCount(order, "ticket.issued")).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM idempotency_key
                        WHERE user_id = :user AND endpoint = :endpoint AND key = :key AND response_status = 200
                        """)
                .param("user", admin).param("endpoint", "resolve-review:" + order).param("key", key.toString())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"action\":\"REFUND\",\"reason\":\"Đã đối chiếu thủ công\"}",
            "{\"action\":\"ISSUE_TICKETS\",\"reason\":\"Một lý do khác\"}"})
    void resolveReview_returns409WhenSameKeyHasDifferentBody(String changedBody) {
        UUID order = reviewSeats(showtime.event().seat(1));
        UUID key = UUID.randomUUID();
        body(resolve(adminToken, order, key, resolution("ISSUE_TICKETS")), 200);

        problem(resolve(adminToken, order, key, changedBody), 409, "IDEMPOTENCY_CONFLICT");

        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(ticketIds(order)).hasSize(1);
        assertThat(audits(order)).hasSize(1);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void resolveReview_returns400WithoutIdempotencyKey() {
        UUID order = reviewSeats(showtime.event().seat(1));

        problem(resolve(adminToken, order, null, resolution("ISSUE_TICKETS")), 400, "VALIDATION_ERROR");

        assertNoResolutionEffects(order);
    }

    @Test
    void resolveReview_twoAdminsIssueOnlyOneSetOfTickets() throws Exception {
        UUID order = reviewSeats(showtime.event().seat(1), showtime.event().seat(2));
        UUID secondAdmin = createAdmin();
        String secondToken = tokenFor(secondAdmin, Role.ADMIN);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var futures = List.of(adminToken, secondToken).stream().map(token -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new AssertionError("Start latch timed out");
                var response = resolve(token, order, UUID.randomUUID(), resolution("ISSUE_TICKETS"))
                        .expectBody(String.class).returnResult();
                return new ResolutionResponse(response.getStatus().value(), response.getResponseBody());
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = futures.getFirst().get(30, TimeUnit.SECONDS);
            var second = futures.getLast().get(30, TimeUnit.SECONDS);
            assertThat(List.of(first.status(), second.status())).containsExactlyInAnyOrder(200, 409);
            var rejected = first.status() == 409 ? first : second;
            assertThat(text(rejected.json(), "$.code")).isEqualTo("ORDER_STATE_INVALID");
            assertThat(text((first.status() == 200 ? first : second).json(), "$.status")).isEqualTo("PAID");
            UUID winner = first.status() == 200 ? admin : secondAdmin;
            var tickets = ticketIds(order);
            assertThat(tickets).hasSize(2).doesNotHaveDuplicates();
            assertAudit(order, "order", "RESOLVE_REVIEW", winner, REASON);
            assertHistory(order, "PAID", winner);
            assertOutbox(tickets);
            assertThat(eventCount(order, "order.paid")).isEqualTo(1);
            assertThat(eventCount(order, "ticket.issued")).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"action\":\"ISSUE_TICKETS\"}", "{\"reason\":\"Đã kiểm tra\"}",
            "{\"action\":\"UNKNOWN\",\"reason\":\"Đã kiểm tra\"}"})
    void resolveReview_returns400ForInvalidBody(String request) {
        UUID order = reviewSeats(showtime.event().seat(1));

        problem(resolve(adminToken, order, UUID.randomUUID(), request), 400, "VALIDATION_ERROR");

        assertNoResolutionEffects(order);
    }

    @Test
    void resolveReview_returns403ForBuyer() {
        UUID order = reviewSeats(showtime.event().seat(1));

        problem(resolve(buyerToken, order, UUID.randomUUID(), resolution("ISSUE_TICKETS")), 403, "FORBIDDEN");

        assertNoResolutionEffects(order);
    }

    @Test
    void resolveReview_returns401WithoutToken() {
        UUID order = reviewSeats(showtime.event().seat(1));

        problem(resolve(null, order, UUID.randomUUID(), resolution("ISSUE_TICKETS")), 401, "UNAUTHORIZED");

        assertNoResolutionEffects(order);
    }

    @Test
    void reconcileOrder_keepsDefault501() {
        // Ba API đọc admin đã làm ở PR 2; reconcile (querydr) thuộc payment nên vẫn 501.
        post(adminToken, "/api/v1/admin/orders/{id}/reconcile", null, UUID.randomUUID()).expectStatus().isEqualTo(501);
    }

    private record ResolutionResponse(int status, String json) {}
}
