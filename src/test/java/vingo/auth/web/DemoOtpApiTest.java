package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import vingo.api.model.OtpSession;
import vingo.api.model.PhoneOtpRequest;
import vingo.api.model.PhoneVerifyRequest;
import vingo.auth.internal.sms.DemoSmsSender;
import vingo.auth.internal.sms.SmsSender;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

/** Chế độ SMS_PROVIDER=demo: không gửi SMS, mã OTP nằm trong header phản hồi và dùng được để xác minh. */
@TestPropertySource(properties = "vingo.sms.provider=demo")
class DemoOtpApiTest extends IntegrationTest {

    private static final String REQUEST = "/api/v1/me/phone/otp";
    private static final String VERIFY = "/api/v1/me/phone/verify";

    @Autowired private SmsSender sms;

    private UUID buyer;

    @AfterEach
    void releaseSendLimits() {
        if (buyer != null) {
            jdbc.sql("DELETE FROM otp_verification WHERE user_id = :id AND purpose = 'PHONE'").param("id", buyer).update();
        }
    }

    @Test
    void requestPhoneOtp_returnsCodeInHeader_andTheCodeVerifiesThePhone() {
        assertThat(sms).isInstanceOf(DemoSmsSender.class);
        buyer = catalog.buyer();
        jdbc.sql("UPDATE user_account SET phone_verified_at = NULL WHERE id = :id").param("id", buyer).update();
        String phone = "09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        String token = tokenFor(buyer, Role.BUYER);

        var result = client.post().uri(REQUEST).headers(h -> h.setBearerAuth(token))
                .body(new PhoneOtpRequest(phone)).exchange()
                .expectStatus().isOk()
                .expectHeader().exists(DemoSmsSender.HEADER)
                .expectBody(OtpSession.class).returnResult();
        String code = result.getResponseHeaders().getFirst(DemoSmsSender.HEADER);
        assertThat(code).matches("[0-9]{6}");
        assertThat(result.getResponseBody()).isNotNull();

        client.post().uri(VERIFY).headers(h -> h.setBearerAuth(token))
                .body(new PhoneVerifyRequest(result.getResponseBody().getOtpSessionId(), code)).exchange()
                .expectStatus().isOk();
        assertThat(jdbc.sql("SELECT phone_verified_at IS NOT NULL FROM user_account WHERE id = :id")
                .param("id", buyer).query(Boolean.class).single()).isTrue();
    }
}
