package vingo.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;

class TicketEmailOnIssueTest extends TicketingTestSupport {

    @Autowired
    TicketEmailOnIssueTest(BookingService bookingService, TicketingService ticketingService,
                           PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void payAndIssue_enqueuesOneEmailToProfileAddressWithAllTicketIds() {
        String email = createBuyerProfile();
        UUID order = hold(token, showtime, standing(showtime, 3));

        var ids = payAndIssue(order);

        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(ticketsForOrder(order)).extracting(TicketRow::status).containsOnly("ISSUED");
        assertTicketEmail(order, email, ids);
    }

    @Test
    void issueForOrder_keepsExactlyOneEmailOnIdempotentRetry() {
        String email = createBuyerProfile();
        UUID order = hold(token, showtime, seats(showtime.event().seat(1), showtime.event().seat(2)));
        var ids = payAndIssue(order);
        var originalEmail = assertTicketEmail(order, email, ids);

        var repeatedIds = transactions.execute(status -> ticketingService.issueForOrder(order));

        assertThat(repeatedIds).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(ticketEmails(order)).containsExactly(originalEmail);
    }

    @Test
    void payAndIssue_withoutProfileIssuesTicketsWithoutEmail() {
        UUID order = hold(token, showtime, standing(showtime, 3));

        var ids = payAndIssue(order);

        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(ticketsForOrder(order)).hasSize(3).extracting(TicketRow::status).containsOnly("ISSUED");
        assertThat(ticketEmails(order)).isEmpty();
        assertThat(jdbc.sql("SELECT buyer_email FROM orders WHERE id = :id")
                .param("id", order).query().singleRow()).containsEntry("buyer_email", null);
    }

    @Test
    void buyerResend_acceptsOrderWithSnapshottedProfileAndEnqueuesOnlyRequestedTicket() {
        String email = createBuyerProfile();
        UUID order = hold(token, showtime, seats(showtime.event().seat(1), showtime.event().seat(2)));
        var ids = payAndIssue(order);
        var issuedEmail = assertTicketEmail(order, email, ids);

        client.post().uri("/api/v1/tickets/{id}/resend", ids.getFirst())
                .headers(headers -> headers.setBearerAuth(token)).exchange().expectStatus().isEqualTo(202);

        var messages = ticketEmails(order);
        assertThat(messages).hasSize(2).contains(issuedEmail);
        var resend = messages.stream().filter(row -> !row.id().equals(issuedEmail.id())).findFirst().orElseThrow();
        assertThat(resend.recipient()).isEqualTo(email);
        assertThat(strings(resend.payload(), "$.ticket_ids")).containsExactly(ids.getFirst().toString());
    }

    private String createBuyerProfile() {
        String email = buyer + "@profile.vingo.test";
        jdbc.sql("""
                        INSERT INTO buyer_profile (user_id, full_name, email, phone, updated_at)
                        VALUES (:user, 'Nguyễn Văn An', :email, '0901234567', :now)
                        """)
                .param("user", buyer).param("email", email).param("now", catalog.now()).update();
        return email;
    }

    private EmailRow assertTicketEmail(UUID order, String email, List<UUID> ids) {
        var messages = ticketEmails(order);
        assertThat(messages).hasSize(1);
        var message = messages.getFirst();
        assertThat(message.recipient()).isEqualTo(email);
        assertThat(strings(message.payload(), "$.ticket_ids"))
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
        return message;
    }

    private List<EmailRow> ticketEmails(UUID order) {
        // Lọc theo vé của đơn để phát hiện cả email gửi nhầm địa chỉ, không lẫn dữ liệu test khác.
        return jdbc.sql("""
                        SELECT m.id, m.recipient, m.payload::text FROM outbox_message m
                        WHERE m.channel = 'EMAIL' AND m.template = 'TICKETS' AND EXISTS (
                            SELECT 1 FROM ticket t
                            JOIN order_item oi ON oi.id = t.order_item_id
                            WHERE oi.order_id = :order
                              AND m.payload->'ticket_ids' @> jsonb_build_array(t.id::text)
                        )
                        """)
                .param("order", order).query((rs, row) -> new EmailRow(rs.getObject("id", UUID.class),
                        rs.getString("recipient"), rs.getString("payload"))).list();
    }

    private record EmailRow(UUID id, String recipient, String payload) {}
}
