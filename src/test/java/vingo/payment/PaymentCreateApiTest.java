package vingo.payment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

import vingo.support.CatalogFixture;

class PaymentCreateApiTest extends PaymentTestSupport {

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void createPayment_returns201_andSignedVnpayUrl_whenValid() {
        clock.set(Instant.parse("2026-11-13T02:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 2); // 2 * 200_000 = 400_000 VND

        String body = createPayment(buyerToken(buyer), orderId, UUID.randomUUID().toString(),
                "{\"method\":\"VNPAYQR\"}")
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).returnResult().getResponseBody();

        String txnRef = JsonPath.read(body, "$.txn_ref");
        long amount = ((Number) JsonPath.read(body, "$.amount")).longValue();
        String status = JsonPath.read(body, "$.status");
        String paymentUrl = JsonPath.read(body, "$.payment_url");

        assertThat(status).isEqualTo("OPEN");
        assertThat(amount).isEqualTo(2 * CatalogFixture.GA_PRICE);
        assertThat(txnRef).endsWith("-1");
        assertThat(intentCount(orderId)).isEqualTo(1);
        assertThat(intentStatus(txnRef)).isEqualTo("OPEN");

        Map<String, String> queryParams = parseQueryParams(URI.create(paymentUrl));
        assertThat(queryParams.get("vnp_Amount")).isEqualTo(String.valueOf(2 * CatalogFixture.GA_PRICE * 100));
        assertThat(queryParams.get("vnp_TxnRef")).isEqualTo(txnRef);
        assertThat(queryParams.get("vnp_TmnCode")).isEqualTo(TEST_TMN_CODE);
        assertThat(queryParams.get("vnp_ReturnUrl"))
                .isEqualTo("http://localhost:8080/api/v1/payments/vnpay/return");
        assertThat(queryParams.get("vnp_BankCode")).isEqualTo("VNPAYQR");
        assertThat(queryParams.get("vnp_SecureHash")).isEqualToIgnoringCase(signForTest(queryParams));
    }

    @Test
    void createPayment_returnsSameResult_whenSameIdempotencyKeyAndBody() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String key = UUID.randomUUID().toString();

        String first = createPayment(buyerToken(buyer), orderId, key, "{\"method\":\"ATM\"}")
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).returnResult().getResponseBody();
        String second = createPayment(buyerToken(buyer), orderId, key, "{\"method\":\"ATM\"}")
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat((String) JsonPath.read(second, "$.txn_ref")).isEqualTo(JsonPath.read(first, "$.txn_ref"));
        assertThat(intentCount(orderId)).isEqualTo(1);
    }

    @Test
    void createPayment_returns409IdempotencyConflict_whenSameKeyDifferentBody() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String key = UUID.randomUUID().toString();

        createPayment(buyerToken(buyer), orderId, key, "{\"method\":\"VNPAYQR\"}")
                .expectStatus().isEqualTo(201);

        var conflict = createPayment(buyerToken(buyer), orderId, key, "{\"method\":\"INTCARD\"}");
        problem(conflict, 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void createPayment_reusesOpenIntent_whenCalledWithNewIdempotencyKey() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);

        String first = createPayment(buyerToken(buyer), orderId, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).returnResult().getResponseBody();
        String second = createPayment(buyerToken(buyer), orderId, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(201)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat((String) JsonPath.read(second, "$.txn_ref")).isEqualTo(JsonPath.read(first, "$.txn_ref"));
        assertThat(intentCount(orderId)).isEqualTo(1);
    }

    @Test
    void createPayment_returns409HoldExpired_whenOrderPastExpiry() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        makeOverdue(orderId);

        var res = createPayment(buyerToken(buyer), orderId, UUID.randomUUID().toString(), "{}");
        problem(res, 409, "HOLD_EXPIRED");
        assertThat(intentCount(orderId)).isZero();
    }

    @Test
    void createPayment_returns409OrderStateInvalid_whenOrderCancelled() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        cancelOrder(buyer, orderId);

        var res = createPayment(buyerToken(buyer), orderId, UUID.randomUUID().toString(), "{}");
        problem(res, 409, "ORDER_STATE_INVALID");
    }

    @Test
    void createPayment_returns422ProfileRequired_whenBuyerHasNoProfile() {
        var showtime = catalog.onSaleShowtime();
        UUID buyerWithoutProfile = catalog.buyer();
        UUID orderId = holdStandingOrder(buyerWithoutProfile, showtime, 1);

        var res = createPayment(buyerToken(buyerWithoutProfile), orderId, UUID.randomUUID().toString(), "{}");
        problem(res, 422, "PROFILE_REQUIRED");
        assertThat(intentCount(orderId)).isZero();
    }

    @Test
    void createPayment_returns404_whenOrderBelongsToAnotherBuyer() {
        var showtime = catalog.onSaleShowtime();
        UUID owner = buyerWithProfile();
        UUID stranger = buyerWithProfile();
        UUID orderId = holdStandingOrder(owner, showtime, 1);

        var res = createPayment(buyerToken(stranger), orderId, UUID.randomUUID().toString(), "{}");
        problem(res, 404, "NOT_FOUND");
    }

    @Test
    void getPayment_returnsLatestIntentForOwner_and404ForOtherUser() {
        var showtime = catalog.onSaleShowtime();
        UUID owner = buyerWithProfile();
        UUID stranger = buyerWithProfile();
        UUID orderId = holdStandingOrder(owner, showtime, 1);

        createPayment(buyerToken(owner), orderId, UUID.randomUUID().toString(), "{}")
                .expectStatus().isEqualTo(201);

        String body = getPayment(buyerToken(owner), orderId)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat((String) JsonPath.read(body, "$.order_id")).isEqualTo(orderId.toString());
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("OPEN");

        problem(getPayment(buyerToken(stranger), orderId), 404, "NOT_FOUND");
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int idx = pair.indexOf('=');
            String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.US_ASCII);
            String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.US_ASCII);
            map.put(k, v);
        }
        return map;
    }
}
