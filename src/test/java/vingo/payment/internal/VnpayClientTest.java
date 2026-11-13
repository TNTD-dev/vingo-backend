package vingo.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.payment.internal.VnpayClient.QueryResult;
import vingo.payment.internal.VnpayClient.QueryStatus;

class VnpayClientTest {

    private static final String SECRET = "test-vnpay-secret-only-for-tests";
    private static final String TMN_CODE = "TESTTMN1";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-13T03:05:00Z"), ZoneOffset.UTC);

    private final VNPaySigner signer = new VNPaySigner(SECRET);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private HttpServer server;
    private String apiUrl;
    private VnpayClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        apiUrl = "http://127.0.0.1:" + port + "/querydr";
        client = new VnpayClient(signer, jsonMapper, FIXED_CLOCK, TMN_CODE, apiUrl,
                Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void querydr_signsRequestCorrectly_andReturnsSuccess_whenResponseSignatureValid() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        Map<String, String> respFields = baseResponseFields("8F3K2Q-1", "00", "00", "40000000", "14567890");
        respFields.put("vnp_SecureHash", signResponse(respFields));

        respondJson(200, jsonMapper.writeValueAsString(respFields), capturedBody);

        OffsetDateTime txDate = OffsetDateTime.parse("2026-11-13T03:00:00Z");
        QueryResult result = client.querydr("8F3K2Q-1", txDate, "203.0.113.10");

        assertThat(result.status()).isEqualTo(QueryStatus.SUCCESS);
        assertThat(result.amountVnd()).isEqualTo(400_000L);
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.transactionStatus()).isEqualTo("00");
        assertThat(result.gatewayTxnNo()).isEqualTo("14567890");

        JsonNode req = jsonMapper.readTree(capturedBody.get());
        assertThat(req.get("vnp_Command").asText()).isEqualTo("querydr");
        assertThat(req.get("vnp_Version").asText()).isEqualTo("2.1.0");
        assertThat(req.get("vnp_TmnCode").asText()).isEqualTo(TMN_CODE);
        assertThat(req.get("vnp_TxnRef").asText()).isEqualTo("8F3K2Q-1");
        assertThat(req.get("vnp_TransactionDate").asText()).isEqualTo("20261113100000"); // GMT+7
        assertThat(req.get("vnp_CreateDate").asText()).isEqualTo("20261113100500");      // GMT+7
        assertThat(req.get("vnp_IpAddr").asText()).isEqualTo("203.0.113.10");

        String expectedReqData = String.join("|",
                req.get("vnp_RequestId").asText(),
                req.get("vnp_Version").asText(),
                req.get("vnp_Command").asText(),
                req.get("vnp_TmnCode").asText(),
                req.get("vnp_TxnRef").asText(),
                req.get("vnp_TransactionDate").asText(),
                req.get("vnp_CreateDate").asText(),
                req.get("vnp_IpAddr").asText(),
                req.get("vnp_OrderInfo").asText());
        assertThat(req.get("vnp_SecureHash").asText())
                .isEqualToIgnoringCase(signer.hmacSha512(expectedReqData));
    }

    @Test
    void querydr_returnsFailed_whenGatewayReportsFailedTransaction() {
        Map<String, String> respFields = baseResponseFields("8F3K2Q-1", "00", "02", "20000000", "14567891");
        respFields.put("vnp_SecureHash", signResponse(respFields));
        respondJson(200, jsonMapper.writeValueAsString(respFields), new AtomicReference<>());

        QueryResult result = client.querydr("8F3K2Q-1", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "127.0.0.1");

        assertThat(result.status()).isEqualTo(QueryStatus.FAILED);
        assertThat(result.amountVnd()).isEqualTo(200_000L);
        assertThat(result.transactionStatus()).isEqualTo("02");
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "04", "07"})
    void querydr_returnsUnavailable_whenTransactionStatusIsNotTerminal(String transactionStatus) {
        Map<String, String> fields = baseResponseFields("8F3K2Q-1", "00", transactionStatus, "20000000", "14567891");
        fields.put("vnp_SecureHash", signResponse(fields));
        respondJson(200, jsonMapper.writeValueAsString(fields), new AtomicReference<>());

        QueryResult result = client.querydr("8F3K2Q-1", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "127.0.0.1");

        assertThat(result.status()).isEqualTo(QueryStatus.UNAVAILABLE);
    }

    @Test
    void querydr_returnsNotFound_whenGatewayReturnsResponseCode91() {
        Map<String, String> respFields = baseResponseFields("8F3K2Q-1", "91", "", "", "");
        respFields.put("vnp_SecureHash", signResponse(respFields));
        respondJson(200, jsonMapper.writeValueAsString(respFields), new AtomicReference<>());

        QueryResult result = client.querydr("8F3K2Q-1", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "127.0.0.1");

        assertThat(result.status()).isEqualTo(QueryStatus.NOT_FOUND);
        assertThat(result.responseCode()).isEqualTo("91");
    }

    @Test
    void querydr_returnsUnavailable_whenResponseSignatureInvalid() {
        Map<String, String> respFields = baseResponseFields("8F3K2Q-1", "00", "00", "40000000", "14567890");
        respFields.put("vnp_SecureHash", "0".repeat(128));
        respondJson(200, jsonMapper.writeValueAsString(respFields), new AtomicReference<>());

        QueryResult result = client.querydr("8F3K2Q-1", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "127.0.0.1");

        assertThat(result.status()).isEqualTo(QueryStatus.UNAVAILABLE);
    }

    @Test
    void querydr_returnsUnavailable_whenHttpErrorOrTimeout() {
        respondJson(500, "{\"error\":\"Internal Server Error\"}", new AtomicReference<>());
        QueryResult httpError = client.querydr("8F3K2Q-1", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "127.0.0.1");
        assertThat(httpError.status()).isEqualTo(QueryStatus.UNAVAILABLE);

        server.removeContext("/querydr");
        server.createContext("/querydr", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        QueryResult timeoutResult = client.querydr("8F3K2Q-1", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "127.0.0.1");
        assertThat(timeoutResult.status()).isEqualTo(QueryStatus.UNAVAILABLE);
    }

    private void respondJson(int status, String responseJson, AtomicReference<String> capturedBody) {
        server.createContext("/querydr", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private Map<String, String> baseResponseFields(String txnRef, String responseCode,
                                                   String txStatus, String amount, String txNo) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("vnp_ResponseId", "resp123456");
        m.put("vnp_Command", "querydr");
        m.put("vnp_ResponseCode", responseCode);
        m.put("vnp_Message", "Confirm Success");
        m.put("vnp_TmnCode", TMN_CODE);
        m.put("vnp_TxnRef", txnRef);
        m.put("vnp_Amount", amount);
        m.put("vnp_BankCode", "NCB");
        m.put("vnp_PayDate", "20261113100100");
        m.put("vnp_TransactionNo", txNo);
        m.put("vnp_TransactionType", "01");
        m.put("vnp_TransactionStatus", txStatus);
        m.put("vnp_OrderInfo", "Thanh toan don " + txnRef);
        m.put("vnp_PromotionCode", "");
        m.put("vnp_PromotionAmount", "");
        return m;
    }

    private String signResponse(Map<String, String> m) {
        String data = String.join("|",
                m.getOrDefault("vnp_ResponseId", ""),
                m.getOrDefault("vnp_Command", ""),
                m.getOrDefault("vnp_ResponseCode", ""),
                m.getOrDefault("vnp_Message", ""),
                m.getOrDefault("vnp_TmnCode", ""),
                m.getOrDefault("vnp_TxnRef", ""),
                m.getOrDefault("vnp_Amount", ""),
                m.getOrDefault("vnp_BankCode", ""),
                m.getOrDefault("vnp_PayDate", ""),
                m.getOrDefault("vnp_TransactionNo", ""),
                m.getOrDefault("vnp_TransactionType", ""),
                m.getOrDefault("vnp_TransactionStatus", ""),
                m.getOrDefault("vnp_OrderInfo", ""),
                m.getOrDefault("vnp_PromotionCode", ""),
                m.getOrDefault("vnp_PromotionAmount", ""));
        return signer.hmacSha512(data);
    }
}
