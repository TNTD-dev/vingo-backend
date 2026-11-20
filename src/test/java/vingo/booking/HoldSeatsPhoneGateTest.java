package vingo.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vingo.api.model.OtpSession;
import vingo.api.model.PhoneOtpRequest;
import vingo.api.model.PhoneVerifyRequest;
import vingo.shared.security.Role;
import vingo.support.CatalogFixture.Showtime;
import vingo.support.RecordingSmsSender;

/** Cổng xác minh phải chặn trước idempotency và mọi thay đổi đơn hoặc ghế. */
class HoldSeatsPhoneGateTest extends BookingTestSupport {

    @Autowired private RecordingSmsSender sms;

    private Showtime showtime;
    private UUID buyer;
    private String token;
    private String key;
    private String request;

    @BeforeEach
    void createVerifiedBuyerAndAvailableSeat() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        sms.reset();
        showtime = catalog.onSaleShowtime();
        buyer = catalog.buyer();
        token = buyerToken(buyer);
        key = UUID.randomUUID().toString();
        request = seats(showtime.event().seat(1));
    }

    @AfterEach
    void restoreClock() {
        jdbc.sql("DELETE FROM otp_verification WHERE purpose = 'PHONE' AND user_id = :id").param("id", buyer).update();
        sms.reset();
        clock.reset();
    }

    @Test
    void holdSeats_returns422ForUnverifiedBuyer_withoutOrderIdempotencyOrSeatChanges() {
        unverify();
        assertBlockedWithoutWrites();
    }

    @Test
    void holdSeats_returns422ForGoogleStyleBuyerWithoutPhone_withoutWrites() {
        jdbc.sql("UPDATE user_account SET phone = NULL, phone_verified_at = NULL WHERE id = :id")
                .param("id", buyer).update();
        assertBlockedWithoutWrites();
    }

    @Test
    void holdSeats_returns201ForVerifiedBuyer() {
        holdOk(token, showtime.id(), key, request);
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void holdSeats_returns201ForAdminWithoutVerifiedPhone() {
        jdbc.sql("UPDATE user_account SET role = 'ADMIN', phone = NULL, phone_verified_at = NULL WHERE id = :id")
                .param("id", buyer).update();
        holdOk(tokenFor(buyer, Role.ADMIN), showtime.id(), key, request);
        assertThat(orderCount(buyer)).isEqualTo(1);
    }

    @Test
    void holdSeats_returns201WithSameIdempotencyKey_afterBuyerVerifiesPhoneThroughApi() {
        unverify();
        assertBlockedWithoutWrites();
        String phone = jdbc.sql("SELECT phone FROM user_account WHERE id = :id")
                .param("id", buyer).query(String.class).single();
        OtpSession session = client.post().uri("/api/v1/me/phone/otp")
                .headers(h -> h.setBearerAuth(token)).body(new PhoneOtpRequest(phone))
                .exchange().expectStatus().isOk().expectBody(OtpSession.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        client.post().uri("/api/v1/me/phone/verify").headers(h -> h.setBearerAuth(token))
                .body(new PhoneVerifyRequest(session.getOtpSessionId(), sms.lastCode(phone)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.phone_verified").isEqualTo(true);
        holdOk(token, showtime.id(), key, request);
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void holdSeats_checksVerificationBeforeReturningAnExistingIdempotentResponse() {
        holdOk(token, showtime.id(), key, request);
        unverify();
        problem(hold(token, showtime.id(), key, request), 422, "PHONE_VERIFICATION_REQUIRED");
        assertThat(orderCount(buyer)).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    private void unverify() {
        jdbc.sql("UPDATE user_account SET phone_verified_at = NULL WHERE id = :id").param("id", buyer).update();
    }

    private void assertBlockedWithoutWrites() {
        problem(hold(token, showtime.id(), key, request), 422, "PHONE_VERIFICATION_REQUIRED");
        assertThat(orderCount(buyer)).isZero();
        assertThat(idempotencyCount()).isZero();
        assertAvailable(showtime, showtime.event().seat(1));
    }

    private int idempotencyCount() {
        return jdbc.sql("SELECT count(*) FROM idempotency_record WHERE user_id = :user")
                .param("user", buyer).query(Integer.class).single();
    }
}
