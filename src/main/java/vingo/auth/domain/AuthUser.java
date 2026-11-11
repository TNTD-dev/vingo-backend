package vingo.auth.domain;

import java.net.URI;
import java.util.UUID;

import vingo.shared.security.Role;

public record AuthUser(UUID id, String email, String phone, String fullName, URI avatar, Role role, String status) {
}
