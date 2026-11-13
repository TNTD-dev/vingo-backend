package vingo.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.jayway.jsonpath.JsonPath;

import vingo.booking.BookingService;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;
import vingo.support.IntegrationTest;

abstract class PaymentTestSupport extends IntegrationTest {

    protected static final String TEST_SECRET = "test-vnpay-secret-only-for-tests";
    protected static final String TEST_TMN_CODE = "TESTTMN1";

    @Autowired
    protected BookingService booking;

    @Autowired
    protected PaymentService paymentService;

    protected String buyerToken(UUID userId) {
        return tokenFor(userId, Role.BUYER);
    }

    /** Tạo buyer kèm bản ghi hợp lệ trong bảng buyer_profile. */
    protected UUID buyerWithProfile() {
        UUID userId = catalog.buyer();
        jdbc.sql("""
                        INSERT INTO buyer_profile (user_id, full_name, phone, email, updated_at)
                        VALUES (:user, 'Nguyen Van A', '0901234567', :email, :now)
                        """)
                .param("user", userId)
                .param("email", userId + "@test.vingo")
                .param("now", catalog.now())
                .update();
        return userId;
    }

    /** Tạo đơn PENDING khu ngồi qua API giữ chỗ thật. */
    protected UUID holdSeatedOrder(UUID buyerId, Showtime showtime, UUID... seatIds) {
        String json = "{\"seat_ids\":[" + Arrays.stream(seatIds)
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
        return holdOrder(buyerId, showtime.id(), json);
    }

    /** Tạo đơn PENDING khu đứng qua API giữ chỗ thật. */
    protected UUID holdStandingOrder(UUID buyerId, Showtime showtime, int quantity) {
        String json = "{\"zone_id\":\"%s\",\"quantity\":%d}"
                .formatted(showtime.event().standingZoneId(), quantity);
        return holdOrder(buyerId, showtime.id(), json);
    }

    private UUID holdOrder(UUID buyerId, UUID showtimeId, String bodyJson) {
        String response = client.post().uri("/api/v1/showtimes/{id}/holds", showtimeId)
                .headers(h -> {
                    h.setBearerAuth(buyerToken(buyerId));
                    h.set("Idempotency-Key", UUID.randomUUID().toString());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(bodyJson)
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    protected ResponseSpec createPayment(String token, UUID orderId, String idempotencyKey, String bodyJson) {
        var req = client.post().uri("/api/v1/orders/{id}/payments", orderId)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                    if (idempotencyKey != null) h.set("Idempotency-Key", idempotencyKey);
                })
                .contentType(MediaType.APPLICATION_JSON);
        if (bodyJson != null) {
            req.body(bodyJson);
        }
        return req.exchange();
    }

    protected ResponseSpec getPayment(String token, UUID orderId) {
        return client.get().uri("/api/v1/orders/{id}/payment", orderId)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .exchange();
    }

    protected void cancelOrder(UUID buyerId, UUID orderId) {
        client.post().uri("/api/v1/orders/{id}/cancel", orderId)
                .headers(h -> h.setBearerAuth(buyerToken(buyerId)))
                .exchange()
                .expectStatus().isOk();
    }

    /** Chèn trực tiếp một payment_intent OPEN để test IPN/querydr độc lập với createPayment. */
    protected String openIntent(UUID orderId) {
        return openIntent(orderId, 1);
    }

    protected String openIntent(UUID orderId, int attempt) {
        var row = jdbc.sql("SELECT code, total_amount, expires_at FROM orders WHERE id = :id")
                .param("id", orderId)
                .query((rs, i) -> new OrderMeta(
                        rs.getString("code").trim(),
                        rs.getLong("total_amount"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .single();
        String txnRef = row.code() + "-" + attempt;
        OffsetDateTime now = catalog.now();
        jdbc.sql("""
                        INSERT INTO payment_intent (order_id, txn_ref, amount, status, created_at, updated_at, expires_at)
                        VALUES (:order, :txnRef, :amount, 'OPEN', :now, :now, :expires)
                        """)
                .param("order", orderId)
                .param("txnRef", txnRef)
                .param("amount", row.totalAmount())
                .param("now", now)
                .param("expires", row.expiresAt())
                .update();
        return txnRef;
    }

    private record OrderMeta(String code, long totalAmount, OffsetDateTime expiresAt) {}

    /** Bộ tham số IPN chuẩn của VNPay đã ký HMAC-SHA512 độc lập trong test. */
    protected Map<String, String> signedIpnParams(String txnRef, long amountVnd,
                                                  String responseCode, String transactionStatus,
                                                  String transactionNo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", TEST_TMN_CODE);
        params.put("vnp_Amount", String.valueOf(Math.multiplyExact(amountVnd, 100L)));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", "Thanh toan don " + txnRef);
        params.put("vnp_OrderType", "other");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_TransactionNo", transactionNo);
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_PayDate", "20261113093000");
        params.put("vnp_SecureHash", signForTest(params));
        return params;
    }

    protected Map<String, String> signedSuccessIpn(String txnRef, long amountVnd) {
        return signedIpnParams(txnRef, amountVnd, "00", "00", "14567890");
    }

    protected Map<String, String> signedFailedIpn(String txnRef, long amountVnd, String responseCode) {
        return signedIpnParams(txnRef, amountVnd, responseCode, responseCode, "14567891");
    }

    /** Tự ký HMAC-SHA512 độc lập với VNPaySigner để kiểm chứng chéo. */
    protected static String signForTest(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("vnp_SecureHash");
        sorted.remove("vnp_SecureHashType");
        String canonical = sorted.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().startsWith("vnp_")
                        && e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected String callIpn(Map<String, String> params) {
        return client.get().uri(builder -> {
                    builder.path("/api/v1/payments/vnpay/ipn");
                    params.forEach(builder::queryParam);
                    return builder.build();
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    protected ResponseSpec callReturn(Map<String, String> params) {
        return client.get().uri(builder -> {
                    builder.path("/api/v1/payments/vnpay/return");
                    params.forEach(builder::queryParam);
                    return builder.build();
                })
                .exchange();
    }

    protected String rspCode(String ipnResponseBody) {
        return JsonPath.read(ipnResponseBody, "$.RspCode");
    }

    protected String problem(ResponseSpec response, int status, String expectedCode) {
        String json = response.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo(expectedCode);
        assertThat(((Number) JsonPath.read(json, "$.status")).intValue()).isEqualTo(status);
        return json;
    }

    protected void makeOverdue(UUID orderId) {
        OffsetDateTime past = catalog.now().minusMinutes(1);
        jdbc.sql("UPDATE orders SET expires_at = :past WHERE id = :id")
                .param("past", past).param("id", orderId).update();
        jdbc.sql("UPDATE showtime_seat SET held_until = :past WHERE order_id = :id AND status = 'HELD'")
                .param("past", past).param("id", orderId).update();
        jdbc.sql("UPDATE payment_intent SET expires_at = :past WHERE order_id = :id AND status = 'OPEN'")
                .param("past", past).param("id", orderId).update();
    }

    protected String orderStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    protected String orderReviewReason(UUID orderId) {
        return jdbc.sql("SELECT review_reason FROM orders WHERE id = :id")
                .param("id", orderId).query(String.class).optional().orElse(null);
    }

    protected String intentStatus(String txnRef) {
        return jdbc.sql("SELECT status FROM payment_intent WHERE txn_ref = :ref")
                .param("ref", txnRef).query(String.class).single();
    }

    protected int intentCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM payment_intent WHERE order_id = :order")
                .param("order", orderId).query(Integer.class).single();
    }

    protected int txCount(String txnRef) {
        return jdbc.sql("""
                        SELECT count(*) FROM payment_transaction pt
                        JOIN payment_intent pi ON pi.id = pt.payment_intent_id
                        WHERE pi.txn_ref = :ref
                        """)
                .param("ref", txnRef).query(Integer.class).single();
    }

    protected int txCount(String txnRef, String source, String result) {
        return jdbc.sql("""
                        SELECT count(*) FROM payment_transaction pt
                        JOIN payment_intent pi ON pi.id = pt.payment_intent_id
                        WHERE pi.txn_ref = :ref AND pt.source = :source AND pt.result = :result
                        """)
                .param("ref", txnRef).param("source", source).param("result", result)
                .query(Integer.class).single();
    }

    protected int ticketCount(UUID orderId) {
        return jdbc.sql("""
                        SELECT count(*) FROM ticket t
                        JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE oi.order_id = :order AND t.status <> 'VOID'
                        """)
                .param("order", orderId).query(Integer.class).single();
    }

    protected int soldSeats(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM showtime_seat WHERE order_id = :order AND status = 'SOLD'")
                .param("order", orderId).query(Integer.class).single();
    }

    protected int heldSeats(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM showtime_seat WHERE order_id = :order AND status = 'HELD'")
                .param("order", orderId).query(Integer.class).single();
    }

    protected int heldStanding(Showtime showtime) {
        return jdbc.sql("""
                        SELECT held FROM showtime_zone_stock
                        WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("showtime", showtime.id())
                .param("zone", showtime.event().standingZoneId())
                .query(Integer.class).single();
    }

    protected int soldStanding(Showtime showtime) {
        return jdbc.sql("""
                        SELECT sold FROM showtime_zone_stock
                        WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("showtime", showtime.id())
                .param("zone", showtime.event().standingZoneId())
                .query(Integer.class).single();
    }

    protected int historyCount(UUID orderId, String fromStatus, String toStatus) {
        return jdbc.sql("""
                        SELECT count(*) FROM order_status_history
                        WHERE order_id = :order
                          AND from_status IS NOT DISTINCT FROM CAST(:from AS text)
                          AND to_status = :to
                        """)
                .param("order", orderId)
                .param("from", fromStatus, java.sql.Types.VARCHAR)
                .param("to", toStatus)
                .query(Integer.class).single();
    }

    protected <T> List<T> race(List<Callable<T>> actions) throws Exception {
        var ready = new CountDownLatch(actions.size());
        var start = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (var action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) {
                        throw new AssertionError("Start latch timed out");
                    }
                    return action.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).as("All callers ready").isTrue();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(f -> f.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("All callers stopped").isTrue();
        }
    }
}