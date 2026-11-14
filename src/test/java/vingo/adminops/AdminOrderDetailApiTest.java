package vingo.adminops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.support.CatalogFixture;
import vingo.ticketing.TicketingService;

class AdminOrderDetailApiTest extends AdminOpsTestSupport {

    private final AdminOpsService adminOps;

    @Autowired
    AdminOrderDetailApiTest(BookingService bookingService, TicketingService ticketingService,
                            PlatformTransactionManager transactionManager, AdminOpsService adminOps) {
        super(bookingService, ticketingService, transactionManager);
        this.adminOps = adminOps;
    }

    @Test
    void adminGetOrder_returnsItemsBuyerAndChronologicalHistoryWithActorEmailsAndSystem() {
        UUID orderId = reviewSeats(showtime.event().seat(1), showtime.event().seat(2));
        jdbc.sql("UPDATE orders SET buyer_name = 'Người mua test', buyer_phone = '0901234567' WHERE id = :id")
                .param("id", orderId).update();
        clock.advance(Duration.ofSeconds(1));
        body(resolve(orderId, "ISSUE_TICKETS"), 200);
        String adminEmail = jdbc.sql("SELECT email FROM users WHERE id = :id").param("id", admin).query(String.class).single();
        String ownerEmail = jdbc.sql("SELECT email FROM users WHERE id = :id").param("id", buyer).query(String.class).single();
        var expectedHistory = jdbc.sql("""
                        SELECT h.to_status, h.at, COALESCE(u.email, 'SYSTEM') AS actor
                        FROM order_status_history h LEFT JOIN users u ON u.id = h.actor_id
                        WHERE h.order_id = :id ORDER BY h.at, h.id
                        """)
                .param("id", orderId).query((rs, row) -> new ReadHistory(rs.getString("to_status"),
                        rs.getObject("at", OffsetDateTime.class), rs.getString("actor"))).list();

        String json = detail(orderId);

        assertThat(text(json, "$.id")).isEqualTo(orderId.toString());
        assertThat(text(json, "$.status")).isEqualTo("PAID");
        assertThat(text(json, "$.buyer.email")).isEqualTo(buyerEmail);
        var expectedBuyer = jdbc.sql("SELECT buyer_name, buyer_phone FROM orders WHERE id = :id")
                .param("id", orderId).query().singleRow();
        Map<String, Object> actualBuyer = JsonPath.read(json, "$.buyer");
        assertThat(actualBuyer.get("full_name")).isEqualTo(expectedBuyer.get("buyer_name"));
        assertThat(actualBuyer.get("phone")).isEqualTo(expectedBuyer.get("buyer_phone"));
        List<Map<String, Object>> items = JsonPath.read(json, "$.items");
        assertThat(items).hasSize(2).allSatisfy(item -> {
            assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(1);
            assertThat(((Number) item.get("unit_price")).longValue()).isEqualTo(CatalogFixture.VIP_PRICE);
        });
        assertThat(strings(json, "$.items[*].seat_id")).containsExactlyInAnyOrder(
                showtime.event().seat(1).toString(), showtime.event().seat(2).toString());
        assertThat(strings(json, "$.history[*].status")).containsExactly("PENDING", "NEEDS_REVIEW", "PAID");
        assertThat(strings(json, "$.history[*].actor")).containsExactly(ownerEmail, "SYSTEM", adminEmail);
        List<Map<String, Object>> history = JsonPath.read(json, "$.history");
        assertThat(history.stream().map(row -> new ReadHistory((String) row.get("status"),
                OffsetDateTime.parse((String) row.get("at")), (String) row.get("actor"))).toList())
                .containsExactlyElementsOf(expectedHistory);
        assertThat(ticketIds(orderId)).hasSize(2);
    }

    @Test
    void adminGetOrder_ordersHistoryByAtThenIdRegardlessOfInsertionOrder() {
        UUID orderId = readOrderFixture(buyer, showtime, "PAID", buyerEmail, "0901234567", catalog.now());
        UUID first = UUID.fromString("10000000-" + UUID.randomUUID().toString().substring(9));
        UUID second = UUID.fromString("20000000-" + UUID.randomUUID().toString().substring(9));
        UUID last = UUID.fromString("00000000-" + UUID.randomUUID().toString().substring(9));
        insertHistory(orderId, last, "PAID", catalog.now());
        insertHistory(orderId, second, "NEEDS_REVIEW", catalog.now().minusSeconds(1));
        insertHistory(orderId, first, "PENDING", catalog.now().minusSeconds(1));

        String json = detail(orderId);

        assertThat(strings(json, "$.history[*].status")).containsExactly("PENDING", "NEEDS_REVIEW", "PAID");
        assertThat(strings(json, "$.history[*].actor")).containsExactly("SYSTEM", "SYSTEM", "SYSTEM");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminGetOrder_returnsPaymentsInCreatedAtIdOrderWithLatestResponseAndNoPaymentUrl(boolean sameCreatedAt) {
        UUID orderId = paidSeats(showtime.event().seat(1));
        var created = catalog.now();
        var failedExpiry = created.plusMinutes(5);
        var succeededExpiry = created.plusMinutes(10);
        UUID failed = UUID.fromString((sameCreatedAt ? "10000000-" : "f0000000-") + UUID.randomUUID().toString().substring(9));
        UUID succeeded = UUID.fromString("20000000-" + UUID.randomUUID().toString().substring(9));
        String succeededUrl = insertIntent(orderId, succeeded, "SUCCEEDED", 500_000,
                sameCreatedAt ? created : created.plusSeconds(1), succeededExpiry);
        String failedUrl = insertIntent(orderId, failed, "FAILED", 450_000, created, failedExpiry);
        insertTransaction(failed, "FAILED", "11", created.plusSeconds(2));
        insertTransaction(failed, "FAILED", "24", created.plusSeconds(1));
        insertTransaction(succeeded, "SUCCESS", "00", created.plusSeconds(3));

        String json = detail(orderId);

        List<Map<String, Object>> payments = JsonPath.read(json, "$.payments");
        assertThat(payments).hasSize(2);
        assertPayment(payments.getFirst(), orderId, failed, "FAILED", 450_000, failedExpiry, "11");
        assertPayment(payments.getLast(), orderId, succeeded, "SUCCEEDED", 500_000, succeededExpiry, "00");
        assertThat(payments).allSatisfy(payment -> assertThat(payment.get("payment_url")).isNull());
        assertThat(json).doesNotContain(failedUrl, succeededUrl);
        assertThat(jdbc.sql("SELECT payment_url FROM payment_intent WHERE order_id = :id ORDER BY created_at, id")
                .param("id", orderId).query(String.class).list()).containsExactly(failedUrl, succeededUrl);
    }

    @Test
    void adminGetOrder_returnsEmptyPaymentsForOrderWithoutIntents() {
        UUID orderId = hold(buyerToken, seats(showtime.event().seat(1)));

        String json = detail(orderId);

        List<Map<String, Object>> payments = JsonPath.read(json, "$.payments");
        assertThat(payments).isEmpty();
    }

    @Test
    void adminGetOrder_keepsIntentWithoutTransactionsAndReturnsNullLastResponseCode() {
        UUID orderId = hold(buyerToken, seats(showtime.event().seat(1)));
        UUID intent = UUID.randomUUID();
        String url = insertIntent(orderId, intent, "OPEN", 500_000, catalog.now(), catalog.now().plusMinutes(5));

        String json = detail(orderId);

        List<Map<String, Object>> payments = JsonPath.read(json, "$.payments");
        assertThat(payments).hasSize(1);
        assertThat(payments.getFirst()).containsEntry("id", intent.toString()).containsEntry("status", "OPEN");
        assertThat(payments.getFirst().get("last_response_code")).isNull();
        assertThat(payments.getFirst().get("payment_url")).isNull();
        assertThat(json).doesNotContain(url);
    }

    @Test
    void adminGetOrder_returns404ForUnknownOrder() {
        problem(get(adminToken, "/api/v1/admin/orders/{id}", UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    void adminGetOrder_returns403ForBuyerEvenForOwnOrder() {
        UUID orderId = hold(buyerToken, seats(showtime.event().seat(1)));

        problem(get(buyerToken, "/api/v1/admin/orders/{id}", orderId), 403, "FORBIDDEN");
    }

    @Test
    void adminGetOrder_returns401WithoutToken() {
        problem(get(null, "/api/v1/admin/orders/{id}", UUID.randomUUID()), 401, "UNAUTHORIZED");
    }

    @Test
    void adminGetOrder_serviceRejectsBuyerWithoutHttpSecurity() {
        assertServiceRejectsBuyer(() -> adminOps.adminGetOrder(UUID.randomUUID()));
    }

    private String detail(UUID orderId) {
        return body(get(adminToken, "/api/v1/admin/orders/{id}", orderId), 200);
    }

    private void insertHistory(UUID orderId, UUID id, String status, OffsetDateTime at) {
        jdbc.sql("INSERT INTO order_status_history (id, order_id, to_status, at) VALUES (:id, :order, :status, :at)")
                .param("id", id).param("order", orderId).param("status", status).param("at", at).update();
    }

    private String insertIntent(UUID orderId, UUID id, String status, long amount, OffsetDateTime created, OffsetDateTime expires) {
        String url = "https://pay.vingo.test/private/" + id;
        jdbc.sql("""
                        INSERT INTO payment_intent (id, order_id, txn_ref, amount, status, payment_url, created_at, expires_at)
                        VALUES (:id, :order, :ref, :amount, :status, :url, :created, :expires)
                        """)
                .param("id", id).param("order", orderId).param("ref", "read-" + id).param("amount", amount)
                .param("status", status).param("url", url).param("created", created).param("expires", expires).update();
        return url;
    }

    private void insertTransaction(UUID intent, String result, String code, OffsetDateTime receivedAt) {
        jdbc.sql("""
                        INSERT INTO payment_transaction (payment_intent_id, source, result, response_code, raw, received_at)
                        VALUES (:intent, 'IPN', :result, :code, '{}'::jsonb, :received)
                        """)
                .param("intent", intent).param("result", result).param("code", code).param("received", receivedAt).update();
    }

    private void assertPayment(Map<String, Object> payment, UUID orderId, UUID intent, String status, long amount,
                               OffsetDateTime expires, String code) {
        assertThat(payment).containsEntry("id", intent.toString()).containsEntry("order_id", orderId.toString())
                .containsEntry("txn_ref", "read-" + intent).containsEntry("status", status).containsEntry("last_response_code", code);
        assertThat(((Number) payment.get("amount")).longValue()).isEqualTo(amount);
        assertThat(OffsetDateTime.parse((String) payment.get("expires_at"))).isEqualTo(expires);
    }

    private record ReadHistory(String status, OffsetDateTime at, String actor) {}
}
