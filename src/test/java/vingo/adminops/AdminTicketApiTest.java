package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.security.Role;
import vingo.ticketing.TicketingService;

class AdminTicketApiTest extends AdminOpsTestSupport {

    @Autowired
    AdminTicketApiTest(BookingService bookingService, TicketingService ticketingService,
                       PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void voidTicket_invalidatesQrKeepsSeatSoldAndRecordsAudit() {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        String oldQr = qr(ticket);
        var seatBefore = seatRow(showtime.event().seat(1));

        String json = body(ticketAction(adminToken, ticket, "void", REASON_BODY), 200);

        assertThat(text(json, "$.id")).isEqualTo(ticket.toString());
        assertThat(text(json, "$.status")).isEqualTo("VOID");
        assertThat(object(json).get("qr_token")).isNull();
        assertThat(ticketRow(ticket)).containsEntry("status", "VOID");
        assertThat(jdbc.sql("SELECT voided_at FROM ticket WHERE id = :id").param("id", ticket)
                .query(OffsetDateTime.class).single()).isEqualTo(catalog.now());
        assertThat(seatRow(showtime.event().seat(1))).isEqualTo(seatBefore).containsEntry("status", "SOLD");
        assertStatusAudit(ticket, "ticket", "VOID_TICKET", "ISSUED", "VOID");
        assertThat(text(body(scan(oldQr), 409), "$.result")).isEqualTo("TICKET_VOID");
        assertThat(outbox()).isEmpty();
    }

    @Test
    void reissueQr_changesOnlyTokenAndRecordsRedactedAudit() {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);
        String oldQr = (String) before.get("qr_token");
        var seatBefore = seatRow(showtime.event().seat(1));

        String json = body(ticketAction(adminToken, ticket, "reissue-qr", REASON_BODY), 200);

        assertThat(text(json, "$.id")).isEqualTo(ticket.toString());
        assertThat(text(json, "$.status")).isEqualTo("ISSUED");
        assertThat(object(json).get("qr_token")).isNull();
        String newQr = qr(ticket);
        assertThat(newQr).isNotEqualTo(oldQr).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(newQr)).hasSize(32);
        var expected = new LinkedHashMap<>(before);
        expected.put("qr_token", newQr);
        assertThat(ticketRow(ticket)).isEqualTo(expected);
        assertThat(seatRow(showtime.event().seat(1))).isEqualTo(seatBefore);
        assertStatusAudit(ticket, "ticket", "REISSUE_QR", "ISSUED", "ISSUED");
        var audit = audits(ticket).getFirst();
        assertThat(audit.beforeJson()).doesNotContain("qr_token", oldQr, newQr);
        assertThat(audit.afterJson()).doesNotContain("qr_token", oldQr, newQr);
        problem(scan(oldQr), 404, "NOT_FOUND");
        assertThat(text(body(scan(newQr), 200), "$.result")).isEqualTo("OK");
        assertThat(outbox()).isEmpty();
    }

    @Test
    void adminResend_enqueuesOnlyRequestedTicketAndAuditsOutboxId() {
        UUID order = paidSeats(showtime.event().seat(1), showtime.event().seat(2));
        clearSetupTicketEmails();
        UUID ticket = ticketIds(order).getFirst();
        var before = ticketRow(ticket);

        ticketAction(adminToken, ticket, "resend", null).expectStatus().isEqualTo(202);

        UUID outboxId = assertOutbox(List.of(ticket));
        var audit = assertAudit(ticket, "ticket", "RESEND_TICKET", admin, null);
        assertThat(audit.beforeJson()).isNull();
        assertThat(object(audit.afterJson())).containsExactlyEntriesOf(Map.of("outbox_id", outboxId.toString()));
        assertThat(audit.afterJson()).doesNotContain(buyerEmail, "email", "qr_token", qr(ticket));
        assertThat(ticketRow(ticket)).isEqualTo(before);
    }

    @Test
    void adminResend_hasNoBuyerRateLimitAndAuditsEveryRequest() {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();

        for (int attempt = 0; attempt < 4; attempt++) {
            ticketAction(adminToken, ticket, "resend", null).expectStatus().isEqualTo(202);
        }

        var messages = outbox();
        assertThat(messages).hasSize(4);
        for (var message : messages) {
            assertThat(message.template()).isEqualTo("TICKETS");
            assertThat(strings(message.payload(), "$.ticket_ids")).containsExactly(ticket.toString());
        }
        var logs = audits(ticket);
        assertThat(logs).hasSize(4);
        for (var audit : logs) {
            assertThat(audit.actorId()).isEqualTo(admin);
            assertThat(audit.entity()).isEqualTo("ticket");
            assertThat(audit.action()).isEqualTo("RESEND_TICKET");
            assertThat(audit.viaAgent()).isFalse();
            assertThat(audit.at()).isEqualTo(catalog.now());
            assertThat(audit.beforeJson()).isNull();
            assertThat(object(audit.afterJson())).hasSize(1).containsKey("outbox_id");
        }
        assertThat(logs.stream().map(log -> text(log.afterJson(), "$.outbox_id")).toList())
                .containsExactlyInAnyOrderElementsOf(messages.stream().map(message -> message.id().toString()).toList());
    }

    @ParameterizedTest
    @CsvSource({"void,USED,TICKET_USED", "void,VOID,TICKET_NOT_VALID",
            "reissue-qr,USED,TICKET_USED", "reissue-qr,VOID,TICKET_NOT_VALID",
            "resend,USED,TICKET_USED", "resend,VOID,TICKET_NOT_VALID"})
    void adminTicketOperations_rejectNonIssuedTicketsWithoutEffects(String action, String state, String code) {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        setTicketState(ticket, state);
        var before = ticketRow(ticket);

        problem(ticketAction(adminToken, ticket, action, action.equals("resend") ? null : REASON_BODY), 409, code);

        assertNoTicketEffects(ticket, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"void", "reissue-qr", "resend"})
    void adminTicketOperations_return404ForUnknownTicket(String action) {
        UUID id = UUID.randomUUID();

        problem(ticketAction(adminToken, id, action, action.equals("resend") ? null : REASON_BODY), 404, "NOT_FOUND");

        assertThat(audits(id)).isEmpty();
        assertThat(outbox()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"void", "reissue-qr"})
    void adminTicketOperations_return400WithoutReason(String action) {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);

        problem(ticketAction(adminToken, ticket, action, "{}"), 400, "VALIDATION_ERROR");

        assertNoTicketEffects(ticket, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"void", "reissue-qr"})
    void adminTicketOperations_return400WhenReasonHasFewerThanThreeCharacters(String action) {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);

        problem(ticketAction(adminToken, ticket, action, "{\"reason\":\"ab\"}"), 400, "VALIDATION_ERROR");

        assertNoTicketEffects(ticket, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"void", "reissue-qr", "resend"})
    void adminTicketOperations_return403ForBuyer(String action) {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);

        problem(ticketAction(buyerToken, ticket, action, action.equals("resend") ? null : REASON_BODY), 403, "FORBIDDEN");

        assertNoTicketEffects(ticket, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"void", "reissue-qr", "resend"})
    void adminTicketOperations_return401WithoutToken(String action) {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);

        problem(ticketAction(null, ticket, action, action.equals("resend") ? null : REASON_BODY), 401, "UNAUTHORIZED");

        assertNoTicketEffects(ticket, before);
    }

    @Test
    void adminResend_returns409WithoutOrderEmail() {
        UUID order = paidSeats(showtime.event().seat(1));
        clearSetupTicketEmails();
        UUID ticket = ticketIds(order).getFirst();
        removeEmail(order);
        var before = ticketRow(ticket);

        problem(ticketAction(adminToken, ticket, "resend", null), 409, "ORDER_STATE_INVALID");

        assertNoTicketEffects(ticket, before);
    }

    @Test
    void buyerResend_enqueuesOwnedTicketWithoutAdminAudit() {
        UUID order = paidSeats(showtime.event().seat(1), showtime.event().seat(2));
        clearSetupTicketEmails();
        UUID ticket = ticketIds(order).getFirst();
        var before = ticketRow(ticket);

        buyerResend(buyerToken, ticket).expectStatus().isEqualTo(202);

        assertOutbox(List.of(ticket));
        assertThat(audits(ticket)).isEmpty();
        assertThat(audits(order)).isEmpty();
        assertThat(ticketRow(ticket)).isEqualTo(before);
    }

    @Test
    void buyerResend_returns404ForAnotherOwnersTicket() {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);

        problem(buyerResend(tokenFor(catalog.buyer(), Role.BUYER), ticket), 404, "NOT_FOUND");

        assertNoTicketEffects(ticket, before);
    }

    @Test
    void buyerResend_returns404ForUnknownTicket() {
        UUID id = UUID.randomUUID();

        problem(buyerResend(buyerToken, id), 404, "NOT_FOUND");

        assertThat(audits(id)).isEmpty();
        assertThat(outbox()).isEmpty();
    }

    @Test
    void buyerResend_countsFailedRequestsTowardHourlyLimit() {
        UUID order = paidSeats(showtime.event().seat(1), showtime.event().seat(2));
        clearSetupTicketEmails();
        var tickets = ticketIds(order);
        jdbc.sql("UPDATE ticket SET status = 'USED', used_at = :now WHERE id = :id")
                .param("now", catalog.now()).param("id", tickets.getLast()).update();

        // Lần gọi lỗi (404, 409) vẫn bị tính để không ai dò id vé hay spam bằng request sai được vô hạn.
        problem(buyerResend(buyerToken, UUID.randomUUID()), 404, "NOT_FOUND");
        problem(buyerResend(buyerToken, UUID.randomUUID()), 404, "NOT_FOUND");
        problem(buyerResend(buyerToken, tickets.getLast()), 409, "TICKET_USED");

        problem(buyerResend(buyerToken, tickets.getFirst()).expectHeader().exists("Retry-After"), 429, "RATE_LIMITED");
        assertThat(outbox()).isEmpty();
    }

    @Test
    void buyerResend_fourthRequestPerUserWithinHourReturns429EvenAcrossTickets() {
        UUID order = paidSeats(showtime.event().seat(1), showtime.event().seat(2));
        clearSetupTicketEmails();
        var tickets = ticketIds(order);
        buyerResend(buyerToken, tickets.getFirst()).expectStatus().isEqualTo(202);
        buyerResend(buyerToken, tickets.getFirst()).expectStatus().isEqualTo(202);
        buyerResend(buyerToken, tickets.getLast()).expectStatus().isEqualTo(202);

        problem(buyerResend(buyerToken, tickets.getLast()).expectHeader().exists("Retry-After"), 429, "RATE_LIMITED");

        var messages = outbox();
        assertThat(messages).hasSize(3);
        assertThat(messages.stream().flatMap(message -> strings(message.payload(), "$.ticket_ids").stream()).toList())
                .containsExactlyInAnyOrder(tickets.getFirst().toString(), tickets.getFirst().toString(), tickets.getLast().toString());
        tickets.forEach(id -> {
            assertThat(audits(id)).isEmpty();
            assertThat(ticketRow(id)).containsEntry("status", "ISSUED");
        });
    }

    @ParameterizedTest
    @CsvSource({"USED,TICKET_USED", "VOID,TICKET_NOT_VALID"})
    void buyerResend_returns409ForNonIssuedTicket(String state, String code) {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        setTicketState(ticket, state);
        var before = ticketRow(ticket);

        problem(buyerResend(buyerToken, ticket), 409, code);

        assertNoTicketEffects(ticket, before);
    }

    @Test
    void buyerResend_returns409WithoutOrderEmail() {
        UUID order = paidSeats(showtime.event().seat(1));
        clearSetupTicketEmails();
        UUID ticket = ticketIds(order).getFirst();
        removeEmail(order);
        var before = ticketRow(ticket);

        problem(buyerResend(buyerToken, ticket), 409, "ORDER_STATE_INVALID");

        assertNoTicketEffects(ticket, before);
    }

    @Test
    void buyerResend_returns401WithoutToken() {
        UUID ticket = issuedTicket();
        clearSetupTicketEmails();
        var before = ticketRow(ticket);

        problem(buyerResend(null, ticket), 401, "UNAUTHORIZED");

        assertNoTicketEffects(ticket, before);
    }

    private void assertNoTicketEffects(UUID ticket, Map<String, Object> before) {
        assertThat(ticketRow(ticket)).isEqualTo(before);
        assertThat(audits(ticket)).isEmpty();
        assertThat(outbox()).isEmpty();
    }
}
