package vingo.auth.web.oauth2;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import vingo.auth.application.GoogleLoginService;
import vingo.auth.dto.GoogleLoginRequest;
import vingo.auth.internal.AuthProperties;
import vingo.auth.web.AuthCookies;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Component
class GoogleLoginHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleLoginHandler.class);
    private static final Set<String> LOGGABLE_OAUTH_ERRORS = Set.of(
            "access_denied", "authorization_request_not_found", "invalid_state_parameter", "invalid_request",
            "invalid_client", "invalid_grant", "invalid_token_response", "invalid_id_token", "invalid_nonce",
            "invalid_user_info_response", "server_error", "temporarily_unavailable");

    private final GoogleLoginService loginService;
    private final AuthProperties properties;
    private final AuthCookies cookies;

    /** Nối danh tính OIDC đã xác minh với phiên Vingo và URL trả về web. */
    GoogleLoginHandler(GoogleLoginService loginService, AuthProperties properties, AuthCookies cookies) {
        this.loginService = loginService;
        this.properties = properties;
        this.cookies = cookies;
    }

    /** Tạo phiên Vingo và chuyển về web với trạng thái đăng nhập tương ứng. */
    void success(HttpServletRequest request, HttpServletResponse response,
                 Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof OidcUser google)) {
            log.info("Google sign-in rejected: reason=missing_oidc_user");
            redirect(response, "failed");
            return;
        }

        String result;
        try {
            var login = loginService.login(new GoogleLoginRequest(google.getSubject(), google.getEmail(),
                    Boolean.TRUE.equals(google.getEmailVerified()), google.getFullName(), avatar(google.getPicture())));
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.refresh(login.refreshToken(), login.expiresAt()).toString());
            result = login.created() ? "created" : "success";
        } catch (DomainException ex) {
            log.info("Google sign-in rejected: reason={}", ex.code());
            result = ex.code() == ErrorCode.EMAIL_TAKEN ? "email_taken" : "failed";
        } catch (RuntimeException ex) {
            log.warn("Google sign-in failed: reason=unexpected_error");
            result = "failed";
        }
        redirect(response, result);
    }

    /** Ghi mã lỗi OAuth2 đã biết và chuyển về web với trạng thái thất bại. */
    void failure(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        String code = failure instanceof OAuth2AuthenticationException oauth ? oauth.getError().getErrorCode() : "";
        String reason = LOGGABLE_OAUTH_ERRORS.contains(code) ? code : "authentication_failed";
        log.info("Google OAuth2 callback rejected: reason={}", reason);
        redirect(response, "failed");
    }

    /** Trả trạng thái qua URL frontend đã cấu hình và ngăn lưu cache. */
    private void redirect(HttpServletResponse response, String result) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(UriComponentsBuilder.fromUri(properties.frontendReturnUrl())
                .replaceQueryParam("google_login", result).build(true).toUriString());
    }

    /** Chỉ nhận URL HTTPS hợp lệ từ claim picture. */
    private static URI avatar(String picture) {
        if (picture == null || picture.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(picture);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
