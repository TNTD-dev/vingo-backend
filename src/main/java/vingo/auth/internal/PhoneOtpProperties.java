package vingo.auth.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Giới hạn gửi SMS; TTL và số lần nhập sai dùng OtpProperties chung. */
@ConfigurationProperties("vingo.auth.phone-otp")
public record PhoneOtpProperties(
        Duration resendCooldown,
        int userMaxPerWindow,
        int phoneMaxPerWindow,
        int ipMaxPerWindow,
        Duration window) {
}
