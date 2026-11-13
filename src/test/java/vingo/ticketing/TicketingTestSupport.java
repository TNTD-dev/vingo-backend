package vingo.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.booking.BookingService;
import vingo.booking.SaleOutcome;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;
import vingo.support.IntegrationTest;

abstract class TicketingTestSupport extends IntegrationTest {

    protected final BookingService bookingService;
    protected final TicketingService ticketingService;
    protected final TransactionTemplate transactions;
    protected Showtime showtime;
    protected UUID buyer;
    protected String token;

    TicketingTestSupport(BookingService bookingService, TicketingService ticketingService,
                         PlatformTransactionManager transactionManager) {
        this.bookingService = bookingService;
        this.ticketingService = ticketingService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void createBuyerAndShowtime() {
        // PostgreSQL lưu microsecond nên cố định cùng độ chính xác để so sánh thời điểm quét.
        clock.set(clock.instant().truncatedTo(ChronoUnit.MICROS));
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        token = tokenFor(buyer, Role.BUYER);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected UUID hold(String ownerToken, Showtime target, String request) {
        String json = body(client.post().uri("/api/v1/showtimes/{id}/holds", target.id())
                .headers(headers -> {
                    headers.setBearerAuth(ownerToken);
                    headers.set("Idempotency-Key", UUID.randomUUID().toString());
                })
                .contentType(MediaType.APPLICATION_JSON).body(request).exchange(), 201);
        return UUID.fromString(text(json, "$.id"));
    }

    protected String seats(UUID... ids) {
        return "{\"seat_ids\":[" + Arrays.stream(ids).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
    }

    protected String standing(Showtime target, int quantity) {
        return "{\"zone_id\":\"%s\",\"quantity\":%d}"
                .formatted(target.event().standingZoneId(), quantity);
    }

    protected List<UUID> payAndIssue(UUID orderId) {
        return transactions.execute(status -> {
            assertThat(bookingService.lockOrderForPayment(orderId)).isPresent();
            assertThat(bookingService.markSold(orderId)).isEqualTo(SaleOutcome.SOLD);
            assertThat(bookingService.lockOrderForPayment(orderId).orElseThrow().status()).isEqualTo("PAID");
            return ticketingService.issueForOrder(orderId);
        });
    }

    protected List<UUID> issueSeats(UUID... ids) {
        return payAndIssue(hold(token, showtime, seats(ids)));
    }

    protected ResponseSpec get(String ownerToken, String uri, Object... vars) {
        return client.get().uri(uri, vars)
                .headers(headers -> { if (ownerToken != null) headers.setBearerAuth(ownerToken); }).exchange();
    }

    protected ResponseSpec checkIn(String scannerToken, String request) {
        return client.post().uri("/api/v1/checkins")
                .headers(headers -> { if (scannerToken != null) headers.setBearerAuth(scannerToken); })
                .contentType(MediaType.APPLICATION_JSON).body(request).exchange();
    }

    protected ResponseSpec scan(String scannerToken, TicketRow ticket, UUID showtimeId, String gate) {
        return checkIn(scannerToken, "{\"qr_token\":\"%s\",\"showtime_id\":\"%s\",\"gate\":\"%s\"}"
                .formatted(ticket.qrToken(), showtimeId, gate));
    }

    protected String body(ResponseSpec response, int status) {
        return response.expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
    }

    protected String problem(ResponseSpec response, int status, String code) {
        String json = body(response.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON), status);
        assertThat(text(json, "$.code")).isEqualTo(code);
        assertThat(((Number) JsonPath.read(json, "$.status")).intValue()).isEqualTo(status);
        return json;
    }

    protected String text(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected List<String> strings(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected Map<String, Object> object(String json) {
        return JsonPath.read(json, "$");
    }

    protected List<TicketRow> ticketsForOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT t.* FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE oi.order_id = :id ORDER BY oi.id, t.seq
                        """)
                .param("id", orderId).query((rs, row) -> new TicketRow(
                        rs.getObject("id", UUID.class), rs.getObject("order_item_id", UUID.class),
                        rs.getObject("seat_id", UUID.class), rs.getInt("seq"), rs.getString("code"),
                        rs.getString("qr_token"), rs.getString("status"),
                        rs.getObject("used_at", OffsetDateTime.class))).list();
    }

    protected TicketRow ticket(UUID id) {
        UUID orderId = jdbc.sql("""
                        SELECT oi.order_id FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE t.id = :id
                        """)
                .param("id", id).query(UUID.class).single();
        return ticketsForOrder(orderId).stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    protected void voidTicket(UUID id) {
        jdbc.sql("UPDATE ticket SET status = 'VOID', voided_at = :now WHERE id = :id")
                .param("now", catalog.now()).param("id", id).update();
    }

    protected List<CheckinRow> checkins(UUID ticketId) {
        return jdbc.sql("SELECT * FROM checkin WHERE ticket_id = :id ORDER BY scanned_at, id")
                .param("id", ticketId).query((rs, row) -> new CheckinRow(
                        rs.getObject("showtime_id", UUID.class), rs.getObject("scanned_by", UUID.class),
                        rs.getString("gate"), rs.getString("result"),
                        rs.getObject("scanned_at", OffsetDateTime.class))).list();
    }

    protected record TicketRow(UUID id, UUID orderItemId, UUID seatId, int seq, String code,
                               String qrToken, String status, OffsetDateTime usedAt) {}

    protected record CheckinRow(UUID showtimeId, UUID scannedBy, String gate, String result,
                                OffsetDateTime scannedAt) {}
}
