package vingo.auth.web;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import vingo.auth.internal.AuthProperties;

@Component
public class AuthCookies {

    static final String REFRESH = "vingo_refresh";
    public static final String OAUTH_BROWSER = "vingo_oauth_browser";

    private final AuthProperties properties;
    private final Clock clock;

    /** Nhận chính sách bảo mật cookie và đồng hồ tính thời hạn. */
    AuthCookies(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Tạo cookie refresh với thời hạn còn lại của phiên. */
    public ResponseCookie refresh(String token, OffsetDateTime expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt.toInstant());
        return cookie(REFRESH, token, remaining.isNegative() ? Duration.ZERO : remaining);
    }

    /** Tạo cookie HttpOnly với SameSite=Lax và chính sách Secure của môi trường. */
    public ResponseCookie cookie(String name, String value, Duration ttl) {
        boolean secure = properties.secureCookies();
        return ResponseCookie.from(cookieName(name), value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(secure ? "/" : "/api/v1/auth")
                .maxAge(ttl)
                .build();
    }

    /** Đọc cookie theo tên hiệu lực của môi trường hiện tại. */
    public Optional<String> read(HttpServletRequest request, String name) {
        return Optional.ofNullable(WebUtils.getCookie(request, cookieName(name))).map(Cookie::getValue);
    }

    /** Áp dụng tiền tố __Host- khi cookie chạy qua HTTPS. */
    private String cookieName(String name) {
        return properties.secureCookies() ? "__Host-" + name : name;
    }
}
