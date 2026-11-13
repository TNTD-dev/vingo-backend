package vingo.payment;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.CreatePaymentRequest;
import vingo.api.model.PaymentIntent;
import vingo.api.model.PaymentStatus;
import vingo.auth.AuthService;
import vingo.booking.BookingService;
import vingo.booking.ReviewReason;
import vingo.booking.SaleOutcome;
import vingo.payment.internal.PaymentRepository;
import vingo.payment.internal.PaymentRepository.IntentRow;
import vingo.payment.internal.VNPaySigner;
import vingo.payment.internal.VnpayClient;
import vingo.payment.internal.VnpayClient.QueryResult;
import vingo.payment.internal.VnpayClient.QueryStatus;
import vingo.realtime.RealtimeEventType;
import vingo.realtime.RealtimeService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.idempotency.IdempotencyService;
import vingo.shared.logging.LogKeys;
import vingo.shared.security.CurrentUser;
import vingo.ticketing.TicketingService;

/**
 * Điều phối tạo thanh toán, xử lý IPN, return và đối soát querydr với VNPay.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNP_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PaymentRepository repository;
    private final VNPaySigner signer;
    private final VnpayClient vnpayClient;
    private final BookingService booking;
    private final TicketingService ticketing;
    private final AuthService auth;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;
    private final RealtimeService realtime;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final String tmnCode;
    private final String payUrl;
    private final String returnUrl;
    private final String frontendReturnUrl;

    public PaymentService(PaymentRepository repository, VNPaySigner signer, VnpayClient vnpayClient,
            BookingService booking, TicketingService ticketing, AuthService auth,
            IdempotencyService idempotency, CurrentUser currentUser,
            RealtimeService realtime, JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${vingo.vnpay.tmn-code}") String tmnCode,
            @Value("${vingo.vnpay.pay-url}") String payUrl,
            @Value("${vingo.vnpay.return-url}") String returnUrl,
            @Value("${vingo.vnpay.frontend-return-url:${vingo.vnpay.return-url}}") String frontendReturnUrl) {
        this.repository = repository;
        this.signer = signer;
        this.vnpayClient = vnpayClient;
        this.booking = booking;
        this.ticketing = ticketing;
        this.auth = auth;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.jsonMapper = jsonMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.tmnCode = tmnCode;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
        this.frontendReturnUrl = frontendReturnUrl;
    }

    /**
     * Tạo hoặc tái sử dụng payment_intent OPEN và ký link thanh toán VNPay.
     */
    @Transactional
    public PaymentIntent createPayment(UUID orderId, UUID idempotencyKey,
            CreatePaymentRequest request, String clientIp) {
        UUID userId = currentUser.require().id();
        var order = booking.lockOrderForPayment(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        currentUser.requireOwner(order.userId());

        CreatePaymentRequest normalizedBody = request == null ? new CreatePaymentRequest() : request;
        String endpoint = "POST /orders/{id}/payments:" + orderId;

        return idempotency.execute(userId, endpoint, idempotencyKey, normalizedBody, PaymentIntent.class, 201, () -> {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!"PENDING".equals(order.status())) {
                throw new DomainException(ErrorCode.ORDER_STATE_INVALID, "Đơn không ở trạng thái chờ thanh toán");
            }
            if (!order.expiresAt().isAfter(now)) {
                throw new DomainException(ErrorCode.HOLD_EXPIRED, "Đơn đã hết hạn giữ chỗ");
            }
            if (!auth.hasBuyerProfile(userId)) {
                throw new DomainException(ErrorCode.PROFILE_REQUIRED, "Chưa có hồ sơ người mua");
            }

            var existingOpen = repository.findOpenIntentByOrderId(orderId);
            if (existingOpen.isPresent()) {
                return toApiModel(existingOpen.get());
            }

            int attempt = repository.countIntentsByOrderId(orderId) + 1;
            String orderCode = repository.findOrderCode(orderId)
                    .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
            String txnRef = orderCode + "-" + attempt;
            String url = buildSignedPaymentUrl(
                    txnRef, order.totalAmount(), normalizedBody.getMethod(), clientIp, now, order.expiresAt());

            IntentRow created = repository.insertIntent(
                    orderId, txnRef, order.totalAmount(), url, now, order.expiresAt());
            log.atInfo().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue(LogKeys.USER_ID, userId)
                    .addKeyValue("txn_ref", txnRef).log("Created payment intent");
            return toApiModel(created);
        });
    }

    /**
     * Lấy trạng thái lần thanh toán gần nhất của đơn (chỉ chủ đơn).
     */
    @Transactional(readOnly = true)
    public PaymentIntent getPayment(UUID orderId) {
        booking.getOrder(orderId);
        return repository.findLatestIntentByOrderId(orderId)
                .map(this::toApiModel)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy giao dịch thanh toán"));
    }

    /**
     * Xử lý webhook IPN của VNPay; xác minh chữ ký trước rồi chạy luồng chốt
     * bán chung.
     */
    public PaymentResult handleVnpayIpn(Map<String, String> params) {
        if (!signer.verifyParams(params)) {
            log.atWarn().addKeyValue("txn_ref", params == null ? null : params.get("vnp_TxnRef"))
                    .log("Rejected VNPay IPN due to invalid signature");
            return PaymentResult.of("97", "Invalid Checksum");
        }
        try {
            String txnRef = params.get("vnp_TxnRef");
            String amountStr = params.get("vnp_Amount");
            if (txnRef == null || txnRef.isBlank() || amountStr == null || amountStr.isBlank()) {
                return PaymentResult.of("99", "Unknow error");
            }
            long amountVnd = Long.parseLong(amountStr) / 100L;
            String responseCode = params.get("vnp_ResponseCode");
            String transactionStatus = params.get("vnp_TransactionStatus");
            String transactionNo = params.get("vnp_TransactionNo");
            boolean success = "00".equals(responseCode) && "00".equals(transactionStatus);
            String rawJson = jsonMapper.writeValueAsString(params);

            return txTemplate.execute(status -> applyGatewayResult(
                    txnRef, amountVnd, success, responseCode, transactionNo, rawJson, "IPN"));
        } catch (RuntimeException ex) {
            log.atError().addKeyValue("txn_ref", params.get("vnp_TxnRef"))
                    .setCause(ex).log("Unexpected error processing VNPay IPN");
            return PaymentResult.of("99", "Unknow error");
        }
    }

    /**
     * Xử lý trang return từ VNPay: chỉ hiển thị/redirect 302. Nếu chữ ký hợp lệ
     * và intent vẫn đang OPEN thì gọi querydr ngoài transaction để đối soát.
     */
    public URI handleVnpayReturn(Map<String, String> params, String clientIp) {
        String txnRef = params == null ? null : params.get("vnp_TxnRef");
        if (txnRef != null && !txnRef.isBlank() && signer.verifyParams(params)) {
            try {
                repository.findIntentByTxnRef(txnRef).ifPresent(intent -> {
                    if ("OPEN".equals(intent.status())) {
                        QueryResult qr = vnpayClient.querydr(intent.txnRef(), intent.createdAt(), clientIp);
                        if (qr.status() == QueryStatus.SUCCESS || qr.status() == QueryStatus.FAILED) {
                            txTemplate.executeWithoutResult(status -> applyGatewayResult(
                                    intent.txnRef(), qr.amountVnd(), qr.status() == QueryStatus.SUCCESS,
                                    qr.responseCode(), qr.gatewayTxnNo(), qr.rawJson(), "QUERY"));
                        }
                    }
                });
            } catch (RuntimeException ex) {
                log.atWarn().addKeyValue("txn_ref", txnRef)
                        .setCause(ex).log("Failed to query VNPay on return; redirecting buyer to frontend");
            }
        }
        if (txnRef == null || txnRef.isBlank()) {
            return URI.create(frontendReturnUrl);
        }
        String separator = frontendReturnUrl.contains("?") ? "&" : "?";
        return URI.create(frontendReturnUrl + separator + "txn_ref="
                + URLEncoder.encode(txnRef, StandardCharsets.UTF_8));
    }

    /**
     * Đối soát tối đa {@code limit} payment_intent đang OPEN (tạo >= 2 phút
     * trước, trong vòng 24h sau expires_at). Gọi HTTP querydr ngoài transaction
     * giữ lock, sau đó áp dụng kết quả qua luồng nghiệp vụ chung.
     */
    public int reconcileOpenIntents(int limit) { return reconcileBatch(limit, null, null).processed(); }
    public record ReconcileBatch(int selected, int processed, OffsetDateTime createdAt, UUID id) {}
    public ReconcileBatch reconcileBatch(int limit, OffsetDateTime afterTime, UUID afterId) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<IntentRow> candidates = txTemplate.execute(status -> repository.lockCandidatesForReconcile(
                now.minusMinutes(2), now.minusHours(24), limit, afterTime, afterId));
        if (candidates == null || candidates.isEmpty()) {
            return new ReconcileBatch(0, 0, afterTime, afterId);
        }

        int processed = 0;
        for (IntentRow intent : candidates) {
            try {
                QueryResult qr = vnpayClient.querydr(intent.txnRef(), intent.createdAt(), "127.0.0.1");
                if (qr.status() == QueryStatus.SUCCESS || qr.status() == QueryStatus.FAILED) {
                    txTemplate.executeWithoutResult(status -> applyGatewayResult(
                            intent.txnRef(), qr.amountVnd(), qr.status() == QueryStatus.SUCCESS,
                            qr.responseCode(), qr.gatewayTxnNo(), qr.rawJson(), "QUERY"));
                    processed++;
                } else if (qr.status() == QueryStatus.NOT_FOUND) {
                    OffsetDateTime checkTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
                    if (!intent.expiresAt().isAfter(checkTime)) {
                        txTemplate.executeWithoutResult(
                                status -> expireIntentIfStillOpen(intent.txnRef(), checkTime));
                        processed++;
                    }
                }
            } catch (RuntimeException ex) {
                log.atWarn().addKeyValue(LogKeys.ORDER_ID, intent.orderId())
                        .addKeyValue("txn_ref", intent.txnRef())
                        .setCause(ex).log("Failed to reconcile open payment intent");
            }
        }
        var last = candidates.getLast();
        return new ReconcileBatch(candidates.size(), processed, last.createdAt(), last.id());
    }

    /**
     * Luồng nghiệp vụ chung duy nhất để IPN và querydr cập nhật trạng thái
     * thanh toán và đơn hàng. Quy tắc chống deadlock: luôn khoá đơn (orders)
     * trước, khoá intent (payment_intent) sau.
     */
    private PaymentResult applyGatewayResult(String txnRef, long amountVnd, boolean success,
            String responseCode, String gatewayTxnNo,
            String rawJson, String source) {
        var orderIdOpt = repository.findOrderIdByTxnRef(txnRef);
        if (orderIdOpt.isEmpty()) {
            return PaymentResult.of("01", "Order not found");
        }
        UUID orderId = orderIdOpt.get();

        // 1. Khoá đơn trước
        var order = booking.lockOrderForPayment(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn"));
        // 2. Khoá intent sau
        var intent = repository.lockIntentByTxnRef(txnRef)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy giao dịch"));

        // 3. Nếu intent đã SUCCEEDED thì trả 02 (IPN trùng)
        if ("SUCCEEDED".equals(intent.status())) {
            return PaymentResult.of("02", "Order already confirmed");
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // 4. Ghi mọi lần gọi IPN/querydr vào payment_transaction (append-only)
        repository.insertTransaction(intent.id(), source, success ? "SUCCESS" : "FAILED",
                responseCode, gatewayTxnNo, amountVnd, rawJson, now);

        if ("IPN".equals(source) && "07".equals(responseCode)) {
            repository.updateIntentStatus(intent.id(), "OPEN", now);
            if (List.of("PENDING", "EXPIRED", "CANCELLED").contains(order.status())) {
                booking.markNeedsReview(orderId, ReviewReason.PAYMENT_SUSPECTED);
            }
            return PaymentResult.of("00", "Confirm Success");
        }

        // 5. Nếu giao dịch thất bại: đánh dấu FAILED, giữ nguyên đơn để khách có thể thử lại
        if (!success) {
            repository.updateIntentStatus(intent.id(), "FAILED", now);
            realtime.publish(order.userId(), RealtimeEventType.PAYMENT_FAILED,
                    Map.of("order_id", orderId, "txn_ref", txnRef,
                            "response_code", responseCode == null ? "" : responseCode));
            return PaymentResult.of("00", "Confirm Success");
        }

        // 6. Tiền đã về cổng thành công -> intent chuyển sang SUCCEEDED
        boolean secondPayment = repository.hasOtherSucceededIntent(orderId, intent.id());
        repository.updateIntentStatus(intent.id(), "SUCCEEDED", now);
        if (secondPayment) {
            if (!"NEEDS_REVIEW".equals(order.status())) booking.markDuplicatePaymentForReview(orderId);
            return PaymentResult.of("00", "Confirm Success");
        }

        // 7. Đối chiếu số tiền với đơn trong DB
        if (amountVnd != order.totalAmount()) {
            if (List.of("PENDING", "EXPIRED", "CANCELLED").contains(order.status())) {
                booking.markNeedsReview(orderId, ReviewReason.AMOUNT_MISMATCH);
            } else {
                log.atError().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue("txn_ref", txnRef)
                        .log("Amount mismatch on order already in state {}", order.status());
            }
            return PaymentResult.of("00", "Confirm Success");
        }

        // 8. Xử lý theo trạng thái đơn và thời hạn giữ chỗ
        return switch (order.status()) {
            case "EXPIRED" -> {
                booking.markNeedsReview(orderId, ReviewReason.PAID_AFTER_EXPIRY);
                yield PaymentResult.of("00", "Confirm Success");
            }
            case "CANCELLED" -> {
                booking.markNeedsReview(orderId, ReviewReason.PAID_AFTER_CANCEL);
                yield PaymentResult.of("00", "Confirm Success");
            }
            case "PENDING" -> {
                if (!order.expiresAt().isAfter(now)) {
                    booking.markNeedsReview(orderId, ReviewReason.PAID_AFTER_EXPIRY);
                    yield PaymentResult.of("00", "Confirm Success");
                }
                SaleOutcome outcome = booking.markSold(orderId);
                if (outcome == SaleOutcome.SOLD) {
                    ticketing.issueForOrder(orderId);
                }
                yield PaymentResult.of("00", "Confirm Success");
            }
            default -> {
                log.atError().addKeyValue(LogKeys.ORDER_ID, orderId).addKeyValue("txn_ref", txnRef)
                        .log("Received successful payment for order already in state {}", order.status());
                yield PaymentResult.of("00", "Confirm Success");
            }
        };
    }

    private void expireIntentIfStillOpen(String txnRef, OffsetDateTime now) {
        var orderIdOpt = repository.findOrderIdByTxnRef(txnRef);
        if (orderIdOpt.isEmpty()) {
            return;
        }
        booking.lockOrderForPayment(orderIdOpt.get());
        repository.lockIntentByTxnRef(txnRef).ifPresent(locked -> {
            if ("OPEN".equals(locked.status())) {
                repository.updateIntentStatus(locked.id(), "EXPIRED", now);
            }
        });
    }

    private String buildSignedPaymentUrl(String txnRef, long amountVnd, CreatePaymentRequest.MethodEnum method,
            String clientIp, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", String.valueOf(Math.multiplyExact(amountVnd, 100L)));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", "Thanh toan don " + txnRef);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", (clientIp == null || clientIp.isBlank()) ? "127.0.0.1" : clientIp);
        params.put("vnp_CreateDate", VNP_TIME.format(createdAt.atZoneSameInstant(VN_ZONE)));
        params.put("vnp_ExpireDate", VNP_TIME.format(expiresAt.atZoneSameInstant(VN_ZONE)));
        if (method != null) {
            params.put("vnp_BankCode", method.getValue());
        }

        String query = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
        String secureHash = signer.signParams(params);
        return payUrl + "?" + query + "&vnp_SecureHash=" + secureHash;
    }

    private PaymentIntent toApiModel(IntentRow row) {
        return new PaymentIntent()
                .id(row.id())
                .orderId(row.orderId())
                .txnRef(row.txnRef())
                .amount(row.amount())
                .status(PaymentStatus.fromValue(row.status()))
                .paymentUrl(row.paymentUrl() == null ? null : URI.create(row.paymentUrl()))
                .expiresAt(row.expiresAt())
                .lastResponseCode(row.lastResponseCode());
    }
}
