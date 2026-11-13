package vingo.payment.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import vingo.api.PaymentApi;
import vingo.api.model.CreatePaymentRequest;
import vingo.api.model.HandleVnpayIpn200Response;
import vingo.api.model.HandleVnpayIpn200Response.RspCodeEnum;
import vingo.api.model.PaymentIntent;
import vingo.payment.PaymentResult;
import vingo.payment.PaymentService;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PaymentController implements PaymentApi {

    private final PaymentService paymentService;
    private final HttpServletRequest httpRequest;

    public PaymentController(PaymentService paymentService, HttpServletRequest httpRequest) {
        this.paymentService = paymentService;
        this.httpRequest = httpRequest;
    }

    @Override
    public ResponseEntity<PaymentIntent> createPayment(
            UUID orderId, UUID idempotencyKey, CreatePaymentRequest createPaymentRequest) {
        PaymentIntent intent = paymentService.createPayment(
                orderId, idempotencyKey, createPaymentRequest, extractClientIp());
        return ResponseEntity.status(HttpStatus.CREATED).body(intent);
    }

    @Override
    public ResponseEntity<PaymentIntent> getPayment(UUID orderId) {
        return ResponseEntity.ok(paymentService.getPayment(orderId));
    }

    @Override
    public ResponseEntity<HandleVnpayIpn200Response> handleVnpayIpn(
            String vnpTxnRef, Long vnpAmount, String vnpResponseCode,
            String vnpTransactionStatus, String vnpSecureHash, String vnpTransactionNo) {
        PaymentResult result = paymentService.handleVnpayIpn(extractQueryParams());
        return ResponseEntity.ok(new HandleVnpayIpn200Response(
                RspCodeEnum.fromValue(result.rspCode()), result.message()));
    }

    @Override
    public ResponseEntity<Void> handleVnpayReturn() {
        URI redirectUri = paymentService.handleVnpayReturn(extractQueryParams(), extractClientIp());
        return ResponseEntity.status(HttpStatus.FOUND).location(redirectUri).build();
    }

    private Map<String, String> extractQueryParams() {
        Map<String, String> params = new LinkedHashMap<>();
        httpRequest.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0 && values[0] != null) {
                params.put(key, values[0]);
            }
        });
        return params;
    }

    private String extractClientIp() {
        String forwarded = httpRequest.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remoteAddr = httpRequest.getRemoteAddr();
        return (remoteAddr == null || remoteAddr.isBlank()) ? "127.0.0.1" : remoteAddr;
    }
}
