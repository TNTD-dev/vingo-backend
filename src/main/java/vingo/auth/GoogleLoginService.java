package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import vingo.auth.internal.AuthResponse;
import vingo.auth.internal.AuthRepository;
import vingo.auth.internal.GoogleLoginRequest;
import vingo.auth.internal.LoginClient;
import vingo.auth.internal.LoginSessions;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.Role;

@Service
public class GoogleLoginService {

    private final AuthRepository accounts;
    private final LoginSessions sessions;
    private final Clock clock;

    /** Nhận kho tài khoản và cấu hình thời hạn phiên Vingo. */
    GoogleLoginService(AuthRepository accounts, LoginSessions sessions, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Tìm hoặc tạo user theo Google subject rồi lưu phiên trong cùng transaction. */
    @Transactional
    public GoogleLoginResult login(GoogleLoginRequest identity, LoginClient client) {
        if (!StringUtils.hasText(identity.subject()) || !identity.emailVerified()
                || !StringUtils.hasText(identity.email())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Google chưa xác minh địa chỉ email");
        }

        AuthUser account = accounts.findByGoogleSubject(identity.subject()).orElse(null);
        boolean created = false;
        if (account == null) {
            String email = identity.email().strip().toLowerCase(Locale.ROOT);
            String name = StringUtils.hasText(identity.fullName()) ? identity.fullName().strip() : email;
            if (name.codePointCount(0, name.length()) > 120) {
                name = name.substring(0, name.offsetByCodePoints(0, 120)).stripTrailing();
            }
            var inserted = accounts.createGoogleAccount(identity.subject(), email, name, identity.avatar(),
                    OffsetDateTime.now(clock));
            created = inserted.isPresent();
            account = inserted.orElseGet(() -> accounts.findByGoogleSubject(identity.subject())
                    .orElseThrow(() -> new DomainException(ErrorCode.EMAIL_TAKEN, "Email đã có tài khoản Vingo")));
        }
        if (account.role() != Role.BUYER || !account.status().equals("ACTIVE")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Tài khoản không thể đăng nhập bằng Google");
        }

        return new GoogleLoginResult(sessions.open(account, OffsetDateTime.now(clock), client), created);
    }

    public record GoogleLoginResult(AuthResponse session, boolean created) {
    }
}
