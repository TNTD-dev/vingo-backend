package vingo.auth.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.auth.AuthUser;
import vingo.shared.security.Role;

/**
 * Truy vấn tài khoản, phiên đăng nhập và OTP trên schema v2.
 *
 * <p>Trạng thái tài khoản không lưu: {@code deleted_at}, {@code email_verified_at}, {@code admin_locked}
 * và {@code locked_until} quyết định {@link AuthUser#status()} khi đọc.
 */
@Repository
public class AuthRepository {

    private static final String ACCOUNT_COLUMNS = """
            id, email, phone, full_name, avatar_url, role, deleted_at, email_verified_at,
            admin_locked, locked_until, phone_verified_at IS NOT NULL AS phone_verified
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    AuthRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Tìm và khoá tài khoản Google trong transaction đăng nhập. */
    public Optional<AuthUser> findByGoogleSubject(String subject) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM user_account WHERE google_sub = :subject FOR UPDATE")
                .param("subject", subject)
                .query(this::account).optional();
    }

    /** Tạo tài khoản BUYER qua Google (email đã được Google xác minh). Trả rỗng nếu email hoặc subject đã có. */
    public Optional<AuthUser> createGoogleAccount(String subject, String email, String name, URI avatar,
                                                  OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO user_account (google_sub, email, full_name, avatar_url, role, email_verified_at,
                                                  created_at, updated_at)
                        VALUES (:subject, :email, :name, :avatar, 'BUYER', :now, :now, :now)
                        ON CONFLICT DO NOTHING
                        RETURNING
                        """ + ACCOUNT_COLUMNS)
                .param("subject", subject)
                .param("email", email)
                .param("name", name)
                .param("avatar", avatar == null ? null : avatar.toString())
                .param("now", now)
                .query(this::account).optional();
    }

    /** Tìm và khoá tài khoản theo email (không phân biệt hoa/thường) hoặc SĐT đã xác minh. */
    public Optional<PasswordAccount> findForPasswordLogin(String identifier) {
        String predicate = "lower(email) = lower(:identifier)";
        if (!identifier.contains("@")) {
            var phone = PhoneNumbers.normalize(identifier);
            if (phone.isEmpty()) return Optional.empty();
            identifier = phone.get();
            predicate = "phone_verified_at IS NOT NULL AND phone = :identifier";
        }
        return jdbc.sql("""
                        SELECT %s, password_hash
                        FROM user_account
                        WHERE %s
                        FOR UPDATE
                        """.formatted(ACCOUNT_COLUMNS, predicate))
                .param("identifier", identifier)
                .query((rs, row) -> new PasswordAccount(account(rs, row), rs.getString("password_hash")))
                .optional();
    }

    /**
     * Ghi một lần đăng nhập sai. Cửa sổ đếm tính từ lần sai gần nhất: quá cửa sổ thì đếm lại từ 1.
     *
     * @return số lần sai liên tiếp sau khi ghi
     */
    public int recordFailedLogin(UUID userId, OffsetDateTime now, OffsetDateTime windowStart) {
        return jdbc.sql("""
                        UPDATE user_account
                        SET failed_login_count = CASE WHEN last_failed_login_at IS NULL OR last_failed_login_at < :windowStart
                                                      THEN 1 ELSE failed_login_count + 1 END,
                            last_failed_login_at = :now
                        WHERE id = :user
                        RETURNING failed_login_count
                        """)
                .param("user", userId).param("now", now).param("windowStart", windowStart)
                .query(Integer.class).single();
    }

    /** Khoá tạm tài khoản đến thời điểm chỉ định và xoá bộ đếm sai để lần mở khoá đếm lại từ đầu. */
    public void lockTemporarily(UUID userId, OffsetDateTime lockedUntil, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account
                        SET locked_until = :lockedUntil, failed_login_count = 0, last_failed_login_at = NULL, updated_at = :now
                        WHERE id = :user
                        """)
                .param("user", userId)
                .param("lockedUntil", lockedUntil)
                .param("now", now)
                .update();
    }

    /** Đăng nhập đúng: xoá bộ đếm sai và khoá tạm đã hết hạn. */
    public void clearLoginFailures(UUID userId, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account
                        SET failed_login_count = 0, last_failed_login_at = NULL, locked_until = NULL, updated_at = :now
                        WHERE id = :user AND (failed_login_count <> 0 OR locked_until IS NOT NULL OR last_failed_login_at IS NOT NULL)
                        """)
                .param("user", userId).param("now", now).update();
    }

    /** Lưu hash refresh token của phiên mới và trả về id phiên. */
    public UUID createSession(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        return jdbc.sql("""
                        INSERT INTO login_session (user_id, token_hash, expires_at)
                        VALUES (:user, :hash, :expires)
                        RETURNING id
                        """)
                .param("user", userId)
                .param("hash", tokenHash)
                .param("expires", expiresAt)
                .query(UUID.class)
                .single();
    }

    /** Xoay refresh token tại chỗ nếu phiên và tài khoản còn hiệu lực. */
    public Optional<RotatedSession> rotate(String oldHash, String newHash, OffsetDateTime now) {
        return jdbc.sql("""
                        UPDATE login_session s SET token_hash = :newHash
                        FROM user_account u
                        WHERE s.token_hash = :oldHash AND s.expires_at > :now
                          AND s.user_id = u.id
                          AND u.deleted_at IS NULL AND u.email_verified_at IS NOT NULL AND NOT u.admin_locked
                          AND (u.locked_until IS NULL OR u.locked_until <= :now)
                        RETURNING s.id AS session_id, s.expires_at, u.id, u.email, u.phone, u.full_name,
                                  u.avatar_url, u.role, u.deleted_at, u.email_verified_at, u.admin_locked,
                                  u.locked_until, u.phone_verified_at IS NOT NULL AS phone_verified
                        """)
                .param("newHash", newHash)
                .param("oldHash", oldHash)
                .param("now", now)
                .query((rs, row) -> new RotatedSession(rs.getObject("session_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class), account(rs, row)))
                .optional();
    }

    /** Đăng xuất = xoá phiên; allDevices xoá mọi phiên của đúng user. */
    public void revoke(UUID userId, UUID sessionId, boolean allDevices) {
        jdbc.sql("""
                        DELETE FROM login_session
                        WHERE user_id = :user AND (:allDevices OR id = CAST(:session AS uuid))
                        """)
                .param("user", userId)
                .param("session", sessionId)
                .param("allDevices", allDevices)
                .update();
    }

    public Optional<EditableAccount> findEditableAccount(UUID userId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + ", password_hash FROM user_account WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query((rs, row) -> new EditableAccount(account(rs, row), rs.getString("password_hash")))
                .optional();
    }

    public void updateAccount(UUID userId, String fullName, String phone, String passwordHash,
                              boolean phoneChanged, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account SET full_name = :name, phone = :phone,
                            phone_verified_at = CASE WHEN :phoneChanged THEN NULL ELSE phone_verified_at END,
                            password_hash = COALESCE(:passwordHash, password_hash), updated_at = :now
                        WHERE id = :id
                        """)
                .param("name", fullName).param("phone", phone).param("passwordHash", passwordHash)
                .param("phoneChanged", phoneChanged)
                .param("now", now).param("id", userId).update();
    }

    /** Ẩn danh hoá tài khoản: giữ hàng để khoá ngoại của OTP và đơn vẫn đúng, xoá mọi dữ liệu cá nhân. */
    public void deleteAccount(UUID userId, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account SET email = 'deleted+' || id || '@invalid.local', phone = NULL,
                            phone_verified_at = NULL, full_name = 'Deleted user', password_hash = NULL,
                            google_sub = NULL, avatar_url = NULL, locked_until = NULL,
                            failed_login_count = 0, last_failed_login_at = NULL,
                            deleted_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", now).param("id", userId).update();
    }

    public record EditableAccount(AuthUser user, String passwordHash) {}

    /** Ánh xạ tài khoản; trạng thái suy ra từ các cột mốc thời gian tại thời điểm đọc. */
    private AuthUser account(ResultSet rs, int row) throws SQLException {
        String avatar = rs.getString("avatar_url");
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
        String status;
        if (rs.getObject("deleted_at", OffsetDateTime.class) != null) {
            status = "DELETED";
        } else if (rs.getObject("email_verified_at", OffsetDateTime.class) == null) {
            status = "UNVERIFIED";
        } else if (rs.getBoolean("admin_locked") || (lockedUntil != null && lockedUntil.isAfter(now))) {
            status = "LOCKED";
        } else {
            status = "ACTIVE";
        }
        return new AuthUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("phone"),
                rs.getString("full_name"), avatar == null ? null : URI.create(avatar),
                Role.valueOf(rs.getString("role")), status, rs.getBoolean("phone_verified"));
    }

    public boolean existsByEmail(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_account WHERE lower(email) = lower(:email))")
                .param("email", email)
                .query(Boolean.class)
                .single();
    }

    public boolean existsByPhone(String phone) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_account WHERE phone = :phone AND phone_verified_at IS NOT NULL)")
                .param("phone", PhoneNumbers.normalize(phone).orElse(phone))
                .query(Boolean.class)
                .single();
    }

    /** Tạo tài khoản BUYER chưa xác minh email (chờ OTP). */
    public AuthUser createUnverifiedUser(String email, String phone, String fullName,
                                         String passwordHash, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO user_account (email, phone, full_name, password_hash, role, created_at, updated_at)
                        VALUES (:email, :phone, :fullName, :passwordHash, 'BUYER', :now, :now)
                        RETURNING
                        """ + ACCOUNT_COLUMNS)
                .param("email", email)
                .param("phone", phone)
                .param("fullName", fullName)
                .param("passwordHash", passwordHash)
                .param("now", now)
                .query(this::account)
                .single();
    }

    /**
     * Ghi một lần gửi OTP. Mỗi lần gửi là một dòng mới; {@code usable = false} dùng để ghi lượt đã bị từ chối
     * (đếm vào hạn mức nhưng không cho nhập mã).
     */
    public OtpRecord createOtp(UUID userId, OtpPurpose purpose, UUID clientKey, String phone, String codeHash,
                               OffsetDateTime now, OffsetDateTime expiresAt, boolean usable) {
        return jdbc.sql("""
                        INSERT INTO otp_verification (user_id, purpose, phone, client_key, code_hash, sent_at, expires_at, delivered)
                        VALUES (:userId, CAST(:purpose AS otp_purpose), :phone, :clientKey, :codeHash, :now, :expiresAt,
                                :usable)
                        RETURNING id, user_id, purpose, phone, client_key, code_hash, attempts, sent_at, expires_at, consumed_at
                        """)
                .param("userId", userId)
                .param("purpose", purpose.name())
                .param("phone", phone)
                .param("clientKey", clientKey)
                .param("codeHash", codeHash)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .param("usable", usable)
                .query(AuthRepository::otp)
                .single();
    }

    /** Khoá dòng OTP và tài khoản chủ để kiểm tra trong transaction. */
    public Optional<OtpWithUser> findOtpForUpdate(UUID otpId) {
        return jdbc.sql("""
                        SELECT o.id, o.user_id, o.purpose, o.phone, o.client_key, o.code_hash, o.attempts,
                               o.sent_at, o.expires_at, o.consumed_at
                        FROM otp_verification o
                        WHERE o.id = :id
                        FOR UPDATE OF o
                        """)
                .param("id", otpId)
                .query((rs, row) -> new OtpWithUser(otp(rs, row)))
                .optional();
    }

    /** Dòng OTP mới nhất của (user, purpose) là dòng duy nhất còn hiệu lực. Trả về id của nó. */
    public Optional<UUID> latestOtpId(UUID userId, OtpPurpose purpose) {
        return jdbc.sql("""
                        SELECT id FROM otp_verification
                        WHERE user_id = :user AND purpose = CAST(:purpose AS otp_purpose) AND delivered
                        ORDER BY sent_at DESC, id DESC LIMIT 1
                        """)
                .param("user", userId).param("purpose", purpose.name())
                .query(UUID.class).optional();
    }

    public Optional<OtpRecord> latestOtp(UUID userId, OtpPurpose purpose) {
        return jdbc.sql("""
                        SELECT id, user_id, purpose, phone, client_key, code_hash, attempts, sent_at, expires_at, consumed_at
                        FROM otp_verification
                        WHERE user_id = :user AND purpose = CAST(:purpose AS otp_purpose) AND delivered
                        ORDER BY sent_at DESC, id DESC LIMIT 1
                        """)
                .param("user", userId).param("purpose", purpose.name())
                .query(AuthRepository::otp).optional();
    }

    public void markOtpDelivered(UUID id) {
        jdbc.sql("UPDATE otp_verification SET delivered = true WHERE id = :id").param("id", id).update();
    }

    public List<OffsetDateTime> recentOtpSends(UUID user, OtpPurpose purpose, OffsetDateTime since, int limit) {
        return jdbc.sql("""
                SELECT sent_at FROM otp_verification WHERE user_id = :user AND purpose = CAST(:purpose AS otp_purpose)
                    AND sent_at > :since ORDER BY sent_at DESC LIMIT :limit
                """).param("user", user).param("purpose", purpose.name()).param("since", since).param("limit", limit)
                .query(OffsetDateTime.class).list();
    }

    public void incrementOtpAttempts(UUID otpId) {
        jdbc.sql("UPDATE otp_verification SET attempts = attempts + 1 WHERE id = :id")
                .param("id", otpId)
                .update();
    }

    public void consumeOtp(UUID otpId, OffsetDateTime now) {
        jdbc.sql("UPDATE otp_verification SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
                .param("id", otpId)
                .param("now", now)
                .update();
    }

    /** Xác minh email lần đầu (không ghi đè thời điểm cũ). */
    public void activateUser(UUID userId, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account SET email_verified_at = COALESCE(email_verified_at, :now), updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", userId)
                .param("now", now)
                .update();
    }

    /** Huỷ đăng ký chưa xác minh khi không gửi được email: xoá OTP rồi xoá tài khoản chưa xác minh. */
    public void deleteUnverifiedRegistration(UUID userId) {
        jdbc.sql("DELETE FROM otp_verification WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM user_account WHERE id = :userId AND email_verified_at IS NULL AND deleted_at IS NULL")
                .param("userId", userId)
                .update();
    }

    public Optional<AuthUser> findByEmailForUpdate(String email) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM user_account WHERE lower(email) = lower(:email) FOR UPDATE")
                .param("email", email)
                .query(this::account)
                .optional();
    }

    /** Đặt mật khẩu mới, tăng auth_version để vô hiệu JWT cũ và gỡ khoá tạm do sai mật khẩu. */
    public void updatePasswordAndUnlock(UUID userId, String passwordHash, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account
                        SET password_hash = :passwordHash,
                            auth_version = auth_version + 1,
                            locked_until = NULL,
                            failed_login_count = 0,
                            last_failed_login_at = NULL,
                            updated_at = :now
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .param("passwordHash", passwordHash)
                .param("now", now)
                .update();
    }

    /** Khoá phạm vi gửi ngay cả khi user chưa có phiên; băm số trước khi đưa vào khoá. */
    public void lockPhoneOtpScope(String scope) {
        long key = UUID.nameUUIDFromBytes(("phone-otp:" + scope).getBytes(StandardCharsets.UTF_8))
                .getMostSignificantBits();
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query((rs, row) -> true).single();
    }

    /** Thời điểm các lần gửi SMS gần nhất của user, mới nhất trước. */
    public List<OffsetDateTime> recentPhoneSendsByUser(UUID userId, OffsetDateTime since, int limit) {
        return jdbc.sql("""
                SELECT sent_at FROM otp_verification
                WHERE user_id = :user AND purpose = 'PHONE' AND sent_at > :since
                ORDER BY sent_at DESC LIMIT :limit
                """).param("user", userId).param("since", since).param("limit", limit)
                .query(OffsetDateTime.class).list();
    }

    /** Thời điểm các lần gửi SMS gần nhất của một IP đã băm, trên mọi user và số đích. */
    public List<OffsetDateTime> recentPhoneSendsByClient(UUID clientKey, OffsetDateTime since, int limit) {
        return jdbc.sql("""
                SELECT sent_at FROM otp_verification
                WHERE purpose = 'PHONE' AND client_key = :clientKey AND sent_at > :since
                ORDER BY sent_at DESC LIMIT :limit
                """).param("clientKey", clientKey).param("since", since).param("limit", limit)
                .query(OffsetDateTime.class).list();
    }

    /** Thời điểm các lần gửi SMS gần nhất tới một số, trên mọi user. */
    public List<OffsetDateTime> recentPhoneSendsByPhone(String phone, OffsetDateTime since, int limit) {
        return jdbc.sql("""
                SELECT sent_at FROM otp_verification
                WHERE purpose = 'PHONE' AND phone = :phone AND sent_at > :since
                ORDER BY sent_at DESC LIMIT :limit
                """).param("phone", phone).param("since", since).param("limit", limit)
                .query(OffsetDateTime.class).list();
    }

    /** Kiểm tra số đã xác minh của người khác. */
    public boolean isPhoneVerifiedByOther(UUID userId, String phone) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM user_account
                WHERE phone = :phone AND phone_verified_at IS NOT NULL AND id <> :user)
                """).param("phone", phone).param("user", userId).query(Boolean.class).single();
    }

    /** Ghi số E.164 đã xác minh; unique index quyết định chủ sở hữu khi tranh chấp. */
    public AuthUser verifyPhone(UUID userId, String phone, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE user_account SET phone = :phone, phone_verified_at = :now, updated_at = :now WHERE id = :id
                RETURNING
                        """ + ACCOUNT_COLUMNS)
                .param("phone", phone).param("now", now).param("id", userId)
                .query(this::account).single();
    }

    /** Đọc cờ xác minh SĐT cho cổng giữ ghế; user không tồn tại trả false. */
    public boolean isPhoneVerified(UUID userId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM user_account
                WHERE id = :id AND phone IS NOT NULL AND phone_verified_at IS NOT NULL)
                """).param("id", userId).query(Boolean.class).single();
    }

    private static OtpRecord otp(ResultSet rs, int row) throws SQLException {
        return new OtpRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                OtpPurpose.valueOf(rs.getString("purpose")), rs.getString("phone"),
                rs.getObject("client_key", UUID.class), rs.getString("code_hash"), rs.getInt("attempts"),
                rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("consumed_at", OffsetDateTime.class));
    }

    public enum OtpPurpose { VERIFY, RESET, PHONE }

    public record RotatedSession(UUID id, OffsetDateTime expiresAt, AuthUser account) {
    }

    public record PasswordAccount(AuthUser user, String passwordHash) {
    }

    public record OtpRecord(
            UUID id,
            UUID userId,
            OtpPurpose purpose,
            String phone,
            UUID clientKey,
            String codeHash,
            int attempts,
            OffsetDateTime sentAt,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt) {
    }

    public record OtpWithUser(OtpRecord otp) {
    }
}
