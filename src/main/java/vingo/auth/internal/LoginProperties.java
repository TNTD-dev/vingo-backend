package vingo.auth.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vingo.auth.login")
public record LoginProperties(int maxAttempts, Duration attemptWindow, Duration lockDuration) {
}
