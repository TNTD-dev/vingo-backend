package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import vingo.api.model.OtpSession;
import vingo.api.model.PhoneOtpRequest;
import vingo.api.model.PhoneVerifyRequest;
import vingo.auth.internal.sms.DemoSmsSender;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

/** Chế độ SMS_PROVIDER=demo: không gửi SMS, mã OTP nằm trong header phản hồi và dùng được để xác minh. */
// Thay thế bean recordingSmsSender của base bằng bản demo (cùng tên, cùng @Primary).
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DemoOtpApiTest.DemoSmsConfig.class)
class DemoOtpApiTest extends IntegrationTest {

    private static final String REQUEST = "/api/v1/me/phone/otp";
    private static final String VERIFY = "/api/v1/me/phone/verify";

    private UUID buyer;

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoSmsConfig {
        @Bean("recordingSmsSender")
        @Primary
        DemoSmsSender demoSmsSender() {
            return new DemoSmsSender();
        }
    }

    @AfterEach
    void releaseSendLimits() {
        if (buyer != null) {
            jdbc.sql("DELETE FROM phone_otp_send WHERE user_id = :id").param("id", buyer).update();
        }
    }

    @Test
    void requestPhoneOtp_returnsCodeInHeader_andTheCodeVerifiesThePhone() {
        buyer = catalog.buyer();
        jdbc.sql("UPDATE users SET phone_verified_at = NULL WHERE id = :id").param("id", buyer).update();
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
        assertThat(jdbc.sql("SELECT phone_verified_at IS NOT NULL FROM users WHERE id = :id")
                .param("id", buyer).query(Boolean.class).single()).isTrue();
    }
}
