package vingo.payment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vingo.support.CatalogFixture;

class VnpayIpnTest extends PaymentTestSupport {

    @Test
    void ipn_recordsSecondSuccessfulIntent_andMarksPaidOrderNeedsReview() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String first = openIntent(orderId);
        jdbc.sql("UPDATE payment_intent SET status = 'FAILED' WHERE txn_ref = :ref").param("ref", first).update();
        String second = first + "-second";
        jdbc.sql("INSERT INTO payment_intent(order_id, txn_ref, amount, expires_at) SELECT order_id, :ref, amount, expires_at FROM payment_intent WHERE txn_ref = :first")
                .param("ref", second).param("first", first).update();
        assertThat(rspCode(callIpn(signedSuccessIpn(first, CatalogFixture.GA_PRICE)))).isEqualTo("00");
        assertThat(rspCode(callIpn(signedSuccessIpn(second, CatalogFixture.GA_PRICE)))).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(intentStatus(second)).isEqualTo("SUCCEEDED");
        assertThat(ticketCount(orderId)).isEqualTo(1);
        assertThat(rspCode(callIpn(signedSuccessIpn(second, CatalogFixture.GA_PRICE)))).isEqualTo("02");
    }

    @Test
    void ipn_returns97_andDoesNotModifyOrderOrRecordTransaction_whenSignatureInvalid() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdSeatedOrder(buyer, showtime, showtime.event().seats().get(0));
        String txnRef = openIntent(orderId);

        Map<String, String> params = signedSuccessIpn(txnRef, CatalogFixture.VIP_PRICE);
        params.put("vnp_SecureHash", "0000" + params.get("vnp_SecureHash").substring(4));

        String response = callIpn(params);

        assertThat(rspCode(response)).isEqualTo("97");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(intentStatus(txnRef)).isEqualTo("OPEN");
        assertThat(txCount(txnRef)).isZero();
        assertThat(heldSeats(orderId)).isEqualTo(1);
        assertThat(soldSeats(orderId)).isZero();
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void ipn_returns00Then02_andSellsAndIssuesTicketsOnce_whenSuccessRepeated() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 3);
        String txnRef = openIntent(orderId);
        Map<String, String> params = signedSuccessIpn(txnRef, 3 * CatalogFixture.GA_PRICE);

        String first = callIpn(params);
        String second = callIpn(params);

        assertThat(rspCode(first)).isEqualTo("00");
        assertThat(rspCode(second)).isEqualTo("02");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(intentStatus(txnRef)).isEqualTo("SUCCEEDED");
        assertThat(heldStanding(showtime)).isZero();
        assertThat(soldStanding(showtime)).isEqualTo(3);
        assertThat(ticketCount(orderId)).isEqualTo(3);
        assertThat(txCount(txnRef, "IPN", "SUCCESS")).isEqualTo(1);
        assertThat(historyCount(orderId, "PENDING", "PAID")).isEqualTo(1);
    }

    @Test
    void ipn_returns00_andMarksOrderNeedsReviewWithoutIssuingTickets_whenAmountMismatch() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 2); // 400_000 VND
        String txnRef = openIntent(orderId);

        Map<String, String> wrongAmount = signedSuccessIpn(txnRef, CatalogFixture.GA_PRICE); // 200_000 VND
        String first = callIpn(wrongAmount);
        String duplicate = callIpn(wrongAmount);

        assertThat(rspCode(first)).isEqualTo("00");
        assertThat(rspCode(duplicate)).isEqualTo("02");
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(orderReviewReason(orderId)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(intentStatus(txnRef)).isEqualTo("SUCCEEDED");
        assertThat(heldStanding(showtime)).isZero();
        assertThat(soldStanding(showtime)).isZero();
        assertThat(ticketCount(orderId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "CANCELLED"})
    void ipn_returns00_andMarksNeedsReview_whenOrderAlreadyExpiredOrCancelled(String priorState) {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdSeatedOrder(buyer, showtime, showtime.event().seats().get(0));
        String txnRef = openIntent(orderId);

        if ("EXPIRED".equals(priorState)) {
            makeOverdue(orderId);
            booking.expireOverdueHolds(100);
        } else {
            cancelOrder(buyer, orderId);
        }
        assertThat(orderStatus(orderId)).isEqualTo(priorState);

        String response = callIpn(signedSuccessIpn(txnRef, CatalogFixture.VIP_PRICE));

        assertThat(rspCode(response)).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(orderReviewReason(orderId))
                .isEqualTo("EXPIRED".equals(priorState) ? "PAID_AFTER_EXPIRY" : "PAID_AFTER_CANCEL");
        assertThat(intentStatus(txnRef)).isEqualTo("SUCCEEDED");
        assertThat(soldSeats(orderId)).isZero();
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void ipn_returns00_andMarksNeedsReviewReleasingInventory_whenPendingOrderPastExpiry() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 2);
        String txnRef = openIntent(orderId);
        makeOverdue(orderId); // Vẫn PENDING trong DB nhưng đã quá expires_at

        String response = callIpn(signedSuccessIpn(txnRef, 2 * CatalogFixture.GA_PRICE));

        assertThat(rspCode(response)).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(orderReviewReason(orderId)).isEqualTo("PAID_AFTER_EXPIRY");
        assertThat(heldStanding(showtime)).isZero();
        assertThat(soldStanding(showtime)).isZero();
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void ipn_andExpireHolds_leaveConsistentState_whenRacingOnOverdueOrder() throws Exception {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 3);
        String txnRef = openIntent(orderId);
        makeOverdue(orderId);
        Map<String, String> signed = signedSuccessIpn(txnRef, 3 * CatalogFixture.GA_PRICE);

        var results = race(List.of(
                () -> callIpn(signed),
                () -> {
                    booking.expireOverdueHolds(100);
                    return "expired";
                }
        ));

        assertThat(rspCode(String.valueOf(results.get(0)))).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(orderReviewReason(orderId)).isEqualTo("PAID_AFTER_EXPIRY");
        assertThat(heldStanding(showtime)).isZero();
        assertThat(soldStanding(showtime)).isZero();
        assertThat(ticketCount(orderId)).isZero();
    }

    @Test
    void ipn_andExpireHolds_sellNormally_whenRacingOnNonExpiredOrder() throws Exception {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 2);
        String txnRef = openIntent(orderId);
        Map<String, String> signed = signedSuccessIpn(txnRef, 2 * CatalogFixture.GA_PRICE);

        var results = race(List.of(
                () -> callIpn(signed),
                () -> {
                    booking.expireOverdueHolds(100);
                    return "expired";
                }
        ));

        assertThat(rspCode(String.valueOf(results.get(0)))).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(heldStanding(showtime)).isZero();
        assertThat(soldStanding(showtime)).isEqualTo(2);
        assertThat(ticketCount(orderId)).isEqualTo(2);
    }

    @Test
    void ipn_recordsFailedAttempt_andAllowsSubsequentSuccessOnSameIntent() {
        var showtime = catalog.onSaleShowtime();
        UUID buyer = buyerWithProfile();
        UUID orderId = holdStandingOrder(buyer, showtime, 1);
        String txnRef = openIntent(orderId);

        String failedResp = callIpn(signedFailedIpn(txnRef, CatalogFixture.GA_PRICE, "24"));
        assertThat(rspCode(failedResp)).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(intentStatus(txnRef)).isEqualTo("FAILED");
        assertThat(txCount(txnRef, "IPN", "FAILED")).isEqualTo(1);
        assertThat(ticketCount(orderId)).isZero();

        String retrySuccess = callIpn(signedSuccessIpn(txnRef, CatalogFixture.GA_PRICE));
        assertThat(rspCode(retrySuccess)).isEqualTo("00");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(intentStatus(txnRef)).isEqualTo("SUCCEEDED");
        assertThat(ticketCount(orderId)).isEqualTo(1);
    }

    @Test
    void ipn_returns01_whenTxnRefDoesNotExist() {
        String response = callIpn(signedSuccessIpn("UNKNOWN-1", 500_000));
        assertThat(rspCode(response)).isEqualTo("01");
    }
}
