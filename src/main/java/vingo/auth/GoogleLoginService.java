package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.AuthRepository;
import vingo.auth.internal.GoogleLoginRequest;
import vingo.auth.internal.TokenSecrets;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.Role;

@Service
public class GoogleLoginService {

    private final AuthRepository accounts;
    private final AuthProperties properties;
    private final Clock clock;

    /** Nhận kho tài khoản và cấu hình thời hạn phiên Vingo. */
    GoogleLoginService(AuthRepository accounts, AuthProperties properties, Clock clock) {
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    /** Tìm hoặc tạo user theo Google subject rồi lưu phiên trong cùng transaction. */
    @Transactional
    public GoogleLoginResult login(GoogleLoginRequest identity) {
        if (!StringUtils.hasText(identity.subject()) || !identity.emailVerified()
                || !StringUtils.hasText(identity.email())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Google chưa xác minh địa chỉ email");
        }

        AuthUser account = accounts.findByGoogleSubject(identity.subject()).orElse(null);
        boolean created = false;
        if (account == null) {
            String email = identity.email().strip().toLowerCase(Locale.ROOT);
            String name = StringUtils.hasText(identity.fullName()) ? identity.fullName().strip() : email;
            var inserted = accounts.createGoogleAccount(identity.subject(), email, name, identity.avatar());
            created = inserted.isPresent();
            account = inserted.orElseGet(() -> accounts.findByGoogleSubject(identity.subject())
                    .orElseThrow(() -> new DomainException(ErrorCode.EMAIL_TAKEN, "Email đã có tài khoản Vingo")));
        }
        if (account.role() != Role.BUYER || !account.status().equals("ACTIVE")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Tài khoản không thể đăng nhập bằng Google");
        }

        String refreshToken = TokenSecrets.generate();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.refreshTokenTtl());
        accounts.createSession(account.id(), TokenSecrets.hash(refreshToken), expiresAt);
        return new GoogleLoginResult(refreshToken, expiresAt, created);
    }

    public record GoogleLoginResult(String refreshToken, OffsetDateTime expiresAt, boolean created) {
    }
}
