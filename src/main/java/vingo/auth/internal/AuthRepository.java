package vingo.auth.internal;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
                        SELECT id, email, phone, full_name, avatar_url, role, status
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
                        RETURNING id, email, phone, full_name, avatar_url, role, status
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
        String predicate = identifier.contains("@") ? "lower(email) = lower(:identifier)" : "phone = :identifier";
        return jdbc.sql("""
                        SELECT id, email, phone, full_name, avatar_url, role, status, password_hash, locked_until
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
                          AND s.user_id = u.id AND u.status = 'ACTIVE'
                        RETURNING s.id AS session_id, s.expires_at, u.id, u.email, u.phone,
                                  u.full_name, u.avatar_url, u.role, u.status
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

    /**
     * Ánh xạ các cột tài khoản, kể cả avatar có thể vắng mặt.
     */
    private static AuthUser account(ResultSet rs, int row) throws SQLException {
        String avatar = rs.getString("avatar_url");
        return new AuthUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("phone"),
                rs.getString("full_name"), avatar == null ? null : URI.create(avatar),
                Role.valueOf(rs.getString("role")), rs.getString("status"));
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
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE phone = :phone)")
                .param("phone", phone)
                .query(Boolean.class)
                .single();
    }

    public AuthUser createUnverifiedUser(String email, String phone, String fullName,
                                         String passwordHash, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO users (email, phone, full_name, password_hash, role, status, created_at, updated_at)
                        VALUES (:email, :phone, :fullName, :passwordHash, 'BUYER', 'UNVERIFIED', :now, :now)
                        RETURNING id, email, phone, full_name, avatar_url, role, status
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
        return jdbc.sql("""
                        INSERT INTO otp_session (user_id, purpose, channel, otp_hash, attempts, created_at, expires_at, last_sent_at)
                        VALUES (:userId, :purpose, :channel, :otpHash, 0, :now, :expiresAt, :now)
                        RETURNING id, user_id, purpose, channel, otp_hash, attempts, created_at, expires_at, consumed_at, last_sent_at
                        """)
                .param("userId", userId)
                .param("purpose", purpose)
                .param("channel", channel)
                .param("otpHash", otpHash)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .query((rs, row) -> new OtpSessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getString("channel"),
                        rs.getString("otp_hash"),
                        rs.getInt("attempts"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("consumed_at", OffsetDateTime.class),
                        rs.getObject("last_sent_at", OffsetDateTime.class)))
                .single();
    }

    public Optional<OtpSessionWithUser> findOtpSessionForUpdate(UUID otpSessionId) {
        return jdbc.sql("""
                        SELECT o.id AS otp_id, o.user_id, o.purpose, o.channel, o.otp_hash, o.attempts,
                               o.created_at, o.expires_at, o.consumed_at, o.last_sent_at,
                               u.id, u.email, u.phone, u.full_name, u.avatar_url, u.role, u.status
                        FROM otp_session o
                        JOIN users u ON u.id = o.user_id
                        WHERE o.id = :id
                        FOR UPDATE OF o, u
                        """)
                .param("id", otpSessionId)
                .query((rs, row) -> new OtpSessionWithUser(
                        new OtpSessionRecord(
                                rs.getObject("otp_id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                rs.getString("purpose"),
                                rs.getString("channel"),
                                rs.getString("otp_hash"),
                                rs.getInt("attempts"),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("expires_at", OffsetDateTime.class),
                                rs.getObject("consumed_at", OffsetDateTime.class),
                                rs.getObject("last_sent_at", OffsetDateTime.class)),
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
            OffsetDateTime lastSentAt) {
    }

    public record OtpSessionWithUser(OtpSessionRecord session, AuthUser user) {
    }
}
