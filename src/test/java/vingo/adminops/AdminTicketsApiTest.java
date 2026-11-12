package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;
import vingo.ticketing.TicketingService;

class AdminTicketsApiTest extends AdminOpsTestSupport {

    private final AdminOpsService adminOps;
    private Showtime otherShowtime;
    private UUID firstOrder;
    private UUID secondOrder;
    private UUID otherOrder;
    private UUID issued;
    private UUID used;
    private UUID voided;
    private String code;

    @Autowired
    AdminTicketsApiTest(BookingService bookingService, TicketingService ticketingService,
                        PlatformTransactionManager transactionManager, AdminOpsService adminOps) {
        super(bookingService, ticketingService, transactionManager);
        this.adminOps = adminOps;
    }

    @BeforeEach
    void createTicketsAcrossBuyersOrdersShowtimesAndStates() {
        firstOrder = paidSeats(showtime.event().seat(1), showtime.event().seat(2), showtime.event().seat(3));
        var tickets = ticketIds(firstOrder);
        issued = tickets.get(0);
        used = tickets.get(1);
        voided = tickets.get(2);
        setTicketState(used, "USED");
        setTicketState(voided, "VOID");
        code = "TK" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.sql("UPDATE ticket SET code = :code WHERE id = :id").param("code", code).param("id", issued).update();

        UUID otherBuyer = catalog.buyer();
        String otherToken = tokenFor(otherBuyer, Role.BUYER);
        String otherEmail = otherBuyer + "@tickets.vingo.test";
        clock.advance(Duration.ofSeconds(1));
        secondOrder = holdForShowtime(otherToken, showtime, otherEmail, showtime.event().seat(4), showtime.event().seat(5));
        payAndIssue(secondOrder);
        clock.advance(Duration.ofSeconds(1));
        otherShowtime = catalog.onSaleShowtime();
        otherOrder = holdForShowtime(otherToken, otherShowtime, otherEmail, otherShowtime.event().seat(1));
        payAndIssue(otherOrder);
    }

    @Test
    void adminListTickets_returnsAllBuyersNewestFirstWithIdTieBreak() {
        var expected = jdbc.sql("SELECT id FROM ticket ORDER BY issued_at DESC, id DESC").query(String.class).list();

        var data = readPages("/api/v1/admin/tickets", 100, expected);

        assertThat(data.stream().map(row -> (String) row.get("id")).toList())
                .contains(issued.toString(), used.toString(), voided.toString(), ticketIds(otherOrder).getFirst().toString());
        assertThat(data).allSatisfy(ticket -> assertThat(ticket.get("qr_token")).isNull());
    }

    @Test
    void adminListTickets_matchesCodeIgnoringCaseExactly() {
        String json = body(get(adminToken, "/api/v1/admin/tickets?code={code}", code.toLowerCase(Locale.ROOT)), 200);

        assertThat(strings(json, "$.data[*].id")).containsExactly(issued.toString());
        assertThat(object(json)).containsEntry("next_cursor", null);
        assertThat(pageData(body(get(adminToken, "/api/v1/admin/tickets?code={code}", code.substring(0, 5)), 200))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%%%", "TK%", "TK________"})
    void adminListTickets_doesNotTreatCodeAsLikePattern(String pattern) {
        assertThat(pageData(body(get(adminToken, "/api/v1/admin/tickets?code={code}", pattern), 200))).isEmpty();
    }

    @Test
    void adminListTickets_filtersOrderId() {
        var expected = jdbc.sql("""
                        SELECT t.id FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE oi.order_id = :order ORDER BY t.issued_at DESC, t.id DESC
                        """)
                .param("order", firstOrder).query(String.class).list();

        var data = readPages("/api/v1/admin/tickets?order_id=" + firstOrder, 100, expected);

        assertThat(data).hasSize(3);
    }

    @Test
    void adminListTickets_filtersShowtimeId() {
        var expected = showtimeTickets();

        var data = readPages("/api/v1/admin/tickets?showtime_id=" + showtime.id(), 100, expected);

        assertThat(data).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISSUED", "USED", "VOID"})
    void adminListTickets_filtersStatusAcrossAllBuyers(String status) {
        var expected = jdbc.sql("SELECT id FROM ticket WHERE status = :status ORDER BY issued_at DESC, id DESC")
                .param("status", status).query(String.class).list();

        var data = readPages("/api/v1/admin/tickets?status=" + status, 100, expected);

        assertThat(data).isNotEmpty().allSatisfy(ticket -> assertThat(ticket).containsEntry("status", status));
    }

    @Test
    void adminListTickets_combinesAllFiltersWithAnd() {
        String uri = "/api/v1/admin/tickets?code={code}&order_id={order}&showtime_id={showtime}&status={status}";
        String json = body(get(adminToken, uri, code.toLowerCase(Locale.ROOT), firstOrder, showtime.id(), "ISSUED"), 200);

        assertThat(strings(json, "$.data[*].id")).containsExactly(issued.toString());
        assertThat(pageData(body(get(adminToken, uri, code, secondOrder, showtime.id(), "ISSUED"), 200))).isEmpty();
        assertThat(pageData(body(get(adminToken, uri, code, firstOrder, otherShowtime.id(), "ISSUED"), 200))).isEmpty();
        assertThat(pageData(body(get(adminToken, uri, code, firstOrder, showtime.id(), "USED"), 200))).isEmpty();
    }

    @Test
    void adminListTickets_neverExposesQrEvenForIssuedTicketsAndLeavesStoredQrUnchanged() {
        var ids = showtimeTickets().stream().map(UUID::fromString).toList();
        var tokens = ids.stream().map(this::qr).toList();
        assertThat(tokens).allSatisfy(token -> assertThat(token).isNotBlank());

        String json = body(get(adminToken, "/api/v1/admin/tickets?showtime_id={id}", showtime.id()), 200);

        assertThat(pageData(json)).hasSize(5).allSatisfy(ticket -> assertThat(ticket.get("qr_token")).isNull());
        assertThat(strings(json, "$.data[*].status")).contains("ISSUED", "USED", "VOID");
        tokens.forEach(token -> assertThat(json).doesNotContain(token));
        assertThat(ids.stream().map(this::qr).toList()).containsExactlyElementsOf(tokens);
    }

    @Test
    void adminListTickets_hidesQrEvenWhenAdminOwnsIssuedTicket() {
        UUID order = holdForShowtime(adminToken, showtime, admin + "@admin.vingo.test", showtime.event().seat(6));
        UUID ticket = payAndIssue(order).getFirst();
        String token = qr(ticket);

        String json = body(get(adminToken, "/api/v1/admin/tickets?order_id={id}", order), 200);

        assertThat(pageData(json)).hasSize(1);
        assertThat(pageData(json).getFirst()).containsEntry("id", ticket.toString()).containsEntry("status", "ISSUED");
        assertThat(pageData(json).getFirst().get("qr_token")).isNull();
        assertThat(json).doesNotContain(token);
    }

    @Test
    void adminListTickets_paginatesByTwoAcrossEqualTimestampsWithoutGapsOrDuplicates() {
        var data = readPages("/api/v1/admin/tickets?showtime_id=" + showtime.id(), 2, showtimeTickets());

        assertThat(data).hasSize(5).allSatisfy(ticket -> assertThat(ticket.get("qr_token")).isNull());
    }

    @Test
    void adminListTickets_returnsEmptyLastPageForUnknownOrder() {
        readPages("/api/v1/admin/tickets?order_id=" + UUID.randomUUID(), 2, List.of());
    }

    @Test
    void adminListTickets_returns400ForMalformedCursor() {
        problem(get(adminToken, "/api/v1/admin/tickets?cursor=not-a-cursor"), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void adminListTickets_returns400ForInvalidLimit(int limit) {
        problem(get(adminToken, "/api/v1/admin/tickets?limit={limit}", limit), 400, "VALIDATION_ERROR");
    }

    @Test
    void adminListTickets_returns403ForBuyer() {
        problem(get(buyerToken, "/api/v1/admin/tickets"), 403, "FORBIDDEN");
    }

    @Test
    void adminListTickets_returns401WithoutToken() {
        problem(get(null, "/api/v1/admin/tickets"), 401, "UNAUTHORIZED");
    }

    @Test
    void adminListTickets_serviceRejectsBuyerWithoutHttpSecurity() {
        assertServiceRejectsBuyer(() -> adminOps.adminListTickets(null, null, null, null, null, 20));
    }

    private List<String> showtimeTickets() {
        return jdbc.sql("SELECT id FROM ticket WHERE showtime_id = :id ORDER BY issued_at DESC, id DESC")
                .param("id", showtime.id()).query(String.class).list();
    }
}
