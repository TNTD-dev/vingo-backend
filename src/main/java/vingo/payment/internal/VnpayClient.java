package vingo.payment.internal;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gọi API querydr của VNPay ngoài transaction để đối soát giao dịch. */
@Component
public class VnpayClient {

    private static final Logger log = LoggerFactory.getLogger(VnpayClient.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNP_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RestClient restClient;
    private final VNPaySigner signer;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String tmnCode;
    private final String apiUrl;

    @Autowired
    public VnpayClient(VNPaySigner signer, JsonMapper jsonMapper, Clock clock,
                       @Value("${vingo.vnpay.tmn-code}") String tmnCode,
                       @Value("${vingo.vnpay.api-url}") String apiUrl) {
        this(signer, jsonMapper, clock, tmnCode, apiUrl, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    VnpayClient(VNPaySigner signer, JsonMapper jsonMapper, Clock clock,
                String tmnCode, String apiUrl, Duration connectTimeout, Duration readTimeout) {
        this.signer = signer;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.tmnCode = tmnCode;
        this.apiUrl = apiUrl;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public enum QueryStatus {
        SUCCESS,
        FAILED,
        NOT_FOUND,
        UNAVAILABLE
    }

    public record QueryResult(
            QueryStatus status,
            long amountVnd,
            String responseCode,
            String transactionStatus,
            String gatewayTxnNo,
            String rawJson
    ) {
        public static QueryResult success(long amountVnd, String gatewayTxnNo, String rawJson) {
            return new QueryResult(QueryStatus.SUCCESS, amountVnd, "00", "00", gatewayTxnNo, rawJson);
        }

        public static QueryResult failed(long amountVnd, String responseCode, String transactionStatus,
                                         String gatewayTxnNo, String rawJson) {
            return new QueryResult(QueryStatus.FAILED, amountVnd, responseCode, transactionStatus,
                    gatewayTxnNo, rawJson);
        }

        public static QueryResult notFound(String responseCode, String rawJson) {
            return new QueryResult(QueryStatus.NOT_FOUND, 0L, responseCode, null, null, rawJson);
        }

        public static QueryResult unavailable() {
            return new QueryResult(QueryStatus.UNAVAILABLE, 0L, null, null, null, "{}");
        }
    }

    public QueryResult querydr(String txnRef, OffsetDateTime transactionDate, String clientIp) {
        try {
            String requestId = UUID.randomUUID().toString().replace("-", "");
            String version = "2.1.0";
            String command = "querydr";
            String orderInfo = "Truy van giao dich " + txnRef;
            String transDate = VNP_TIME.format(transactionDate.atZoneSameInstant(VN_ZONE));
            String createDate = VNP_TIME.format(OffsetDateTime.now(clock).atZoneSameInstant(VN_ZONE));
            String ipAddr = (clientIp == null || clientIp.isBlank()) ? "127.0.0.1" : clientIp;

            String requestSignData = String.join("|",
                    requestId, version, command, tmnCode, txnRef, transDate, createDate, ipAddr, orderInfo);
            String secureHash = signer.hmacSha512(requestSignData);

            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("vnp_RequestId", requestId);
            payload.put("vnp_Version", version);
            payload.put("vnp_Command", command);
            payload.put("vnp_TmnCode", tmnCode);
            payload.put("vnp_TxnRef", txnRef);
            payload.put("vnp_OrderInfo", orderInfo);
            payload.put("vnp_TransactionDate", transDate);
            payload.put("vnp_CreateDate", createDate);
            payload.put("vnp_IpAddr", ipAddr);
            payload.put("vnp_SecureHash", secureHash);

            String rawBody = restClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);

            if (rawBody == null || rawBody.isBlank()) {
                return QueryResult.unavailable();
            }

            JsonNode root = jsonMapper.readTree(rawBody);
            if (!verifyResponseSignature(root)) {
                log.atWarn().addKeyValue("txn_ref", txnRef)
                        .log("Rejected VNPay querydr response due to invalid signature");
                return QueryResult.unavailable();
            }

            String returnedTxnRef = textOrEmpty(root, "vnp_TxnRef");
            if (!returnedTxnRef.isEmpty() && !txnRef.equals(returnedTxnRef)) {
                log.atWarn().addKeyValue("txn_ref", txnRef).addKeyValue("returned_txn_ref", returnedTxnRef)
                        .log("Rejected VNPay querydr response due to mismatched vnp_TxnRef");
                return QueryResult.unavailable();
            }

            String responseCode = textOrNull(root, "vnp_ResponseCode");
            if ("91".equals(responseCode)) {
                return QueryResult.notFound(responseCode, rawBody);
            }
            if (!"00".equals(responseCode)) {
                log.atWarn().addKeyValue("txn_ref", txnRef).addKeyValue("response_code", responseCode).log("VNPay rejected querydr request");
                throw new IllegalStateException("VNPay querydr rejected: " + responseCode);
            }

            String transactionStatus = textOrNull(root, "vnp_TransactionStatus");
            if (transactionStatus == null || transactionStatus.isBlank() || "01".equals(transactionStatus)) {
                // Giao dịch chưa hoàn tất trên cổng VNPay -> giữ nguyên OPEN để đối soát lại sau
                return QueryResult.unavailable();
            }

            String transactionNo = textOrNull(root, "vnp_TransactionNo");
            String amountStr = textOrNull(root, "vnp_Amount");

            if ("00".equals(transactionStatus)) {
                if (amountStr == null || amountStr.isBlank()) {
                    return QueryResult.unavailable();
                }
                long amountVnd = Long.parseLong(amountStr) / 100L;
                return QueryResult.success(amountVnd, transactionNo, rawBody);
            }

            if (!"02".equals(transactionStatus)) {
                return QueryResult.unavailable();
            }
            long amountVnd = (amountStr == null || amountStr.isBlank()) ? 0L : Long.parseLong(amountStr) / 100L;
            return QueryResult.failed(amountVnd, responseCode, transactionStatus, transactionNo, rawBody);
        } catch (org.springframework.web.client.ResourceAccessException | org.springframework.web.client.HttpServerErrorException ex) {
            log.atWarn().addKeyValue("txn_ref", txnRef).setCause(ex).log("VNPay querydr call failed");
            return QueryResult.unavailable();
        }
    }

    private boolean verifyResponseSignature(JsonNode root) {
        String receivedHash = textOrNull(root, "vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            return false;
        }
        String responseSignData = String.join("|",
                textOrEmpty(root, "vnp_ResponseId"),
                textOrEmpty(root, "vnp_Command"),
                textOrEmpty(root, "vnp_ResponseCode"),
                textOrEmpty(root, "vnp_Message"),
                textOrEmpty(root, "vnp_TmnCode"),
                textOrEmpty(root, "vnp_TxnRef"),
                textOrEmpty(root, "vnp_Amount"),
                textOrEmpty(root, "vnp_BankCode"),
                textOrEmpty(root, "vnp_PayDate"),
                textOrEmpty(root, "vnp_TransactionNo"),
                textOrEmpty(root, "vnp_TransactionType"),
                textOrEmpty(root, "vnp_TransactionStatus"),
                textOrEmpty(root, "vnp_OrderInfo"),
                textOrEmpty(root, "vnp_PromotionCode"),
                textOrEmpty(root, "vnp_PromotionAmount")
        );
        String expectedHash = signer.hmacSha512(responseSignData);
        byte[] expectedBytes = expectedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] receivedBytes = receivedHash.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedBytes, receivedBytes);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? null : child.asText();
    }

    private static String textOrEmpty(JsonNode node, String field) {
        String val = textOrNull(node, field);
        return val == null ? "" : val;
    }
}
