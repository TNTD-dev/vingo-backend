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

    public record RotatedSession(UUID id, OffsetDateTime expiresAt, AuthUser account) {

    }

    public record PasswordAccount(AuthUser user, String passwordHash, OffsetDateTime lockedUntil) {
    }
}
