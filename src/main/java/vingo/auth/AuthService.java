package vingo.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Validator;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.AuthRepository;
import vingo.auth.internal.AuthRepository.OtpPurpose;
import vingo.auth.internal.AuthRepository.OtpRecord;
import vingo.auth.internal.AdminAccountRepository;
import vingo.api.model.AdminAccount;
import vingo.api.model.RegisterRequest;
import vingo.auth.internal.AccountSecurityState;
import vingo.auth.internal.AuthResponse;
import vingo.auth.internal.LoginProperties;
import vingo.auth.internal.OtpProperties;
import vingo.auth.internal.PhoneNumbers;
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

/**
 * Đăng ký, đăng nhập, OTP email và quản lý tài khoản.
 *
 * <p>Mỗi lần gửi OTP là một dòng {@code otp_verification} mới; mã hiệu lực là dòng mới nhất của (user, purpose).
 * Trạng thái tài khoản suy ra từ {@code email_verified_at}, {@code deleted_at}, {@code admin_locked}, {@code locked_until}.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom OTP_RANDOM = new SecureRandom();
    private static final String INVALID_CREDENTIALS_MESSAGE = "Email hoặc mật khẩu không đúng";
    private static final String ACCOUNT_LOCKED_MESSAGE = "Tài khoản đang bị khoá tạm do đăng nhập sai nhiều lần";

    private final AuthRepository accounts;
    private final AdminAccountRepository administration;
    private final AccountSecurityState securityState;
    private final JwtProperties jwt;
    private final AuthProperties authProperties;
    private final LoginProperties loginProperties;
    private final OtpProperties otpProperties;
    private final Validator validator;
    private final NotificationsService notifications;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JwtEncoder encoder;

    /** Dùng cùng cấu hình ký JWT mà resource server dùng để xác minh. */
    AuthService(AuthRepository accounts, AdminAccountRepository administration, JwtProperties jwt, AuthProperties authProperties,
                LoginProperties loginProperties, OtpProperties otpProperties, Validator validator,
                NotificationsService notifications, RateLimiter rateLimiter,
                PasswordEncoder passwordEncoder, PlatformTransactionManager txManager, Clock clock,
                AccountSecurityState securityState) {
        this.accounts = accounts;
        this.administration = administration;
        this.securityState = securityState;
        this.jwt = jwt;
        this.authProperties = authProperties;
        this.loginProperties = loginProperties;
        this.otpProperties = otpProperties;
        this.validator = validator;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.secretKey(jwt)).build();
    }

    /**
     * Đăng ký tài khoản BUYER chưa xác minh, ghi OTP email và gửi mã ngoài transaction.
     */
    public OtpSessionInfo register(String fullName, String email, String phone, String password, String clientIp) {
        rateLimiter.check(clientKey(clientIp), "auth-register",
                otpProperties.registerMaxPerWindow(), otpProperties.registerWindow());
        String normalizedName = fullName == null ? "" : fullName.strip();
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String rawPhone = phone == null ? "" : phone.strip();
        String normalizedPhone = normalizeAccountPhone(rawPhone);
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
                OffsetDateTime now = OffsetDateTime.now(clock);
                OffsetDateTime expiresAt = now.plus(otpProperties.ttl());
                AuthUser user = accounts.createUnverifiedUser(
                        normalizedEmail, normalizedPhone, normalizedName, passwordHash, now);
                var otp = accounts.createOtp(user.id(), OtpPurpose.VERIFY, clientKey(clientIp),
                        otpHash, now, expiresAt, true);
                return new RegisteredOtp(user.id(), otp.id(), otp.expiresAt());
            });
        } catch (DataIntegrityViolationException ex) {
            throw mapRegistrationConflict(ex);
        }

        try {
            notifications.sendOtpEmail(UUID.randomUUID(), normalizedEmail, rawOtp, created.expiresAt());
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(status -> accounts.deleteUnverifiedRegistration(created.userId()));
            throw ex;
        }
        log.atInfo()
                .addKeyValue("user_id", created.userId())
                .addKeyValue("otp_id", created.otpId())
                .log("Registered unverified account and sent verification OTP");
        return new OtpSessionInfo(created.otpId(), created.expiresAt(), "EMAIL");
    }

    /**
     * Xác thực OTP email, xác minh tài khoản và cấp cặp token.
     */
    public AuthResponse verifyOtp(UUID otpId, String rawOtp) {
        VerifyOutcome outcome = tx.execute(status -> verifyOtpInTransaction(otpId, rawOtp));
        if (outcome instanceof VerifyOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        var success = (VerifyOutcome.Success) outcome;
        log.atInfo()
                .addKeyValue("user_id", success.response().user().id())
                .addKeyValue("otp_id", otpId)
                .log("Verified OTP and activated account");
        return success.response();
    }

    private VerifyOutcome verifyOtpInTransaction(UUID otpId, String rawOtp) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var found = accounts.findOtpForUpdate(otpId);
        if (found.isEmpty()) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ");
        }
        var otp = found.get().otp();
        var user = accounts.findEditableAccount(otp.userId()).orElse(null);
        if (otp.purpose() != OtpPurpose.VERIFY || otp.consumedAt() != null || user == null
                || !"UNVERIFIED".equals(user.user().status()) || !isLatest(otp)) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ hoặc đã được sử dụng");
        }
        if (!otp.expiresAt().isAfter(now)) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_EXPIRED, "OTP đã hết hạn");
        }
        if (otp.attempts() >= otpProperties.maxAttempts()) {
            return new VerifyOutcome.Failure(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Nhập sai OTP quá số lần cho phép");
        }
        if (!passwordEncoder.matches(rawOtp, otp.codeHash())) {
            accounts.incrementOtpAttempts(otp.id());
            return new VerifyOutcome.Failure(ErrorCode.OTP_INVALID, "OTP không đúng");
        }

        accounts.consumeOtp(otp.id(), now);
        accounts.activateUser(otp.userId(), now);
        AuthUser activeUser = accounts.findEditableAccount(otp.userId()).orElseThrow().user();
        String refreshToken = TokenSecrets.generate();
        OffsetDateTime expiresAt = now.plus(authProperties.refreshTokenTtl());
        UUID sessionId = accounts.createSession(activeUser.id(), TokenSecrets.hash(refreshToken), expiresAt);
        String accessToken = issueAccessToken(activeUser.id(), activeUser.role(), sessionId, now);
        return new VerifyOutcome.Success(new AuthResponse(accessToken, refreshToken, expiresAt,
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), activeUser));
    }

    /**
     * Gửi lại OTP email: ghi một dòng OTP mới (dòng cũ hết hiệu lực) và trả về phiên mới.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OtpSessionInfo resendOtp(UUID otpId) {
        String rawOtp = generateOtp();
        String otpHash = passwordEncoder.encode(rawOtp);

        ResendOutcome outcome = tx.execute(status -> resendOtpInTransaction(otpId, otpHash));
        if (outcome instanceof ResendOutcome.RateLimited(long retryAfter)) {
            throw new RateLimitedException(retryAfter);
        }
        if (outcome instanceof ResendOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        var success = (ResendOutcome.Success) outcome;
        notifications.sendOtpEmail(UUID.randomUUID(), success.email(), rawOtp, success.expiresAt());
        tx.executeWithoutResult(status -> {
            var previous = accounts.findOtpForUpdate(otpId).orElseThrow().otp();
            accounts.findEditableAccount(previous.userId()).orElseThrow();
            if (previous.consumedAt() != null || !isLatest(previous)) {
                throw new DomainException(ErrorCode.OTP_INVALID, "Phiên OTP đã được thay thế hoặc sử dụng");
            }
            accounts.markOtpDelivered(success.otpId());
        });
        log.atInfo()
                .addKeyValue("user_id", success.userId())
                .addKeyValue("otp_id", success.otpId())
                .log("Resent OTP");
        return new OtpSessionInfo(success.otpId(), success.expiresAt(), "EMAIL");
    }

    private ResendOutcome resendOtpInTransaction(UUID otpId, String newOtpHash) {
        var found = accounts.findOtpForUpdate(otpId);
        if (found.isEmpty()) {
            return new ResendOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ");
        }
        var otp = found.get().otp();
        var user = accounts.findEditableAccount(otp.userId()).orElse(null);
        if (user == null || otp.consumedAt() != null || !isLatest(otp)) {
            return new ResendOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ hoặc đã được sử dụng");
        }
        boolean validState = (otp.purpose() == OtpPurpose.VERIFY && "UNVERIFIED".equals(user.user().status()))
                || (otp.purpose() == OtpPurpose.RESET && ("ACTIVE".equals(user.user().status())
                || "LOCKED".equals(user.user().status())));
        if (!validState) {
            return new ResendOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ hoặc đã được sử dụng");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        var sends = accounts.recentOtpSends(otp.userId(), otp.purpose(), now.minus(otpProperties.resendWindow()),
                otpProperties.resendMaxPerWindow());
        if (sends.size() >= otpProperties.resendMaxPerWindow()) {
            requireSendTime(now, sends.getLast().plus(otpProperties.resendWindow()));
        }
        OffsetDateTime nextAllowed = (sends.isEmpty() ? otp.sentAt() : sends.getFirst()).plus(otpProperties.resendCooldown());
        if (nextAllowed.isAfter(now)) {
            long retryAfter = Math.max(1, (Duration.between(now, nextAllowed).toMillis() + 999) / 1000);
            return new ResendOutcome.RateLimited(retryAfter);
        }
        OffsetDateTime newExpiresAt = now.plus(otpProperties.ttl());
        var next = accounts.createOtp(otp.userId(), otp.purpose(), otp.clientKey(), newOtpHash, now, newExpiresAt, false);
        return new ResendOutcome.Success(user.user().id(), user.user().email(), next.id(), newExpiresAt);
    }

    /**
     * Yêu cầu OTP đặt lại mật khẩu. Luôn trả phiên hợp lệ để không lộ email tồn tại hay không.
     */
    public OtpSessionInfo forgotPassword(String email, String clientIp) {
        rateLimiter.check(clientKey(clientIp), "auth-forgot-password",
                otpProperties.registerMaxPerWindow(), otpProperties.registerWindow());
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String rawOtp = generateOtp();
        String otpHash = passwordEncoder.encode(rawOtp);

        var created = tx.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            OffsetDateTime expiresAt = now.plus(otpProperties.ttl());
            var found = accounts.findByEmailForUpdate(normalizedEmail);
            if (found.isEmpty()) {
                return new ForgotPasswordResult(null, null, UUID.randomUUID(), expiresAt, false);
            }
            var user = found.get();
            if (!"ACTIVE".equals(user.status()) && !"LOCKED".equals(user.status())) {
                return new ForgotPasswordResult(null, null, UUID.randomUUID(), expiresAt, false);
            }
            var otp = accounts.createOtp(user.id(), OtpPurpose.RESET, clientKey(clientIp),
                    otpHash, now, expiresAt, true);
            return new ForgotPasswordResult(user.id(), user.email(), otp.id(), otp.expiresAt(), true);
        });

        if (created.shouldSendEmail()) {
            try {
                notifications.sendOtpEmail(UUID.randomUUID(), created.email(), rawOtp, created.expiresAt());
            } catch (RuntimeException ex) {
                tx.executeWithoutResult(status ->
                        accounts.consumeOtp(created.otpId(), OffsetDateTime.now(clock)));
                throw ex;
            }
            log.atInfo()
                    .addKeyValue("user_id", created.userId())
                    .addKeyValue("otp_id", created.otpId())
                    .log("Created password reset OTP");
        }
        return new OtpSessionInfo(created.otpId(), created.expiresAt(), "EMAIL");
    }

    /**
     * Xác thực OTP mục đích RESET, đặt mật khẩu mới (gỡ khoá tạm) và thu hồi toàn bộ phiên.
     */
    public void resetPassword(UUID otpId, String rawOtp, String newPassword) {
        String newPasswordHash = passwordEncoder.encode(newPassword);
        ResetOutcome outcome = tx.execute(status -> resetPasswordInTransaction(otpId, rawOtp, newPasswordHash));
        if (outcome instanceof ResetOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        var success = (ResetOutcome.Success) outcome;
        log.atInfo()
                .addKeyValue("user_id", success.userId())
                .addKeyValue("otp_id", otpId)
                .log("Reset password via OTP and revoked all sessions");
    }

    private ResetOutcome resetPasswordInTransaction(UUID otpId, String rawOtp, String newPasswordHash) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var found = accounts.findOtpForUpdate(otpId);
        if (found.isEmpty()) {
            return new ResetOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ");
        }
        var otp = found.get().otp();
        var user = accounts.findEditableAccount(otp.userId()).orElse(null);
        boolean eligibleUser = user != null && ("ACTIVE".equals(user.user().status()) || "LOCKED".equals(user.user().status()));
        if (otp.purpose() != OtpPurpose.RESET || otp.consumedAt() != null || !eligibleUser || !isLatest(otp)) {
            return new ResetOutcome.Failure(ErrorCode.OTP_INVALID, "Phiên OTP không hợp lệ hoặc đã được sử dụng");
        }
        if (!otp.expiresAt().isAfter(now)) {
            return new ResetOutcome.Failure(ErrorCode.OTP_EXPIRED, "OTP đã hết hạn");
        }
        if (otp.attempts() >= otpProperties.maxAttempts()) {
            return new ResetOutcome.Failure(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Nhập sai OTP quá số lần cho phép");
        }
        if (!passwordEncoder.matches(rawOtp, otp.codeHash())) {
            accounts.incrementOtpAttempts(otp.id());
            return new ResetOutcome.Failure(ErrorCode.OTP_INVALID, "OTP không đúng");
        }

        accounts.consumeOtp(otp.id(), now);
        accounts.updatePasswordAndUnlock(otp.userId(), newPasswordHash, now);
        accounts.revoke(otp.userId(), null, true);
        return new ResetOutcome.Success(otp.userId());
    }

    /** Dòng OTP là mã hiệu lực khi nó là dòng mới nhất của (user, purpose). */
    private boolean isLatest(OtpRecord otp) {
        return accounts.latestOtpId(otp.userId(), otp.purpose())
                .map(latest -> latest.equals(otp.id()))
                .orElse(false);
    }

    private static String normalizeAccountPhone(String rawPhone) {
        return PhoneNumbers.normalize(rawPhone)
                .or(() -> rawPhone.matches("^\\+[1-9][0-9]{8,14}$") ? java.util.Optional.of(rawPhone) : java.util.Optional.empty())
                .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_ERROR, "Số điện thoại không hợp lệ"));
    }

    private static String generateOtp() {
        return String.format(Locale.ROOT, "%06d", OTP_RANDOM.nextInt(1_000_000));
    }

    /** Băm IP client thành UUID ổn định để không lưu IP thô. */
    private static UUID clientKey(String remoteAddress) {
        String address = remoteAddress == null ? "" : remoteAddress.strip();
        return UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8));
    }

    private static RuntimeException mapRegistrationConflict(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        if (message != null && message.contains("user_account_email_key")) {
            return new DomainException(ErrorCode.EMAIL_TAKEN, "Email đã tồn tại");
        }
        return ex;
    }

    /**
     * Xác thực email và mật khẩu, ghi nhận lần sai và cấp cặp token khi hợp lệ.
     */
    public AuthResponse login(String identifier, String password, String device) {
        String email = identifier == null ? "" : identifier.strip();
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)
                || !validator.validateValue(RegisterRequest.class, "email", email).isEmpty()) {
            throw invalidCredentials();
        }

        LoginOutcome outcome = tx.execute(status -> authenticateInTransaction(email, password));
        if (outcome instanceof LoginOutcome.Failure(var code, var message)) {
            throw new DomainException(code, message);
        }
        return ((LoginOutcome.Success) outcome).response();
    }

    private LoginOutcome authenticateInTransaction(String email, String rawPassword) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var found = accounts.findForPasswordLogin(email);
        if (found.isEmpty()) {
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        var account = found.get();
        var user = account.user();
        if (securityState.locked(user.id())) {
            return new LoginOutcome.Failure(ErrorCode.ACCOUNT_LOCKED, "Tài khoản đã bị quản trị viên khóa");
        }
        if ("LOCKED".equals(user.status())) {
            return new LoginOutcome.Failure(ErrorCode.ACCOUNT_LOCKED, ACCOUNT_LOCKED_MESSAGE);
        }
        if (!"ACTIVE".equals(user.status()) || account.passwordHash() == null) {
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            int failedAttempts = accounts.recordFailedLogin(user.id(), now, now.minus(loginProperties.attemptWindow()));
            if (failedAttempts >= loginProperties.maxAttempts()) {
                accounts.lockTemporarily(user.id(), now.plus(loginProperties.lockDuration()), now);
            }
            return new LoginOutcome.Failure(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        accounts.clearLoginFailures(user.id(), now);
        String refreshToken = TokenSecrets.generate();
        OffsetDateTime expiresAt = now.plus(authProperties.refreshTokenTtl());
        UUID sessionId = accounts.createSession(user.id(), TokenSecrets.hash(refreshToken), expiresAt);
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
                Math.toIntExact(jwt.accessTokenTtl().toSeconds()), session.account());
    }

    /** Đăng xuất: xoá phiên hiện tại, hoặc mọi phiên của user. */
    @Transactional
    public void logout(UUID userId, UUID sessionId, boolean allDevices) {
        accounts.revoke(userId, sessionId, allDevices);
    }

    @Transactional
    public AuthUser getMe(UUID userId) {
        var account = accounts.findEditableAccount(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy tài khoản"));
        if (!"ACTIVE".equals(account.user().status()) && !"LOCKED".equals(account.user().status())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Tài khoản không hoạt động");
        }
        return account.user();
    }

    @Transactional
    public AuthUser updateMe(UUID userId, String fullName, String phone, String currentPassword, String newPassword) {
        var account = accounts.findEditableAccount(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy tài khoản"));
        if (!"ACTIVE".equals(account.user().status())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Tài khoản không hoạt động");
        }
        boolean changePassword = StringUtils.hasText(newPassword);
        if ((StringUtils.hasText(currentPassword) && !changePassword)
                || (changePassword && newPassword.length() < 8)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Đổi mật khẩu cần mật khẩu mới tối thiểu 8 ký tự");
        }
        if (changePassword && (!StringUtils.hasText(currentPassword) || account.passwordHash() == null
                || !passwordEncoder.matches(currentPassword, account.passwordHash()))) {
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS, "Mật khẩu hiện tại không đúng");
        }
        String normalizedName = fullName == null ? account.user().fullName() : fullName.strip();
        String normalizedPhone = phone == null ? account.user().phone() : phone.strip();
        if (!StringUtils.hasText(normalizedName) || normalizedName.codePointCount(0, normalizedName.length()) > 120
                || (normalizedPhone != null && !normalizedPhone.isBlank() && normalizedPhone.length() > 32)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Tên hoặc số điện thoại không hợp lệ");
        }
        String nextPhone = phone == null ? account.user().phone()
                : StringUtils.hasText(normalizedPhone) ? normalizeAccountPhone(normalizedPhone) : null;
        String hash = changePassword ? passwordEncoder.encode(newPassword) : null;
        accounts.updateAccount(userId, normalizedName, nextPhone, hash, OffsetDateTime.now(clock));
        return accounts.findEditableAccount(userId).orElseThrow().user();
    }

    @Transactional
    public void deleteMe(UUID userId) {
        administration.lockAdministration();
        var account = administration.find(userId, true).orElseThrow(() ->
                new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy tài khoản."));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (account.getRole() == AdminAccount.RoleEnum.ADMIN && account.getDeletedAt() == null
                && account.getEmailVerifiedAt() != null
                && !Boolean.TRUE.equals(account.getAdminLocked())
                && (account.getLockedUntil() == null || !account.getLockedUntil().isAfter(now))
                && administration.activeAdmins(now) <= 1) {
            throw new DomainException(ErrorCode.ACTION_STATE_INVALID, "Không thể xóa ADMIN hoạt động cuối cùng.");
        }
        accounts.revoke(userId, null, true);
        accounts.deleteAccount(userId, now);
    }

    private static void requireSendTime(OffsetDateTime now, OffsetDateTime nextAllowed) {
        if (nextAllowed.isAfter(now)) {
            Duration remaining = Duration.between(now, nextAllowed);
            throw new RateLimitedException(remaining.toSeconds() + (remaining.getNano() == 0 ? 0 : 1));
        }
    }

    private String issueAccessToken(UUID userId, Role role, UUID sessionId, OffsetDateTime now) {
        var claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(userId.toString())
                .claim("av", securityState.version(userId))
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
        record Success(UUID userId, String email, UUID otpId, OffsetDateTime expiresAt) implements ResendOutcome {
        }

        record RateLimited(long retryAfterSeconds) implements ResendOutcome {
        }

        record Failure(ErrorCode code, String message) implements ResendOutcome {
        }
    }

    private record RegisteredOtp(UUID userId, UUID otpId, OffsetDateTime expiresAt) {
    }

    private record ForgotPasswordResult(
            UUID userId,
            String email,
            UUID otpId,
            OffsetDateTime expiresAt,
            boolean shouldSendEmail) {
    }

    private sealed interface ResetOutcome {
        record Success(UUID userId) implements ResetOutcome {
        }

        record Failure(ErrorCode code, String message) implements ResetOutcome {
        }
    }

    public record OtpSessionInfo(UUID otpId, OffsetDateTime expiresAt, String channel) {
    }
}
