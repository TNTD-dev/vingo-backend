package vingo.auth.internal;

import java.time.OffsetDateTime;

import vingo.auth.AuthUser;

public record AuthResponse(String accessToken, String refreshToken, OffsetDateTime expiresAt,
                           int expiresIn, AuthUser user, boolean profileCompleted) {
}
