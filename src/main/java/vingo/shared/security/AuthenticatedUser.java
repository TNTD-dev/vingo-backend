package vingo.shared.security;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/** Người dùng đang gọi API, lấy từ phiên. */
public record AuthenticatedUser(UUID userId, Role role, UUID sessionId, OffsetDateTime expiresAt) implements AuthenticatedPrincipal {

    public UUID id() {
        return userId;
    }

    @Override
    public String getName() {
        return userId.toString();
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
