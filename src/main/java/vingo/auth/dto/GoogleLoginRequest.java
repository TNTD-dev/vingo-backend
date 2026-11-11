package vingo.auth.dto;

import java.net.URI;

/** Identity claims from the authenticated Google principal. */
public record GoogleLoginRequest(String subject, String email, boolean emailVerified, String fullName, URI avatar) {
}
