package vingo.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

class TicketingServiceTest extends TicketingTestSupport {

    @Autowired
    TicketingServiceTest(BookingService bookingService, TicketingService ticketingService,
                         PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void issueForOrder_issuesOneTicketPerSeatWithDistinctQrTokens() {
        var first = showtime.event().seat(1);
        var second = showtime.event().seat(2);
        UUID order = hold(token, showtime, seats(first, second));

        var ids = payAndIssue(order);

        var rows = ticketsForOrder(order);
        assertThat(ids).hasSize(2).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(TicketRow::id).toList());
        assertThat(rows).extracting(TicketRow::seatId).containsExactlyInAnyOrder(first, second);
        assertThat(rows).extracting(TicketRow::orderItemId).doesNotHaveDuplicates();
        assertThat(rows).extracting(TicketRow::code).doesNotHaveDuplicates();
        assertThat(rows).extracting(TicketRow::qrToken).doesNotHaveDuplicates();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("ISSUED");
            assertThat(row.seq()).isEqualTo(1);
            assertThat(row.code()).isNotBlank();
            assertThat(row.qrToken()).hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(row.qrToken())).hasSize(32);
            assertThat(jdbc.sql("SELECT seat_id FROM order_item WHERE id = :id")
                    .param("id", row.orderItemId()).query(UUID.class).single()).isEqualTo(row.seatId());
        });
    }

    @Test
    void issueForOrder_issuesThreeStandingTicketsForSameOrderItemWithSequentialNumbers() {
        UUID order = hold(token, showtime, standing(showtime, 3));

        var ids = payAndIssue(order);

        var rows = ticketsForOrder(order);
        assertThat(ids).hasSize(3).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(TicketRow::id).toList());
        assertThat(rows).extracting(TicketRow::orderItemId).containsOnly(rows.getFirst().orderItemId());
        assertThat(rows).extracting(TicketRow::seq).containsExactly(1, 2, 3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.seatId()).isNull();
            assertThat(row.status()).isEqualTo("ISSUED");
        });
    }

    @Test
    void issueForOrder_returnsSameIdsAndDoesNotDuplicateTickets_whenCalledAgain() {
        UUID order = hold(token, showtime, standing(showtime, 3));
        var ids = payAndIssue(order);
        var before = ticketsForOrder(order);

        var repeated = transactions.execute(status -> ticketingService.issueForOrder(order));

        assertThat(repeated).containsExactlyElementsOf(ids);
        assertThat(ticketsForOrder(order)).hasSize(3).isEqualTo(before);
    }

    @Test
    void issueForOrder_rejectsPendingOrderWithoutIssuingTickets() {
        UUID order = hold(token, showtime, seats(showtime.event().seat(1)));

        assertThatThrownBy(() -> transactions.execute(status -> ticketingService.issueForOrder(order)))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_STATE_INVALID));

        assertThat(ticketsForOrder(order)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM orders WHERE id = :id")
                .param("id", order).query(String.class).single()).isEqualTo("PENDING");
        assertThat(issuedEventCount(order)).isZero();
    }

    @Test
    void issueForOrder_requiresExistingTransaction() {
        assertThatThrownBy(() -> ticketingService.issueForOrder(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void issueForOrder_publishesExactlyOneEventForWholeOrder_evenAfterRetry() {
        UUID order = hold(token, showtime, seats(showtime.event().seat(1), showtime.event().seat(2)));
        var ids = payAndIssue(order);
        transactions.execute(status -> ticketingService.issueForOrder(order));

        assertThat(issuedEventCount(order)).isEqualTo(1);
        String payload = jdbc.sql("""
                        SELECT payload::text FROM realtime_event
                        WHERE user_id = :user AND type = 'ticket.issued' AND payload->>'order_id' = :order
                        """)
                .param("user", buyer).param("order", order.toString()).query(String.class).single();
        assertThat(object(payload)).containsOnlyKeys("order_id", "ticket_ids");
        assertThat(text(payload, "$.order_id")).isEqualTo(order.toString());
        assertThat(strings(payload, "$.ticket_ids")).hasSize(2).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
        assertThat(jdbc.sql("SELECT count(*) FROM realtime_event WHERE user_id = :user AND type = 'ticket.issued'")
                .param("user", buyer).query(Integer.class).single()).isEqualTo(1);
    }

    private int issuedEventCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM realtime_event WHERE type = 'ticket.issued' AND payload->>'order_id' = :id")
                .param("id", orderId.toString()).query(Integer.class).single();
    }
}
