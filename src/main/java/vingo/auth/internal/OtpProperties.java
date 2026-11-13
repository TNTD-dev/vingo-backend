package vingo.auth.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vingo.auth.otp")
public record OtpProperties(
        Duration ttl,
        int maxAttempts,
        Duration resendCooldown,
        int resendMaxPerWindow,
        Duration resendWindow,
        int registerMaxPerWindow,
        Duration registerWindow) {
}
