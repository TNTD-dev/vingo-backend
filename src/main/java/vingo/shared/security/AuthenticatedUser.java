package vingo.shared.security;

import java.util.UUID;

/** Người dùng đang gọi API, lấy từ access token. */
public record AuthenticatedUser(UUID id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
