package vingo.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import vingo.payment.internal.VnpayClient;
import vingo.payment.internal.VnpayClient.QueryResult;
import vingo.support.CatalogFixture;

class VnpayQueryTest extends PaymentTestSupport {

    @MockitoBean
    private VnpayClient vnpayClient;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void reconcile_marksOrderPaidAndIssuesTickets_whenQuerydrReportsSuccess() {
        clock.set(Instant.parse("2026-11-13T03:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 2);
        String txnRef = openIntent(orderId);

        clock.advance(Duration.ofMinutes(3)); // Đủ >= 2 phút để job chọn đối soát
        when(vnpayClient.querydr(eq(txnRef), any(), any()))
                .thenReturn(QueryResult.success(2 * CatalogFixture.GA_PRICE, "99887766", "{\"vnp_ResponseCode\":\"00\"}"));

        paymentService.reconcileOpenIntents(50);

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(intentStatus(txnRef)).isEqualTo("SUCCEEDED");
        assertThat(txCount(txnRef, "QUERY", "SUCCESS")).isEqualTo(1);
        assertThat(ticketCount(orderId)).isEqualTo(2);
    }

    @Test
    void reconcile_marksIntentFailedAndKeepsOrderPending_whenQuerydrReportsFailed() {
        clock.set(Instant.parse("2026-11-13T03:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String txnRef = openIntent(orderId);

        clock.advance(Duration.ofMinutes(3));
        when(vnpayClient.querydr(eq(txnRef), any(), any()))
                .thenReturn(QueryResult.failed(CatalogFixture.GA_PRICE, "24", "02", "99887700", "{\"vnp_ResponseCode\":\"24\"}"));

        paymentService.reconcileOpenIntents(50);

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(intentStatus(txnRef)).isEqualTo("FAILED");
        assertThat(txCount(txnRef, "QUERY", "FAILED")).isEqualTo(1);
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void reconcile_marksIntentExpired_whenPastExpiryAndQuerydrReturnsNotFound() {
        clock.set(Instant.parse("2026-11-13T03:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String txnRef = openIntent(orderId);

        clock.advance(Duration.ofMinutes(15)); // Đã quá hạn giữ chỗ 10 phút
        when(vnpayClient.querydr(eq(txnRef), any(), any()))
                .thenReturn(QueryResult.notFound("91", "{\"vnp_ResponseCode\":\"91\"}"));

        paymentService.reconcileOpenIntents(50);

        assertThat(intentStatus(txnRef)).isEqualTo("EXPIRED");
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void reconcile_keepsIntentOpen_whenQuerydrUnavailable() {
        clock.set(Instant.parse("2026-11-13T03:00:00Z"));
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String txnRef = openIntent(orderId);

        clock.advance(Duration.ofMinutes(3));
        when(vnpayClient.querydr(eq(txnRef), any(), any()))
                .thenReturn(QueryResult.unavailable());

        paymentService.reconcileOpenIntents(50);

        assertThat(intentStatus(txnRef)).isEqualTo("OPEN");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(txCount(txnRef)).isZero();
    }

    @Test
    void vnpayReturn_redirects302ToFrontend_andDoesNotSellWhenSignatureInvalidOrQueryNotFound() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String txnRef = openIntent(orderId);

        when(vnpayClient.querydr(eq(txnRef), any(), any()))
                .thenReturn(QueryResult.notFound("91", "{\"vnp_ResponseCode\":\"91\"}"));

        Map<String, String> params = signedSuccessIpn(txnRef, CatalogFixture.GA_PRICE);
        params.put("vnp_SecureHash", "badhash");

        var res = callReturn(params).expectStatus().isEqualTo(302);
        String location = res.returnResult(Void.class).getResponseHeaders().getFirst("Location");

        assertThat(location).startsWith("http://localhost:5173/payment/result");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void vnpayReturn_queriesGatewayAndRedirects302_whenIntentStillOpen() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String txnRef = openIntent(orderId);

        when(vnpayClient.querydr(eq(txnRef), any(), any()))
                .thenReturn(QueryResult.success(CatalogFixture.GA_PRICE, "11223344", "{\"vnp_ResponseCode\":\"00\"}"));

        Map<String, String> params = signedSuccessIpn(txnRef, CatalogFixture.GA_PRICE);
        var res = callReturn(params).expectStatus().isEqualTo(302);
        String location = res.returnResult(Void.class).getResponseHeaders().getFirst("Location");

        assertThat(location).startsWith("http://localhost:5173/payment/result");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(ticketCount(orderId)).isEqualTo(1);
    }
}