package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vingo.support.CatalogFixture.uniquePhone;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;

import vingo.api.model.PhoneOtpRequest;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.RecordingSmsSender;

/** Hạn mức SMS theo IP vẫn chặn được khi Redis không kết nối được. */
@Import(PhoneVerificationRedisDownTest.DeadRedis.class)
class PhoneVerificationRedisDownTest extends IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class DeadRedis {
        @Bean
        @Primary
        DataRedisConnectionDetails deadRedis() {
            return new DataRedisConnectionDetails() {
                @Override
                public Standalone getStandalone() {
                    return Standalone.of("127.0.0.1", 1);
                }
            };
        }
    }

    @Autowired private RecordingSmsSender sms;
    @Autowired private StringRedisTemplate redis;

    private final List<UUID> buyers = new ArrayList<>();

    @BeforeEach
    void freezeClockAndResetSms() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        sms.reset();
    }

    @AfterEach
    void restoreClockAndReleasePhoneSendLimits() {
        if (!buyers.isEmpty()) {
            jdbc.sql("DELETE FROM phone_otp_send WHERE user_id IN (:ids)").param("ids", buyers).update();
        }
        sms.reset();
        clock.reset();
    }

    @Test
    void requestPhoneOtp_limitsTwentyPerIp_whenRedisDown() {
        assertThatThrownBy(() -> redis.hasKey("phone-verification-redis-down-test"))
                .isInstanceOf(RedisConnectionFailureException.class);

        for (int i = 0; i < 21; i++) {
            UUID buyer = catalog.buyer();
            buyers.add(buyer);
            jdbc.sql("UPDATE users SET phone_verified_at = NULL WHERE id = :id").param("id", buyer).update();
            var response = client.post().uri("/api/v1/me/phone/otp")
                    .headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                    .body(new PhoneOtpRequest(uniquePhone())).exchange();
            if (i < 20) {
                response.expectStatus().isOk();
            } else {
                response.expectStatus().isEqualTo(429)
                        .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "86400")
                        .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
            }
        }
        assertThat(sms.sent()).hasSize(20);
        assertThat(jdbc.sql("SELECT count(*) FROM phone_otp_send WHERE user_id IN (:ids)")
                .param("ids", buyers).query(Integer.class).single()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(DISTINCT client_key) FROM phone_otp_send WHERE user_id IN (:ids)")
                .param("ids", buyers).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM phone_otp_send WHERE user_id = :id")
                .param("id", buyers.getLast()).query(Integer.class).single()).isZero();
    }
}
