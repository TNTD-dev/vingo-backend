package vingo.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
import vingo.auth.internal.OtpProperties;
import vingo.auth.internal.TokenSecrets;
import vingo.notifications.NotificationsService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.ratelimit.RateLimitedException;
import vingo.shared.ratelimit.RateLimiter;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.JwtProperties;
import vingo.shared.security.Role;
import vingo.shared.security.SecurityConfig;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom OTP_RANDOM = new SecureRandom();
    private static final String INVALID_CREDENTIALS_MESSAGE = "Email, số điện thoại hoặc mật khẩu không đúng";
    private static final String ACCOUNT_LOCKED_MESSAGE = "Tài khoản đang bị khoá tạm do đăng nhập sai nhiều lần";

    private final AuthRepository accounts;
    private final JwtProperties jwt;
    private final AuthProperties authProperties;
    private final LoginProperties loginProperties;
    private final OtpProperties otpProperties;
    private final NotificationsService notifications;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JwtEncoder encoder;

    /** Dùng cùng cấu hình ký JWT mà resource server dùng để xác minh. */
    AuthService(AuthRepository accounts, JwtProperties jwt, AuthProperties authProperties,
                LoginProperties loginProperties, OtpProperties otpProperties,
                NotificationsService notifications, RateLimiter rateLimiter,
                PasswordEncoder passwordEncoder, PlatformTransactionManager txManager, Clock clock) {
        this.accounts = accounts;
        this.jwt = jwt;
        this.authProperties = authProperties;
        this.loginProperties = loginProperties;
        this.otpProperties = otpProperties;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(jwt)).build();
    }

    /**
     * Đăng ký tài khoản BUYER ở trạng thái UNVERIFIED, tạo phiên OTP và gửi mã qua email ngoài transaction.
     */
    public OtpSessionInfo register(String fullName, String email, String phone, String password) {
        String normalizedName = fullName == null ? "" : fullName.strip();
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String normalizedPhone = phone == null ? "" : phone.strip();
        if (!StringUtils.hasText(normalizedName)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Họ tên không được để trống");
        }

        String passwordHash = passwordEncoder.encode(password);
        String rawOtp = generateOtp();
        String otpHash = passwordEncoder.encode(rawOtp);

        RegisteredOtp created;
        try {
            created = tx.execute(status -> {
                if (accounts.existsByEmail(normalizedEmail)) {
                    throw new DomainException(ErrorCode.EMAIL_TAKEN, "Email đã tồn tại");
                }
                if (accounts.existsByPhone(normalizedPhone)) {
                    throw new DomainException(ErrorCode.PHONE_TAKEN, "Số điện thoại đã tồn tại");
                }
                OffsetDateTime now = OffsetDateTime.now(clock);
                OffsetDateTime expiresAt = now.plus(otpProperties.ttl());
                AuthUser user = accounts.createUnverifiedUser(
                        normalizedEmail, normalizedPhone, normalizedName, passwordHash, now);
                var session = accounts.createOtpSession(user.id(), "VERIFY", "EMAIL", otpHash, now, expiresAt);
                return new RegisteredOtp(user.id(), session.id(), session.expiresAt());
            });
        } catch (DataIntegrityViolationException ex) {
            throw mapRegistrationConflict(ex);
        }

        notifications.sendOtpEmail(UUID.randomUUID(), normalizedEmail, rawOtp, created.expiresAt());
        log.atInfo()
                .addKeyValue("user_id", created.userId())
                .addKeyValue("otp_session_id", created.otpSessionId())
                .log("Registered unverified account and sent verification OTP");
        return new OtpSessionInfo(created.otpSessionId(), created.expiresAt(), "EMAIL");
    }

    /**
     * Xác thực OTP, kích hoạt tài khoản UNVERIFIED -> ACTIVE và cấp cặp token.
     */
    public AuthResponse verifyOtp(UUID otpSessionId, String rawOtp) {
        VerifyOutcome outcome = tx.execute(status -> verifyOtpInTransaction(otpSessionId, rawOtp));
        if (outcome instanceof VerifyOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        var success = (VerifyOutcome.Success) outcome;
        log.atInfo()
                .addKeyValue("user_id", success.response().user().id())
                .addKeyValue("otp_session_id", otpSessionId)
                .log("Verified OTP and activated account");
        return success.response();
    }

    private VerifyOutcome verifyOtpInTransaction(UUID otpSessionId, String rawOtp) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var found = accounts.findOtpSessionForUpdate(otpSessionId);
        if (found.isEmpty()) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ");
        }
        var session = found.get().session();
        var user = found.get().user();
        if (!"VERIFY".equals(session.purpose()) || session.consumedAt() != null || !"UNVERIFIED".equals(user.status())) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ hoặc đã được sử dụng");
        }
        if (!session.expiresAt().isAfter(now)) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_EXPIRED, "OTP đã hết hạn");
        }
        if (session.attempts() >= otpProperties.maxAttempts()) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Nhập sai OTP quá số lần cho phép");
        }
        if (!passwordEncoder.matches(rawOtp, session.otpHash())) {
            accounts.incrementOtpAttempts(session.id());
            return new VerifyOutcome.Failure(ErrorCode.OTP_INVALID, "OTP không đúng");
        }

        accounts.consumeOtpSession(session.id(), now);
        accounts.activateUser(user.id(), now);
        AuthUser activeUser = new AuthUser(user.id(), user.email(), user.phone(), user.fullName(), user.avatar(),
                user.role(), "ACTIVE");
        String refreshToken = TokenSecrets.generate();
        OffsetDateTime expiresAt = now.plus(authProperties.refreshTokenTtl());
        UUID sessionId = accounts.createSession(activeUser.id(), TokenSecrets.hash(refreshToken), null, expiresAt);
        String accessToken = issueAccessToken(activeUser.id(), activeUser.role(), sessionId, now);
        boolean profileCompleted = accounts.hasBuyerProfile(activeUser.id());
        return new VerifyOutcome.Success(new AuthResponse(accessToken, refreshToken, expiresAt,
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), activeUser, profileCompleted));
    }

    /**
     * Vô hiệu hoá mã OTP cũ, tạo mã mới và gửi lại qua email ngoài transaction.
     */
    public void resendOtp(UUID otpSessionId) {
        rateLimiter.check(otpSessionId, "otp-resend",
                otpProperties.resendMaxPerWindow(), otpProperties.resendWindow());
        String rawOtp = generateOtp();
        String otpHash = passwordEncoder.encode(rawOtp);

        ResendOutcome outcome = tx.execute(status -> resendOtpInTransaction(otpSessionId, otpHash));
        if (outcome instanceof ResendOutcome.RateLimited(long retryAfter)) {
            throw new RateLimitedException(retryAfter);
        }
        if (outcome instanceof ResendOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        var success = (ResendOutcome.Success) outcome;
        notifications.sendOtpEmail(UUID.randomUUID(), success.email(), rawOtp, success.expiresAt());
        log.atInfo()
                .addKeyValue("user_id", success.userId())
                .addKeyValue("otp_session_id", otpSessionId)
                .log("Resent verification OTP");
    }

    private ResendOutcome resendOtpInTransaction(UUID otpSessionId, String newOtpHash) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var found = accounts.findOtpSessionForUpdate(otpSessionId);
        if (found.isEmpty()) {
            return new ResendOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ");
        }
        var session = found.get().session();
        var user = found.get().user();
        if (!"VERIFY".equals(session.purpose()) || session.consumedAt() != null || !"UNVERIFIED".equals(user.status())) {
            return new ResendOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ hoặc đã được sử dụng");
        }
        OffsetDateTime nextAllowed = session.lastSentAt().plus(otpProperties.resendCooldown());
        if (nextAllowed.isAfter(now)) {
            long retryAfter = Math.max(1, (Duration.between(now, nextAllowed).toMillis() + 999) / 1000);
            return new ResendOutcome.RateLimited(retryAfter);
        }
        OffsetDateTime newExpiresAt = now.plus(otpProperties.ttl());
        accounts.refreshOtpSession(session.id(), newOtpHash, now, newExpiresAt);
        return new ResendOutcome.Success(user.id(), user.email(), newExpiresAt);
    }

    private static String generateOtp() {
        return String.format(Locale.ROOT, "%06d", OTP_RANDOM.nextInt(1_000_000));
    }

    private static RuntimeException mapRegistrationConflict(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        if (message != null) {
            if (message.contains("users_email_uk")) {
                return new DomainException(ErrorCode.EMAIL_TAKEN, "Email đã tồn tại");
            }
            if (message.contains("users_phone_uk")) {
                return new DomainException(ErrorCode.PHONE_TAKEN, "Số điện thoại đã tồn tại");
            }
        }
        return ex;
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
        boolean profileCompleted = accounts.hasBuyerProfile(user.id());
        return new LoginOutcome.Success(new AuthResponse(accessToken, refreshToken, expiresAt,
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), user, profileCompleted));
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
        boolean profileCompleted = accounts.hasBuyerProfile(session.account().id());
        return new AuthResponse(accessToken, nextToken, session.expiresAt(),
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), session.account(), profileCompleted);
    }

    /** Thu hồi phiên thuộc user hiện tại hoặc mọi phiên của user đó. */
    @Transactional
    public void logout(UUID userId, UUID sessionId, boolean allDevices) {
        accounts.revoke(userId, sessionId, allDevices, OffsetDateTime.now(clock));
    }

    /** Kiểm tra người mua đã có hồ sơ trước khi tạo thanh toán. */
    public boolean hasBuyerProfile(UUID userId) {
        return accounts.hasBuyerProfile(userId);
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

    private sealed interface VerifyOutcome {
        record Success(AuthResponse response) implements VerifyOutcome {
        }

        record Failure(ErrorCode code, String message) implements VerifyOutcome {
        }
    }

    private sealed interface ResendOutcome {
        record Success(UUID userId, String email, OffsetDateTime expiresAt) implements ResendOutcome {
        }

        record RateLimited(long retryAfterSeconds) implements ResendOutcome {
        }

        record Failure(ErrorCode code, String message) implements ResendOutcome {
        }
    }

    private record RegisteredOtp(UUID userId, UUID otpSessionId, OffsetDateTime expiresAt) {
    }

    public record OtpSessionInfo(UUID otpSessionId, OffsetDateTime expiresAt, String channel) {
    }
}
