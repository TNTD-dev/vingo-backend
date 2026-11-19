package vingo.auth.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.MeApi;
import vingo.api.model.BuyerProfile;
import vingo.api.model.OtpSession;
import vingo.api.model.PhoneOtpRequest;
import vingo.api.model.PhoneVerifyRequest;
import vingo.auth.BuyerProfileService;
import vingo.api.model.UpdateMeRequest;
import vingo.api.model.User;
import vingo.auth.AuthService;
import vingo.auth.AuthUser;
import vingo.auth.internal.sms.DemoSmsSender;
import vingo.shared.security.CurrentUser;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class MeController implements MeApi {

    private final AuthService auth;
    private final CurrentUser currentUser;
    private final AuthCookies cookies;
    private final BuyerProfileService profiles;
    private final HttpServletRequest request;

    MeController(AuthService auth, CurrentUser currentUser, AuthCookies cookies, BuyerProfileService profiles,
                 HttpServletRequest request) {
        this.auth = auth;
        this.currentUser = currentUser;
        this.cookies = cookies;
        this.profiles = profiles;
        this.request = request;
    }

    /** Gửi SMS OTP cho user hiện tại, dùng IP từ servlet như AuthController. */
    @Override
    public ResponseEntity<OtpSession> requestPhoneOtp(PhoneOtpRequest body) {
        var session = auth.requestPhoneOtp(currentUser.require().id(), body.getPhone(), request.getRemoteAddr());
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        // Chỉ có khi chạy SMS_PROVIDER=demo: trả mã để web demo hiện lên màn hình.
        DemoSmsSender.takeCode().ifPresent(code -> response.header(DemoSmsSender.HEADER, code));
        return response.body(new OtpSession()
                .otpSessionId(session.otpSessionId()).expiresAt(session.expiresAt())
                .channel(OtpSession.ChannelEnum.fromValue(session.channel())));
    }

    /** Xác minh SMS OTP của đúng user đang đăng nhập. */
    @Override
    public ResponseEntity<User> verifyPhoneOtp(PhoneVerifyRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toUser(
                auth.verifyPhone(currentUser.require().id(), body.getOtpSessionId(), body.getOtp())));
    }

    @Override
    public ResponseEntity<BuyerProfile> getBuyerProfile() {
        return ResponseEntity.ok(profiles.get());
    }

    @Override
    public ResponseEntity<BuyerProfile> putBuyerProfile(BuyerProfile body) {
        return ResponseEntity.ok(profiles.put(body));
    }

    @Override
    public ResponseEntity<User> getMe() {
        return ResponseEntity.ok(toUser(auth.getMe(currentUser.require().id())));
    }

    @Override
    public ResponseEntity<User> updateMe(UpdateMeRequest body) {
        AuthUser updated = auth.updateMe(currentUser.require().id(), body.getFullName(), body.getPhone(),
                body.getCurrentPassword(), body.getNewPassword());
        return ResponseEntity.ok(toUser(updated));
    }

    @Override
    public ResponseEntity<Void> deleteMe() {
        auth.deleteMe(currentUser.require().id());
        return ResponseEntity.noContent()
                .header("Set-Cookie", cookies.cookie(AuthCookies.REFRESH, "", java.time.Duration.ZERO).toString())
                .build();
    }

    private static User toUser(AuthUser account) {
        return new User().id(account.id()).email(account.email()).phone(account.phone())
                .phoneVerified(account.phoneVerified())
                .fullName(account.fullName()).avatarUrl(account.avatar())
                .role(User.RoleEnum.fromValue(account.role().name()))
                .status(User.StatusEnum.fromValue(account.status()));
    }
}
