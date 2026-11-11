package vingo.auth.web;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.AuthApi;
import vingo.api.model.LogoutRequest;
import vingo.api.model.RefreshResult;
import vingo.api.model.RefreshTokenRequest;
import vingo.api.model.User;
import vingo.auth.application.AuthService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthController implements AuthApi {

    private final AuthService auth;
    private final CurrentUser currentUser;
    private final AuthCookies cookies;
    private final AuthWebConfig web;
    private final HttpServletRequest request;

    AuthController(AuthService auth, CurrentUser currentUser, AuthCookies cookies,
                   AuthWebConfig web, HttpServletRequest request) {
        this.auth = auth;
        this.currentUser = currentUser;
        this.cookies = cookies;
        this.web = web;
        this.request = request;
    }

    @Override
    public ResponseEntity<Void> startGoogleLogin() {
        throw googleUnavailable();
    }

    @Override
    public ResponseEntity<Void> completeGoogleLogin() {
        throw googleUnavailable();
    }

    @Override
    public ResponseEntity<RefreshResult> refreshToken(RefreshTokenRequest body) {
        boolean useCookie = body == null;
        String refreshToken;
        if (useCookie) {
            refreshToken = cookies.read(request, AuthCookies.REFRESH)
                    .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "Thiếu refresh token"));
            if (!web.frontendOrigin().equals(request.getHeader(HttpHeaders.ORIGIN))) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Origin không được phép làm mới phiên");
            }
        } else {
            refreshToken = body.getRefreshToken();
        }

        var session = auth.refresh(refreshToken);
        var account = session.user();
        var user = new User().id(account.id()).email(account.email()).phone(account.phone())
                .fullName(account.fullName()).avatarUrl(account.avatar())
                .role(User.RoleEnum.fromValue(account.role().name())).status(User.StatusEnum.fromValue(account.status()));
        var result = new RefreshResult(session.accessToken(), session.expiresIn(), user);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (useCookie) {
            response.header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken(), session.expiresAt()).toString());
        } else {
            result.setRefreshToken(session.refreshToken());
        }
        return response.body(result);
    }

    @Override
    public ResponseEntity<Void> logout(LogoutRequest body) {
        var user = currentUser.require();
        boolean allDevices = body != null && Boolean.TRUE.equals(body.getAllDevices());
        var sessionId = currentUser.sessionId();
        if (sessionId.isEmpty() && !allDevices) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Access token không xác định phiên");
        }
        auth.logout(user.id(), sessionId.orElse(null), allDevices);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.cookie(AuthCookies.REFRESH, "", Duration.ZERO).toString())
                .build();
    }

    private static DomainException googleUnavailable() {
        return new DomainException(ErrorCode.NOT_FOUND, "Đăng nhập Google chưa được bật");
    }
}
