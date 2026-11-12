package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.booking.BookingService;
import vingo.booking.ReviewReason;
import vingo.booking.SaleOutcome;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;
import vingo.support.IntegrationTest;
import vingo.ticketing.TicketingService;

abstract class AdminOpsTestSupport extends IntegrationTest {

    protected static final String REASON = "Đã đối chiếu thủ công";
    protected static final String REASON_BODY = "{\"reason\":\"" + REASON + "\"}";
    protected final BookingService bookingService;
    protected final TicketingService ticketingService;
    protected final TransactionTemplate transactions;
    protected Showtime showtime;
    protected UUID buyer;
    protected UUID admin;
    protected String buyerToken;
    protected String adminToken;
    protected String buyerEmail;

    AdminOpsTestSupport(BookingService bookingService, TicketingService ticketingService,
                        PlatformTransactionManager transactionManager) {
        this.bookingService = bookingService;
        this.ticketingService = ticketingService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void createAdminBuyerAndShowtime() {
        clock.set(clock.instant().truncatedTo(ChronoUnit.MICROS));
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        admin = createAdmin();
        buyerToken = tokenFor(buyer, Role.BUYER);
        adminToken = tokenFor(admin, Role.ADMIN);
        buyerEmail = buyer + "@tickets.vingo.test";
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected UUID createAdmin() {
        return jdbc.sql("""
                        INSERT INTO users (email, full_name, password_hash, role, status)
                        VALUES (:email, 'Test admin', 'x', 'ADMIN', 'ACTIVE') RETURNING id
                        """)
                .param("email", UUID.randomUUID() + "@admin.vingo.test").query(UUID.class).single();
    }

    protected String seats(UUID... ids) {
        return "{\"seat_ids\":[" + Arrays.stream(ids).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
    }

    protected String standing(int quantity) {
        return "{\"zone_id\":\"%s\",\"quantity\":%d}"
                .formatted(showtime.event().standingZoneId(), quantity);
    }

    protected UUID hold(String token, String request) {
        String json = body(client.post().uri("/api/v1/showtimes/{id}/holds", showtime.id())
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set("Idempotency-Key", UUID.randomUUID().toString());
                })
                .contentType(MediaType.APPLICATION_JSON).body(request).exchange(), 201);
        UUID orderId = UUID.fromString(text(json, "$.id"));
        jdbc.sql("UPDATE orders SET buyer_email = :email WHERE id = :id")
                .param("email", buyerEmail).param("id", orderId).update();
        return orderId;
    }

    protected UUID reviewSeats(UUID... ids) {
        return needsReview(hold(buyerToken, seats(ids)));
    }

    protected UUID reviewStanding(int quantity) {
        return needsReview(hold(buyerToken, standing(quantity)));
    }

    private UUID needsReview(UUID orderId) {
        transactions.executeWithoutResult(status -> {
            assertThat(bookingService.lockOrderForPayment(orderId)).isPresent();
            bookingService.markNeedsReview(orderId, ReviewReason.AMOUNT_MISMATCH);
        });
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM showtime_seat WHERE order_id = :id AND status = 'HELD'")
                .param("id", orderId).query(Integer.class).single()).isZero();
        return orderId;
    }

    protected UUID paidSeats(UUID... ids) {
        UUID orderId = hold(buyerToken, seats(ids));
        payAndIssue(orderId);
        return orderId;
    }

    protected List<UUID> payAndIssue(UUID orderId) {
        return transactions.execute(status -> {
            assertThat(bookingService.lockOrderForPayment(orderId)).isPresent();
            assertThat(bookingService.markSold(orderId)).isEqualTo(SaleOutcome.SOLD);
            return ticketingService.issueForOrder(orderId);
        });
    }

    protected UUID issuedTicket() {
        return ticketIds(paidSeats(showtime.event().seat(1))).getFirst();
    }

    protected void removeEmail(UUID orderId) {
        jdbc.sql("UPDATE orders SET buyer_email = NULL WHERE id = :id").param("id", orderId).update();
    }

    protected String resolution(String action) {
        return "{\"action\":\"%s\",\"reason\":\"%s\"}".formatted(action, REASON);
    }

    protected ResponseSpec resolve(UUID orderId, String action) {
        return resolve(adminToken, orderId, UUID.randomUUID(), resolution(action));
    }

    protected ResponseSpec resolve(String token, UUID orderId, UUID key, String request) {
        return client.post().uri("/api/v1/admin/orders/{id}/resolve-review", orderId)
                .headers(headers -> {
                    if (token != null) headers.setBearerAuth(token);
                    if (key != null) headers.set("Idempotency-Key", key.toString());
                })
                .contentType(MediaType.APPLICATION_JSON).body(request).exchange();
    }

    protected ResponseSpec post(String token, String uri, String json, Object... vars) {
        var request = client.post().uri(uri, vars)
                .headers(headers -> { if (token != null) headers.setBearerAuth(token); });
        return json == null ? request.exchange() : request.contentType(MediaType.APPLICATION_JSON).body(json).exchange();
    }

    protected ResponseSpec ticketAction(String token, UUID ticketId, String action, String request) {
        return post(token, "/api/v1/admin/tickets/{id}/{action}", request, ticketId, action);
    }

    protected ResponseSpec buyerResend(String token, UUID ticketId) {
        return post(token, "/api/v1/tickets/{id}/resend", null, ticketId);
    }

    protected ResponseSpec scan(String qrToken) {
        return post(adminToken, "/api/v1/checkins",
                "{\"qr_token\":\"%s\",\"showtime_id\":\"%s\"}".formatted(qrToken, showtime.id()));
    }

    protected ResponseSpec get(String token, String uri, Object... vars) {
        return client.get().uri(uri, vars)
                .headers(headers -> { if (token != null) headers.setBearerAuth(token); }).exchange();
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

    protected String orderStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
    }

    protected List<UUID> ticketIds(UUID orderId) {
        return jdbc.sql("""
                        SELECT t.id FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE oi.order_id = :id ORDER BY t.id
                        """)
                .param("id", orderId).query(UUID.class).list();
    }

    protected Map<String, Object> ticketRow(UUID ticketId) {
        return jdbc.sql("SELECT * FROM ticket WHERE id = :id").param("id", ticketId).query().singleRow();
    }

    protected String qr(UUID ticketId) {
        return (String) ticketRow(ticketId).get("qr_token");
    }

    protected void setTicketState(UUID ticketId, String state) {
        if (state.equals("USED")) {
            assertThat(text(body(scan(qr(ticketId)), 200), "$.result")).isEqualTo("OK");
        } else if (state.equals("VOID")) {
            jdbc.sql("UPDATE ticket SET status = 'VOID', voided_at = :now WHERE id = :id")
                    .param("now", catalog.now()).param("id", ticketId).update();
        } else {
            throw new IllegalArgumentException(state);
        }
    }

    protected Map<String, Object> seatRow(UUID seatId) {
        return jdbc.sql("SELECT * FROM showtime_seat WHERE showtime_id = :showtime AND seat_id = :seat")
                .param("showtime", showtime.id()).param("seat", seatId).query().singleRow();
    }

    protected Map<String, Object> standingStock() {
        return jdbc.sql("SELECT * FROM showtime_zone_stock WHERE showtime_id = :showtime AND zone_id = :zone")
                .param("showtime", showtime.id()).param("zone", showtime.event().standingZoneId()).query().singleRow();
    }

    protected List<AuditRow> audits(UUID entityId) {
        return jdbc.sql("""
                        SELECT *, before::text AS before_json, after::text AS after_json
                        FROM audit_log WHERE entity_id = :id ORDER BY at DESC, id DESC
                        """)
                .param("id", entityId).query((rs, row) -> new AuditRow(
                        rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getString("action"), rs.getString("entity"), rs.getObject("entity_id", UUID.class),
                        rs.getString("reason"), rs.getBoolean("via_agent"), rs.getObject("at", OffsetDateTime.class),
                        rs.getString("before_json"), rs.getString("after_json"))).list();
    }

    protected AuditRow assertAudit(UUID id, String entity, String action, UUID actor, String reason) {
        var rows = audits(id);
        assertThat(rows).hasSize(1);
        var audit = rows.getFirst();
        assertThat(audit.actorId()).isEqualTo(actor);
        assertThat(audit.entity()).isEqualTo(entity);
        assertThat(audit.entityId()).isEqualTo(id);
        assertThat(audit.action()).isEqualTo(action);
        assertThat(audit.reason()).isEqualTo(reason);
        assertThat(audit.viaAgent()).isFalse();
        assertThat(audit.at()).isEqualTo(catalog.now());
        return audit;
    }

    protected void assertStatusAudit(UUID id, String entity, String action, String before, String after) {
        var audit = assertAudit(id, entity, action, admin, REASON);
        assertThat(object(audit.beforeJson())).containsEntry("status", before);
        assertThat(object(audit.afterJson())).containsEntry("status", after);
    }

    protected void assertHistory(UUID orderId, String to, UUID actor) {
        var rows = jdbc.sql("""
                        SELECT actor_id, reason, at FROM order_status_history
                        WHERE order_id = :id AND from_status = 'NEEDS_REVIEW' AND to_status = :to
                        """)
                .param("id", orderId).param("to", to)
                .query((rs, row) -> new HistoryRow(rs.getObject("actor_id", UUID.class),
                        rs.getString("reason"), rs.getObject("at", OffsetDateTime.class))).list();
        assertThat(rows).containsExactly(new HistoryRow(actor, REASON, catalog.now()));
    }

    protected List<OutboxRow> outbox() {
        return jdbc.sql("SELECT id, template, recipient, payload::text FROM outbox_message WHERE recipient = :email")
                .param("email", buyerEmail).query((rs, row) -> new OutboxRow(rs.getObject("id", UUID.class),
                        rs.getString("template"), rs.getString("recipient"), rs.getString("payload"))).list();
    }

    protected UUID assertOutbox(List<UUID> ids) {
        var rows = outbox();
        assertThat(rows).hasSize(1);
        var message = rows.getFirst();
        assertThat(message.template()).isEqualTo("TICKETS");
        assertThat(message.recipient()).isEqualTo(buyerEmail);
        assertThat(strings(message.payload(), "$.ticket_ids"))
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
        return message.id();
    }

    protected int eventCount(UUID orderId, String type) {
        return jdbc.sql("""
                        SELECT count(*) FROM realtime_event WHERE user_id = :user AND type = :type
                        AND payload->>'order_id' = :order
                        """)
                .param("user", buyer).param("type", type).param("order", orderId.toString())
                .query(Integer.class).single();
    }

    protected void assertNoResolutionEffects(UUID orderId) {
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(ticketIds(orderId)).isEmpty();
        assertThat(audits(orderId)).isEmpty();
        assertThat(outbox()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM order_status_history WHERE order_id = :id AND from_status = 'NEEDS_REVIEW'")
                .param("id", orderId).query(Integer.class).single()).isZero();
        assertThat(eventCount(orderId, "order.paid")).isZero();
        assertThat(eventCount(orderId, "ticket.issued")).isZero();
    }

    protected UUID readOrderFixture(UUID owner, Showtime target, String status, String email, String phone,
                                    OffsetDateTime createdAt) {
        UUID id = catalog.order(owner, target.id(), status, catalog.now().plusMinutes(10));
        jdbc.sql("""
                        UPDATE orders SET buyer_name = 'Read test buyer', buyer_email = :email,
                                          buyer_phone = :phone, created_at = :created WHERE id = :id
                        """)
                .param("email", email).param("phone", phone).param("created", createdAt).param("id", id).update();
        return id;
    }

    protected UUID holdForShowtime(String token, Showtime target, String email, UUID... seatIds) {
        String json = body(client.post().uri("/api/v1/showtimes/{id}/holds", target.id())
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set("Idempotency-Key", UUID.randomUUID().toString());
                })
                .contentType(MediaType.APPLICATION_JSON).body(seats(seatIds)).exchange(), 201);
        UUID orderId = UUID.fromString(text(json, "$.id"));
        jdbc.sql("UPDATE orders SET buyer_email = :email WHERE id = :id")
                .param("email", email).param("id", orderId).update();
        return orderId;
    }

    protected List<Map<String, Object>> pageData(String json) {
        return JsonPath.read(json, "$.data");
    }

    protected List<Map<String, Object>> readPages(String uri, int limit, List<String> expectedIds) {
        List<Map<String, Object>> data = new ArrayList<>();
        String cursor = null;
        int pages = Math.max(1, (expectedIds.size() + limit - 1) / limit);
        String query = uri + (uri.contains("?") ? "&" : "?") + "limit={limit}";
        for (int page = 0; page < pages; page++) {
            String json = cursor == null ? body(get(adminToken, query, limit), 200)
                    : body(get(adminToken, query + "&cursor={cursor}", limit, cursor), 200);
            assertThat(pageData(json)).hasSize(Math.min(limit, expectedIds.size() - data.size()));
            data.addAll(pageData(json));
            if (page == pages - 1) {
                assertThat(object(json)).containsEntry("next_cursor", null);
            } else {
                cursor = text(json, "$.next_cursor");
                assertThat(cursor).isNotBlank();
            }
        }
        assertThat(data.stream().map(row -> (String) row.get("id")).toList())
                .doesNotHaveDuplicates().containsExactlyElementsOf(expectedIds);
        return data;
    }

    protected void assertServiceRejectsBuyer(Runnable action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("test-buyer").header("alg", "HS256")
                .subject(buyer.toString()).claim(CurrentUser.ROLE_CLAIM, "BUYER").build();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            SecurityContextHolder.setContext(context);
            assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN));
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    protected record AuditRow(UUID id, UUID actorId, String action, String entity, UUID entityId,
                              String reason, boolean viaAgent, OffsetDateTime at, String beforeJson, String afterJson) {}
    protected record HistoryRow(UUID actorId, String reason, OffsetDateTime at) {}
    protected record OutboxRow(UUID id, String template, String recipient, String payload) {}
}
