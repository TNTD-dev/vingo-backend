package vingo.auth.web;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.AuthApi;
import vingo.api.model.LoginRequest;
import vingo.api.model.LogoutRequest;
import vingo.api.model.OtpSession;
import vingo.api.model.RefreshResult;
import vingo.api.model.RefreshTokenRequest;
import vingo.api.model.RegisterRequest;
import vingo.api.model.ResendOtpRequest;
import vingo.api.model.TokenPair;
import vingo.api.model.User;
import vingo.api.model.VerifyOtpRequest;
import vingo.auth.AuthService;
import vingo.auth.AuthUser;
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

    /** Nối contract auth với nghiệp vụ phiên và xử lý cookie của request. */
    AuthController(AuthService auth, CurrentUser currentUser, AuthCookies cookies,
                   AuthWebConfig web, HttpServletRequest request) {
        this.auth = auth;
        this.currentUser = currentUser;
        this.cookies = cookies;
        this.web = web;
        this.request = request;
    }

    /** Đăng ký tài khoản UNVERIFIED và gửi OTP qua email. */
    @Override
    public ResponseEntity<OtpSession> register(RegisterRequest body) {
        var session = auth.register(body.getFullName(), body.getEmail(), body.getPhone(), body.getPassword());
        var response = new OtpSession()
                .otpSessionId(session.otpSessionId())
                .expiresAt(session.expiresAt())
                .channel(OtpSession.ChannelEnum.fromValue(session.channel()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /** Xác thực OTP, kích hoạt tài khoản và cấp cặp token. */
    @Override
    public ResponseEntity<TokenPair> verifyOtp(VerifyOtpRequest body) {
        var session = auth.verifyOtp(body.getOtpSessionId(), body.getOtp());
        var result = new TokenPair(session.accessToken(), session.refreshToken(),
                session.expiresIn(), toApiUser(session.user()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken(), session.expiresAt()).toString())
                .body(result);
    }

    /** Gửi lại OTP cho phiên đang chờ xác thực. */
    @Override
    public ResponseEntity<Void> resendOtp(ResendOtpRequest body) {
        auth.resendOtp(body.getOtpSessionId());
        return ResponseEntity.accepted().build();
    }

    /** Đăng nhập bằng email/SĐT và mật khẩu, cấp TokenPair và đặt cookie refresh. */
    @Override
    public ResponseEntity<TokenPair> login(LoginRequest body) {
        var session = auth.login(body.getIdentifier(), body.getPassword(), body.getDevice());
        var result = new TokenPair(session.accessToken(), session.refreshToken(),
                session.expiresIn(), toApiUser(session.user()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken(), session.expiresAt()).toString())
                .body(result);
    }

    /** Trả 404 khi trang bắt đầu không được filter OAuth2 tiếp nhận. */
    @Override
    public ResponseEntity<Void> startGoogleLogin() {
        throw googleUnavailable();
    }

    /** Trả 404 khi callback không được filter OAuth2 tiếp nhận. */
    @Override
    public ResponseEntity<Void> completeGoogleLogin() {
        throw googleUnavailable();
    }

    /** Nhận refresh token qua cookie cùng Origin hợp lệ hoặc body JSON. */
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
        var result = new RefreshResult(session.accessToken(), session.expiresIn(), toApiUser(session.user()));
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (useCookie) {
            response.header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken(), session.expiresAt()).toString());
        } else {
            result.setRefreshToken(session.refreshToken());
        }
        return response.body(result);
    }

    /** Thu hồi phiên theo bearer token và xoá cookie refresh. */
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

    private static User toApiUser(AuthUser account) {
        return new User().id(account.id()).email(account.email()).phone(account.phone())
                .fullName(account.fullName()).avatarUrl(account.avatar())
                .role(User.RoleEnum.fromValue(account.role().name()))
                .status(User.StatusEnum.fromValue(account.status()));
    }

    /** Dùng chung ProblemDetail cho các đường dẫn Google khi tính năng tắt. */
    private static DomainException googleUnavailable() {
        return new DomainException(ErrorCode.NOT_FOUND, "Đăng nhập Google chưa được bật");
    }
}
