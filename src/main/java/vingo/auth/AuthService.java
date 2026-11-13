package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.AuthRepository;
import vingo.auth.internal.AuthResponse;
import vingo.auth.internal.LoginProperties;
import vingo.auth.internal.TokenSecrets;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.JwtProperties;
import vingo.shared.security.Role;
import vingo.shared.security.SecurityConfig;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Email, số điện thoại hoặc mật khẩu không đúng";
    private static final String ACCOUNT_LOCKED_MESSAGE = "Tài khoản đang bị khoá tạm do đăng nhập sai nhiều lần";

    private final AuthRepository accounts;
    private final JwtProperties jwt;
    private final AuthProperties authProperties;
    private final LoginProperties loginProperties;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JwtEncoder encoder;

    /** Dùng cùng cấu hình ký JWT mà resource server dùng để xác minh. */
    AuthService(AuthRepository accounts, JwtProperties jwt, AuthProperties authProperties,
                LoginProperties loginProperties, PasswordEncoder passwordEncoder,
                PlatformTransactionManager txManager, Clock clock) {
        this.accounts = accounts;
        this.jwt = jwt;
        this.authProperties = authProperties;
        this.loginProperties = loginProperties;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(jwt)).build();
    }

    /**
     * Xác thực email/SĐT và mật khẩu, ghi nhận lịch sử thử và cấp cặp token khi hợp lệ.
     */
    public AuthResponse login(String identifier, String password, String device) {
        String normalizedIdentifier = identifier == null ? "" : identifier.strip();
        if (!StringUtils.hasText(normalizedIdentifier) || !StringUtils.hasText(password)) {
            throw invalidCredentials();
        }
        String normalizedDevice = StringUtils.hasText(device) ? device.strip() : null;

        LoginOutcome outcome = tx.execute(status -> authenticateInTransaction(normalizedIdentifier, password, normalizedDevice));
        if (outcome instanceof LoginOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        return ((LoginOutcome.Success) outcome).response();
    }

    private LoginOutcome authenticateInTransaction(String identifier, String rawPassword, String device) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var found = accounts.findForPasswordLogin(identifier);
        if (found.isEmpty()) {
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        var account = found.get();
        var user = account.user();
        boolean expiredTempLock = false;
        if ("LOCKED".equals(user.status())) {
            if (account.lockedUntil() == null || account.lockedUntil().isAfter(now)) {
                return new LoginOutcome.Failure(ErrorCode.ACCOUNT_LOCKED, ACCOUNT_LOCKED_MESSAGE);
            }
            expiredTempLock = true;
        } else if (!"ACTIVE".equals(user.status())) {
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        if (account.passwordHash() == null) {
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            accounts.recordLoginAttempt(user.id(), false, now);
            int failedAttempts = accounts.countRecentFailedAttempts(
                    user.id(), now.minus(loginProperties.attemptWindow()), account.lockedUntil());
            if (failedAttempts >= loginProperties.maxAttempts()) {
                accounts.lockTemporarily(user.id(), now.plus(loginProperties.lockDuration()), now);
            }
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        if (expiredTempLock) {
            accounts.unlockAfterExpiry(user.id(), now);
            user = new AuthUser(user.id(), user.email(), user.phone(), user.fullName(), user.avatar(),
                    user.role(), "ACTIVE");
        }
        accounts.recordLoginAttempt(user.id(), true, now);
        String refreshToken = TokenSecrets.generate();
        OffsetDateTime expiresAt = now.plus(authProperties.refreshTokenTtl());
        UUID sessionId = accounts.createSession(user.id(), TokenSecrets.hash(refreshToken), device, expiresAt);
        String accessToken = issueAccessToken(user.id(), user.role(), sessionId, now);
        return new LoginOutcome.Success(new AuthResponse(accessToken, refreshToken, expiresAt,
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), user));
    }

    /** Xoay vòng refresh token và ký access JWT trong một transaction. */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken) || refreshToken.length() > 512) {
            throw invalidRefresh();
        }
        String nextToken = TokenSecrets.generate();
        OffsetDateTime now = OffsetDateTime.now(clock);
        var session = accounts.rotate(TokenSecrets.hash(refreshToken), TokenSecrets.hash(nextToken), now)
                .orElseThrow(AuthService::invalidRefresh);
        String accessToken = issueAccessToken(session.account().id(), session.account().role(), session.id(), now);
        return new AuthResponse(accessToken, nextToken, session.expiresAt(),
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), session.account(), profileCompleted);
    }

    /** Thu hồi phiên thuộc user hiện tại hoặc mọi phiên của user đó. */
    @Transactional
    public void logout(UUID userId, UUID sessionId, boolean allDevices) {
        accounts.revoke(userId, sessionId, allDevices, OffsetDateTime.now(clock));
    }

    private String issueAccessToken(UUID userId, Role role, UUID sessionId, OffsetDateTime now) {
        var claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(userId.toString())
                .claim(CurrentUser.ROLE_CLAIM, role.name())
                .claim(CurrentUser.SESSION_CLAIM, sessionId.toString())
                .issuedAt(now.toInstant())
                .expiresAt(now.plus(jwt.accessTokenTtl()).toInstant())
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** Dùng chung phản hồi cho thông tin đăng nhập không hợp lệ hoặc tài khoản chưa kích hoạt. */
    private static DomainException invalidCredentials() {
        return new DomainException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }

    /** Dùng chung phản hồi cho refresh token sai, hết hạn hoặc đã thu hồi. */
    private static DomainException invalidRefresh() {
        return new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token không hợp lệ hoặc đã hết hạn");
    }

    private sealed interface LoginOutcome {
        record Success(AuthResponse response) implements LoginOutcome {
        }

        record Failure(ErrorCode code, String message) implements LoginOutcome {
        }
    }
}
