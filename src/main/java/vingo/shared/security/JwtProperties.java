package vingo.shared.security;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Cấu hình access token. {@code secret} chỉ đọc từ biến môi trường JWT_SECRET. */
@Validated
@ConfigurationProperties("vingo.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32) String secret,
        String issuer,
        Duration accessTokenTtl) {
}
