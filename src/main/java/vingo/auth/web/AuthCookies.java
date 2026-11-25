package vingo.auth.web;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Consumer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.AuthResponse;
import vingo.auth.internal.LoginClient;

@Component
public class AuthCookies {

    static final String SESSION = "vingo_session";
    static final String DEVICE = "vingo_device";
    public static final String OAUTH_BROWSER = "vingo_oauth_browser";

    private final AuthProperties properties;
    private final Clock clock;

    /** Nhận chính sách bảo mật cookie và đồng hồ tính thời hạn. */
    AuthCookies(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Tạo cookie phiên với thời hạn còn lại của phiên. */
    public ResponseCookie session(String token, OffsetDateTime expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt.toInstant());
        return cookie(SESSION, token, remaining.isNegative() ? Duration.ZERO : remaining);
    }

    public ResponseCookie clearSession() {
        return cookie(SESSION, "", Duration.ZERO);
    }

    public LoginClient loginClient(HttpServletRequest request) {
        return new LoginClient(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr(),
                read(request, DEVICE).orElse(null));
    }

    public void writeLoginCookies(AuthResponse login, Consumer<String> addHeader) {
        addHeader.accept(session(login.token(), login.expiresAt()).toString());
        if (login.newDeviceToken() != null) {
            addHeader.accept(cookie(DEVICE, login.newDeviceToken(), Duration.ofDays(365)).toString());
        }
    }

    /** Tạo cookie HttpOnly với SameSite=Lax và chính sách Secure của môi trường. */
    public ResponseCookie cookie(String name, String value, Duration ttl) {
        boolean secure = properties.secureCookies();
        return ResponseCookie.from(cookieName(name), value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(!secure && OAUTH_BROWSER.equals(name) ? "/api/v1/auth" : "/")
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
