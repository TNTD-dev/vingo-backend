package vingo.auth.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.auth.AuthUser;
import vingo.shared.security.Role;

@Repository
public class AuthRepository {

    private final JdbcClient jdbc;

    /**
     * Nhận JDBC client dùng chung cho truy vấn tài khoản và phiên.
     */
    AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tìm và khoá bản ghi liên kết Google trong transaction đăng nhập.
     */
    public Optional<AuthUser> findByGoogleSubject(String subject) {
        return jdbc.sql("""
                        SELECT id, email, phone, full_name, avatar_url, role, status,
                               phone_verified_at IS NOT NULL AS phone_verified
                        FROM users WHERE google_sub = :subject FOR UPDATE
                        """)
                .param("subject", subject)
                .query(AuthRepository::account).optional();
    }

    /**
     * Tạo buyer Google, trả rỗng nếu email hoặc subject đã có chủ.
     */
    public Optional<AuthUser> createGoogleAccount(String subject, String email, String name, URI avatar) {
        return jdbc.sql("""
                        INSERT INTO users (google_sub, email, full_name, avatar_url, role, status)
                        VALUES (:subject, :email, :name, :avatar, 'BUYER', 'ACTIVE')
                        ON CONFLICT DO NOTHING
                        RETURNING id, email, phone, full_name, avatar_url, role, status,
                                  phone_verified_at IS NOT NULL AS phone_verified
                        """)
                .param("subject", subject)
                .param("email", email)
                .param("name", name)
                .param("avatar", avatar == null ? null : avatar.toString())
                .query(AuthRepository::account).optional();
    }

    /** Lưu hash refresh token và thời điểm hết hạn của phiên mới. */
    /**
     * Tìm và khoá bản ghi tài khoản theo email (không phân biệt hoa/thường) hoặc SĐT.
     */
    public Optional<PasswordAccount> findForPasswordLogin(String identifier) {
        String predicate = "lower(email) = lower(:identifier)";
        if (!identifier.contains("@")) {
            var phone = PhoneNumbers.normalize(identifier);
            if (phone.isEmpty()) return Optional.empty();
            identifier = phone.get();
            predicate = "phone_verified_at IS NOT NULL AND phone = :identifier";
        }
        return jdbc.sql("""
                        SELECT id, email, phone, full_name, avatar_url, role, status, password_hash, locked_until,
                               phone_verified_at IS NOT NULL AS phone_verified
                        FROM users
                        WHERE %s
                        FOR UPDATE
                        """.formatted(predicate))
                .param("identifier", identifier)
                .query((rs, row) -> new PasswordAccount(account(rs, row), rs.getString("password_hash"),
                        rs.getObject("locked_until", OffsetDateTime.class)))
                .optional();
    }

    /**
     * Ghi nhận một lần thử đăng nhập (thành công hoặc thất bại).
     */
    public void recordLoginAttempt(UUID userId, boolean success, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO login_attempt (user_id, success, at)
                        VALUES (:user, :success, :at)
                        """)
                .param("user", userId)
                .param("success", success)
                .param("at", at)
                .update();
    }

    /**
     * Đếm số lần đăng nhập sai gần đây trong cửa sổ, tính từ sau lần thành công hoặc hết khoá gần nhất.
     */
    public int countRecentFailedAttempts(UUID userId, OffsetDateTime windowStart, OffsetDateTime lockedUntil) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM login_attempt
                        WHERE user_id = :user
                          AND success = false
                          AND at >= GREATEST(
                              :windowStart,
                              COALESCE(CAST(:lockedUntil AS timestamptz), '-infinity'::timestamptz),
                              COALESCE((SELECT max(at) FROM login_attempt WHERE user_id = :user AND success = true), '-infinity'::timestamptz)
                          )
                        """)
                .param("user", userId)
                .param("windowStart", windowStart)
                .param("lockedUntil", lockedUntil)
                .query(Integer.class)
                .single();
    }

    /**
     * Khoá tạm tài khoản đến thời điểm chỉ định.
     */
    public void lockTemporarily(UUID userId, OffsetDateTime lockedUntil, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE users
                        SET status = 'LOCKED', locked_until = :lockedUntil, updated_at = :now
                        WHERE id = :user
                        """)
                .param("user", userId)
                .param("lockedUntil", lockedUntil)
                .param("now", now)
                .update();
    }

    /**
     * Mở khoá tài khoản sau khi hết thời gian khoá tạm và đăng nhập thành công.
     */
    public void unlockAfterExpiry(UUID userId, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE users
                        SET status = 'ACTIVE', locked_until = NULL, updated_at = :now
                        WHERE id = :user
                        """)
                .param("user", userId)
                .param("now", now)
                .update();
    }

    /**
     * Lưu hash refresh token và thời điểm hết hạn của phiên mới.
     */
    public void createSession(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        createSession(userId, tokenHash, null, expiresAt);
    }

    /**
     * Lưu hash refresh token kèm tên thiết bị và trả về id phiên vừa tạo.
     */
    public UUID createSession(UUID userId, String tokenHash, String device, OffsetDateTime expiresAt) {
        return jdbc.sql("""
                        INSERT INTO sessions (user_id, refresh_token_hash, device, expires_at)
                        VALUES (:user, :hash, :device, :expires)
                        RETURNING id
                        """)
                .param("user", userId)
                .param("hash", tokenHash)
                .param("device", device)
                .param("expires", expiresAt)
                .query(UUID.class)
                .single();
    }

    /**
     * Thay hash một cách nguyên tử nếu phiên và tài khoản còn hiệu lực.
     */
    public Optional<RotatedSession> rotate(String oldHash, String newHash, OffsetDateTime now) {
        return jdbc.sql("""
                        UPDATE sessions s SET refresh_token_hash = :newHash
                        FROM users u
                        WHERE s.refresh_token_hash = :oldHash AND s.revoked_at IS NULL AND s.expires_at > :now
                          AND s.user_id = u.id AND u.status = 'ACTIVE' AND NOT u.admin_locked
                        RETURNING s.id AS session_id, s.expires_at, u.id, u.email, u.phone,
                                  u.full_name, u.avatar_url, u.role, u.status,
                                  u.phone_verified_at IS NOT NULL AS phone_verified
                        """)
                .param("newHash", newHash)
                .param("oldHash", oldHash)
                .param("now", now)
                .query((rs, row) -> new RotatedSession(rs.getObject("session_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class), account(rs, row)))
                .optional();
    }

    /**
     * Đánh dấu thu hồi cho một phiên hoặc mọi phiên của đúng user.
     */
    public void revoke(UUID userId, UUID sessionId, boolean allDevices, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE sessions SET revoked_at = :now
                        WHERE user_id = :user AND revoked_at IS NULL
                          AND (:allDevices OR id = CAST(:session AS uuid))
                        """)
                .param("user", userId)
                .param("session", sessionId)
                .param("allDevices", allDevices)
                .param("now", now)
                .update();
    }

    public Optional<EditableAccount> findEditableAccount(UUID userId) {
        return jdbc.sql("""
                        SELECT id, email, phone, full_name, avatar_url, role, status, password_hash,
                               phone_verified_at IS NOT NULL AS phone_verified
                        FROM users WHERE id = :id FOR UPDATE
                        """)
                .param("id", userId)
                .query((rs, row) -> new EditableAccount(account(rs, row), rs.getString("password_hash")))
                .optional();
    }

    public void updateAccount(UUID userId, String fullName, String phone, String passwordHash,
                              boolean phoneChanged, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE users SET full_name = :name, phone = :phone,
                            phone_verified_at = CASE WHEN :phoneChanged THEN NULL ELSE phone_verified_at END,
                            password_hash = COALESCE(:passwordHash, password_hash), updated_at = :now
                        WHERE id = :id
                        """)
                .param("name", fullName).param("phone", phone).param("passwordHash", passwordHash)
                .param("phoneChanged", phoneChanged)
                .param("now", now).param("id", userId).update();
    }

    public void deleteAccount(UUID userId, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE users SET email = 'deleted+' || id || '@invalid.local', phone = NULL, phone_verified_at = NULL,
                            full_name = 'Deleted user', password_hash = NULL, google_sub = NULL,
                            avatar_url = NULL, status = 'DELETED', locked_until = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", now).param("id", userId).update();
        jdbc.sql("DELETE FROM buyer_profile WHERE user_id = :id").param("id", userId).update();
    }

    public record EditableAccount(AuthUser user, String passwordHash) {}

    /**
     * Ánh xạ các cột tài khoản, kể cả avatar có thể vắng mặt.
     */
    private static AuthUser account(ResultSet rs, int row) throws SQLException {
        String avatar = rs.getString("avatar_url");
        return new AuthUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("phone"),
                rs.getString("full_name"), avatar == null ? null : URI.create(avatar),
                Role.valueOf(rs.getString("role")), rs.getString("status"), rs.getBoolean("phone_verified"));
    }

    /**
     * Kiểm tra người dùng đã khai báo hồ sơ người mua đủ họ tên, số điện thoại
     * và email.
     */
    public boolean hasBuyerProfile(UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM buyer_profile
                            WHERE user_id = :user
                              AND length(btrim(full_name)) > 0
                              AND length(btrim(phone)) > 0
                              AND length(btrim(email)) > 0
                        )
                        """)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    public boolean existsByEmail(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(:email))")
                .param("email", email)
                .query(Boolean.class)
                .single();
    }

    public boolean existsByPhone(String phone) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE phone = :phone AND phone_verified_at IS NOT NULL)")
                .param("phone", PhoneNumbers.normalize(phone).orElse(phone))
                .query(Boolean.class)
                .single();
    }

    public AuthUser createUnverifiedUser(String email, String phone, String fullName,
                                         String passwordHash, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO users (email, phone, full_name, password_hash, role, status, created_at, updated_at)
                        VALUES (:email, :phone, :fullName, :passwordHash, 'BUYER', 'UNVERIFIED', :now, :now)
                        RETURNING id, email, phone, full_name, avatar_url, role, status,
                                  phone_verified_at IS NOT NULL AS phone_verified
                        """)
                .param("email", email)
                .param("phone", phone)
                .param("fullName", fullName)
                .param("passwordHash", passwordHash)
                .param("now", now)
                .query(AuthRepository::account)
                .single();
    }

    public OtpSessionRecord createOtpSession(UUID userId, String purpose, String channel,
                                             String otpHash, OffsetDateTime now, OffsetDateTime expiresAt) {
        return createOtpSession(userId, purpose, channel, otpHash, now, expiresAt, null);
    }

    /** Tạo phiên OTP có số đích riêng cho purpose PHONE. */
    public OtpSessionRecord createOtpSession(UUID userId, String purpose, String channel,
                                             String otpHash, OffsetDateTime now, OffsetDateTime expiresAt, String phone) {
        return jdbc.sql("""
                        INSERT INTO otp_session (user_id, purpose, channel, otp_hash, attempts, created_at, expires_at, last_sent_at, phone)
                        VALUES (:userId, :purpose, :channel, :otpHash, 0, :now, :expiresAt, :now, :phone)
                        RETURNING id AS otp_id, user_id, purpose, channel, otp_hash, attempts,
                                  created_at, expires_at, consumed_at, last_sent_at, phone AS otp_phone
                        """)
                .param("userId", userId)
                .param("purpose", purpose)
                .param("channel", channel)
                .param("otpHash", otpHash)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .param("phone", phone)
                .query(AuthRepository::otpSession)
                .single();
    }

    public Optional<OtpSessionWithUser> findOtpSessionForUpdate(UUID otpSessionId) {
        return jdbc.sql("""
                        SELECT o.id AS otp_id, o.user_id, o.purpose, o.channel, o.otp_hash, o.attempts,
                               o.created_at, o.expires_at, o.consumed_at, o.last_sent_at, o.phone AS otp_phone,
                               u.id, u.email, u.phone, u.full_name, u.avatar_url, u.role, u.status,
                               u.phone_verified_at IS NOT NULL AS phone_verified
                        FROM otp_session o
                        JOIN users u ON u.id = o.user_id
                        WHERE o.id = :id
                        FOR UPDATE OF o, u
                        """)
                .param("id", otpSessionId)
                .query((rs, row) -> new OtpSessionWithUser(
                        otpSession(rs, row),
                        account(rs, row)))
                .optional();
    }

    public void incrementOtpAttempts(UUID otpSessionId) {
        jdbc.sql("UPDATE otp_session SET attempts = attempts + 1 WHERE id = :id")
                .param("id", otpSessionId)
                .update();
    }

    public void consumeOtpSession(UUID otpSessionId, OffsetDateTime now) {
        jdbc.sql("UPDATE otp_session SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
                .param("id", otpSessionId)
                .param("now", now)
                .update();
    }

    public void activateUser(UUID userId, OffsetDateTime now) {
        jdbc.sql("UPDATE users SET status = 'ACTIVE', updated_at = :now WHERE id = :id AND status = 'UNVERIFIED'")
                .param("id", userId)
                .param("now", now)
                .update();
    }

    public void refreshOtpSession(UUID otpSessionId, String newOtpHash, OffsetDateTime now, OffsetDateTime newExpiresAt) {
        jdbc.sql("""
                        UPDATE otp_session
                        SET otp_hash = :otpHash, attempts = 0, expires_at = :expiresAt, last_sent_at = :now
                        WHERE id = :id AND consumed_at IS NULL
                        """)
                .param("id", otpSessionId)
                .param("otpHash", newOtpHash)
                .param("expiresAt", newExpiresAt)
                .param("now", now)
                .update();
    }

    public void deleteUnverifiedRegistration(UUID userId, UUID otpSessionId) {
        jdbc.sql("DELETE FROM otp_session WHERE id = :otpId AND user_id = :userId AND consumed_at IS NULL")
                .param("otpId", otpSessionId)
                .param("userId", userId)
                .update();
        jdbc.sql("DELETE FROM users WHERE id = :userId AND status = 'UNVERIFIED'")
                .param("userId", userId)
                .update();
    }

    public Optional<AuthUser> findByEmailForUpdate(String email) {
        return jdbc.sql("""
                        SELECT id, email, phone, full_name, avatar_url, role, status,
                               phone_verified_at IS NOT NULL AS phone_verified
                        FROM users
                        WHERE lower(email) = lower(:email)
                        FOR UPDATE
                        """)
                .param("email", email)
                .query(AuthRepository::account)
                .optional();
    }

    public void consumeActiveOtpSessions(UUID userId, String purpose, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE otp_session
                        SET consumed_at = :now
                        WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL
                        """)
                .param("userId", userId)
                .param("purpose", purpose)
                .param("now", now)
                .update();
    }

    public void updatePasswordAndUnlock(UUID userId, String passwordHash, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE users
                        SET password_hash = :passwordHash,
                            auth_version = auth_version + 1,
                            status = CASE WHEN status = 'LOCKED' AND NOT admin_locked THEN 'ACTIVE' ELSE status END,
                            locked_until = NULL,
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

    /** Khoá phiên PHONE chưa consume để gửi lại luôn dùng cùng một phiên. */
    public Optional<OtpSessionRecord> findOpenPhoneOtpForUpdate(UUID userId) {
        return jdbc.sql("""
                SELECT id AS otp_id, user_id, purpose, channel, otp_hash, attempts,
                       created_at, expires_at, consumed_at, last_sent_at, phone AS otp_phone
                FROM otp_session WHERE user_id = :user AND purpose = 'PHONE' AND consumed_at IS NULL
                FOR UPDATE
                """).param("user", userId).query(AuthRepository::otpSession).optional();
    }

    /** Chỉ đọc và khoá phiên thuộc user gọi để không tiết lộ phiên của người khác. */
    public Optional<OtpSessionRecord> findOwnedOtpForUpdate(UUID userId, UUID sessionId) {
        return jdbc.sql("""
                SELECT id AS otp_id, user_id, purpose, channel, otp_hash, attempts,
                       created_at, expires_at, consumed_at, last_sent_at, phone AS otp_phone
                FROM otp_session WHERE id = :id AND user_id = :user
                FOR UPDATE
                """).param("id", sessionId).param("user", userId).query(AuthRepository::otpSession).optional();
    }

    /** Đọc tối đa ngưỡng tin gần nhất của user để tính hạn mức và lúc được gửi tiếp. */
    public List<OffsetDateTime> recentPhoneSendsByUser(UUID userId, OffsetDateTime since, int limit) {
        return jdbc.sql("""
                SELECT sent_at FROM phone_otp_send WHERE user_id = :user AND sent_at > :since
                ORDER BY sent_at DESC LIMIT :limit
                """).param("user", userId).param("since", since).param("limit", limit)
                .query(OffsetDateTime.class).list();
    }

    /** Đọc tối đa ngưỡng tin gần nhất của số đích trên mọi user. */
    public List<OffsetDateTime> recentPhoneSendsByPhone(String phone, OffsetDateTime since, int limit) {
        return jdbc.sql("""
                SELECT sent_at FROM phone_otp_send WHERE phone = :phone AND sent_at > :since
                ORDER BY sent_at DESC LIMIT :limit
                """).param("phone", phone).param("since", since).param("limit", limit)
                .query(OffsetDateTime.class).list();
    }

    /** Kiểm tra số đã xác minh của người khác sau khi ghi nhận lượt yêu cầu. */
    public boolean isPhoneVerifiedByOther(UUID userId, String phone) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM users
                WHERE phone = :phone AND phone_verified_at IS NOT NULL AND id <> :user)
                """).param("phone", phone).param("user", userId).query(Boolean.class).single();
    }

    /** Ghi lượt yêu cầu trong transaction, kể cả lượt bị từ chối do số đã xác minh. */
    public void recordPhoneOtpSend(UUID userId, String phone, OffsetDateTime now) {
        jdbc.sql("INSERT INTO phone_otp_send (user_id, phone, sent_at) VALUES (:user, :phone, :now)")
                .param("user", userId).param("phone", phone).param("now", now).update();
    }

    /** Đổi số đích và mã, đặt lại attempts và hạn dùng của phiên PHONE đang mở. */
    public void refreshPhoneOtpSession(UUID sessionId, String phone, String hash,
                                       OffsetDateTime now, OffsetDateTime expiresAt) {
        jdbc.sql("""
                UPDATE otp_session SET phone = :phone, otp_hash = :hash, attempts = 0,
                    expires_at = :expires, last_sent_at = :now
                WHERE id = :id AND purpose = 'PHONE' AND consumed_at IS NULL
                """).param("phone", phone).param("hash", hash).param("expires", expiresAt)
                .param("now", now).param("id", sessionId).update();
    }

    /** Ghi số E.164 đã xác minh; unique index quyết định chủ sở hữu khi tranh chấp. */
    public AuthUser verifyPhone(UUID userId, String phone, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE users SET phone = :phone, phone_verified_at = :now, updated_at = :now WHERE id = :id
                RETURNING id, email, phone, full_name, avatar_url, role, status,
                          phone_verified_at IS NOT NULL AS phone_verified
                """).param("phone", phone).param("now", now).param("id", userId)
                .query(AuthRepository::account).single();
    }

    /** Đọc cờ xác minh cho cổng booking, user không tồn tại trả false. */
    public boolean isPhoneVerified(UUID userId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM users
                WHERE id = :id AND phone IS NOT NULL AND phone_verified_at IS NOT NULL)
                """).param("id", userId).query(Boolean.class).single();
    }

    private static OtpSessionRecord otpSession(ResultSet rs, int row) throws SQLException {
        return new OtpSessionRecord(rs.getObject("otp_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("purpose"), rs.getString("channel"), rs.getString("otp_hash"), rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("consumed_at", OffsetDateTime.class), rs.getObject("last_sent_at", OffsetDateTime.class),
                rs.getString("otp_phone"));
    }

    public record RotatedSession(UUID id, OffsetDateTime expiresAt, AuthUser account) {

    }

    public record PasswordAccount(AuthUser user, String passwordHash, OffsetDateTime lockedUntil) {
    }

    public record OtpSessionRecord(
            UUID id,
            UUID userId,
            String purpose,
            String channel,
            String otpHash,
            int attempts,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt,
            OffsetDateTime lastSentAt,
            String phone) {
    }

    public record OtpSessionWithUser(OtpSessionRecord session, AuthUser user) {
    }
}
