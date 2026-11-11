package vingo.auth.web;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import vingo.auth.internal.AuthProperties;

@Component
public class AuthCookies {

    static final String REFRESH = "vingo_refresh";
    public static final String OAUTH_BROWSER = "vingo_oauth_browser";

    private final AuthProperties properties;
    private final Clock clock;

    AuthCookies(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public ResponseCookie refresh(String token, OffsetDateTime expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt.toInstant());
        return cookie(REFRESH, token, remaining.isNegative() ? Duration.ZERO : remaining);
    }

    public ResponseCookie cookie(String name, String value, Duration ttl) {
        return ResponseCookie.from(cookieName(name), value)
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Lax")
                .path(properties.secureCookies() ? "/" : "/api/v1/auth")
                .maxAge(ttl)
                .build();
    }

    public Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String expectedName = cookieName(name);
        return Arrays.stream(cookies).filter(cookie -> expectedName.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst();
    }

    private String cookieName(String name) {
        return properties.secureCookies() ? "__Host-" + name : name;
    }
}
