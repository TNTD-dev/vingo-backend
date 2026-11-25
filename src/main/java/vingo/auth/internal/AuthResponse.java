package vingo.auth.internal;

import java.time.OffsetDateTime;

import vingo.auth.AuthUser;

public record AuthResponse(String token, OffsetDateTime expiresAt, AuthUser user, String newDeviceToken) {
    @Override public String toString() { return "AuthResponse[redacted]"; }
}
