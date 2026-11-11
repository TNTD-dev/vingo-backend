package vingo.auth.dto;

import java.time.OffsetDateTime;

import vingo.auth.domain.AuthUser;

public record AuthResponse(String accessToken, String refreshToken, OffsetDateTime expiresAt,
                           int expiresIn, AuthUser user) {
}
