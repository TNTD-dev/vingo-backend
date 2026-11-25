package vingo.auth.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.AuthApi;
import vingo.api.model.ForgotPasswordRequest;
import vingo.api.model.LoginRequest;
import vingo.api.model.LoginResult;
import vingo.api.model.LogoutRequest;
import vingo.api.model.OtpSession;
import vingo.api.model.RegisterRequest;
import vingo.api.model.ResendOtpRequest;
import vingo.api.model.ResetPasswordRequest;
import vingo.api.model.User;
import vingo.api.model.VerifyOtpRequest;
import vingo.auth.AuthService;
import vingo.auth.AuthUser;
import vingo.auth.internal.AuthResponse;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthController implements AuthApi {

    private final AuthService auth;
    private final CurrentUser currentUser;
    private final AuthCookies cookies;
    private final HttpServletRequest request;

    /** Nối contract auth với nghiệp vụ phiên và xử lý cookie của request. */
    AuthController(AuthService auth, CurrentUser currentUser, AuthCookies cookies,
                   HttpServletRequest request) {
        this.auth = auth;
        this.currentUser = currentUser;
        this.cookies = cookies;
        this.request = request;
    }

    /** Đăng ký tài khoản UNVERIFIED và gửi OTP qua email. */
    @Override
    public ResponseEntity<OtpSession> register(RegisterRequest body) {
        var session = auth.register(body.getFullName(), body.getEmail(), body.getPhone(),
                body.getPassword(), request.getRemoteAddr());
        var response = new OtpSession()
                .otpSessionId(session.otpId())
                .expiresAt(session.expiresAt())
                .channel(OtpSession.ChannelEnum.fromValue(session.channel()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /** Xác thực OTP, kích hoạt tài khoản và mở phiên. */
    @Override
    public ResponseEntity<LoginResult> verifyOtp(VerifyOtpRequest body) {
        return loggedIn(auth.verifyOtp(body.getOtpSessionId(), body.getOtp(), cookies.loginClient(request)));
    }

    /** Gửi lại OTP: mỗi lần gửi là một phiên mới; phiên cũ hết hiệu lực, dùng otp_session_id trong phản hồi. */
    @Override
    public ResponseEntity<OtpSession> resendOtp(ResendOtpRequest body) {
        var session = auth.resendOtp(body.getOtpSessionId());
        return ResponseEntity.accepted().body(new OtpSession()
                .otpSessionId(session.otpId()).expiresAt(session.expiresAt())
                .channel(OtpSession.ChannelEnum.fromValue(session.channel())));
    }

    /** Yêu cầu OTP đặt lại mật khẩu, luôn trả 202 để không lộ email tồn tại. */
    @Override
    public ResponseEntity<OtpSession> forgotPassword(ForgotPasswordRequest body) {
        var session = auth.forgotPassword(body.getEmail(), request.getRemoteAddr());
        var response = new OtpSession()
                .otpSessionId(session.otpId())
                .expiresAt(session.expiresAt())
                .channel(OtpSession.ChannelEnum.fromValue(session.channel()));
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /** Đặt mật khẩu mới bằng OTP và thu hồi toàn bộ phiên của người dùng. */
    @Override
    public ResponseEntity<Void> resetPassword(ResetPasswordRequest body) {
        auth.resetPassword(body.getOtpSessionId(), body.getOtp(), body.getNewPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clearSession().toString())
                .build();
    }

    /** Đăng nhập bằng email và mật khẩu, đặt cookie phiên. */
    @Override
    public ResponseEntity<LoginResult> login(LoginRequest body) {
        return loggedIn(auth.login(body.getIdentifier(), body.getPassword(), cookies.loginClient(request)));
    }

    private ResponseEntity<LoginResult> loggedIn(AuthResponse session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .headers(h -> cookies.writeLoginCookies(session, value -> h.add(HttpHeaders.SET_COOKIE, value)))
                .body(new LoginResult(toApiUser(session.user())));
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

    @Override
    public ResponseEntity<Void> logout(LogoutRequest body) {
        var user = currentUser.require();
        boolean allDevices = body != null && Boolean.TRUE.equals(body.getAllDevices());
        auth.logout(user.id(), user.sessionId(), allDevices);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clearSession().toString())
                .build();
    }

    private static User toApiUser(AuthUser account) {
        return new User().id(account.id()).email(account.email()).phone(account.phone())
                .fullName(account.fullName()).avatarUrl(account.avatar())
                .role(User.RoleEnum.fromValue(account.role().name()))
                .emailVerified(!"UNVERIFIED".equals(account.status()));
    }

    /** Dùng chung ProblemDetail cho các đường dẫn Google khi tính năng tắt. */
    private static DomainException googleUnavailable() {
        return new DomainException(ErrorCode.NOT_FOUND, "Đăng nhập Google chưa được bật");
    }
}
