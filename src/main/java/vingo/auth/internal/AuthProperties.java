package vingo.auth.internal;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vingo.auth")
public record AuthProperties(URI frontendReturnUrl, boolean secureCookies, Duration refreshTokenTtl) {
}
