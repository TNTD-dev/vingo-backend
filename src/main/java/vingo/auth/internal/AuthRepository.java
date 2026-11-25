package vingo.auth.internal;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.api.model.SessionInfo;
import vingo.shared.security.AuthenticatedUser;
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
            admin_locked, locked_until
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
                .query((rs, row) -> account(rs, false)).optional();
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
                .query((rs, row) -> account(rs, false)).optional();
    }

    /** Tìm và khoá tài khoản theo email (không phân biệt hoa/thường). */
    public Optional<PasswordAccount> findForPasswordLogin(String email) {
        return jdbc.sql("""
                        SELECT %s, password_hash
                        FROM user_account
                        WHERE lower(email) = lower(:email)
                        FOR UPDATE
                        """.formatted(ACCOUNT_COLUMNS))
                .param("email", email)
                .query((rs, row) -> new PasswordAccount(account(rs, row), rs.getString("password_hash"), rs.getBoolean("admin_locked")))
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

    public void createSession(UUID userId, String tokenHash, OffsetDateTime now, OffsetDateTime expiresAt,
                              String agent, String ip) {
        jdbc.sql("""
                INSERT INTO login_session (user_id, token_hash, created_at, expires_at, last_seen_at, user_agent, last_ip)
                VALUES (:user, :hash, :now, :expires, :now, left(:agent, 512), CAST(:ip AS inet))
                """).param("user", userId).param("hash", tokenHash).param("now", now).param("expires", expiresAt)
                .param("agent", agent).param("ip", ip).update();
    }

    /** Gọi dưới khoá user; chỉ trả true khi vừa thêm thiết bị và đã có thiết bị khác. */
    public boolean recordDevice(UUID userId, String hash, OffsetDateTime now) {
        int added = jdbc.sql("""
                INSERT INTO user_device (user_id, device_hash, created_at, last_seen_at)
                VALUES (:user, :hash, :now, :now) ON CONFLICT (user_id, device_hash) DO NOTHING
                """).param("user", userId).param("hash", hash).param("now", now).update();
        if (added == 0) {
            jdbc.sql("""
                    UPDATE user_device SET last_seen_at = greatest(last_seen_at, :now)
                    WHERE user_id = :user AND device_hash = :hash
                    """).param("user", userId).param("hash", hash).param("now", now).update();
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_device WHERE user_id = :user AND device_hash <> :hash)")
                .param("user", userId).param("hash", hash).query(Boolean.class).single();
    }

    public Optional<AuthenticatedUser> authenticate(String hash, OffsetDateTime now,
                                                   String agent, String ip, boolean recordActivity) {
        // Use the same snapshot for identity and the conditional activity write.
        return jdbc.sql("""
                WITH valid AS MATERIALIZED (
                    SELECT s.id, s.user_id, s.expires_at, s.last_seen_at, u.role
                    FROM login_session s JOIN user_account u ON u.id = s.user_id
                    WHERE s.token_hash = :hash AND s.expires_at > :now
                      AND u.deleted_at IS NULL AND u.email_verified_at IS NOT NULL AND NOT u.admin_locked
                ), touched AS (
                    UPDATE login_session s SET last_seen_at = :now, user_agent = left(:agent, 512), last_ip = CAST(:ip AS inet)
                    FROM valid v WHERE :recordActivity AND s.id = v.id AND s.token_hash = :hash
                      AND v.last_seen_at <= :cutoff AND s.last_seen_at <= :cutoff
                )
                SELECT id, user_id, role, expires_at FROM valid
                """).param("hash", hash).param("now", now)
                .param("recordActivity", recordActivity).param("cutoff", now.minusMinutes(5)).param("agent", agent)
                .param("ip", LoginClient.normalizeIp(ip))
                .query((rs, row) -> new AuthenticatedUser(rs.getObject("user_id", UUID.class),
                        Role.valueOf(rs.getString("role")), rs.getObject("id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class))).optional();
    }

    public List<SessionInfo> sessions(UUID user, UUID current, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT id, created_at, last_seen_at, expires_at, user_agent, host(last_ip) AS ip FROM login_session
                WHERE user_id = :user AND expires_at > :now
                ORDER BY last_seen_at DESC, created_at DESC, id DESC
                """).param("user", user).param("now", now)
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new SessionInfo(id, rs.getString("user_agent"), rs.getString("ip"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("last_seen_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                            current.equals(id));
                }).list();
    }

    public boolean replaceToken(UUID user, UUID session, String hash) {
        if (jdbc.sql("UPDATE login_session SET token_hash = :hash WHERE user_id = :user AND id = :id")
                .param("user", user).param("id", session).param("hash", hash).update() == 0) {
            return false;
        }
        jdbc.sql("DELETE FROM login_session WHERE user_id = :user AND id <> :id")
                .param("user", user).param("id", session).update();
        return true;
    }

    /** Đăng xuất = xoá phiên; allDevices xoá mọi phiên của đúng user. */
    public int revoke(UUID userId, UUID sessionId, boolean allDevices) {
        return jdbc.sql("""
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
                .query((rs, row) -> new EditableAccount(account(rs, row), rs.getString("password_hash"), rs.getBoolean("admin_locked")))
                .optional();
    }

    public void updateAccount(UUID userId, String fullName, String phone, String passwordHash,
                              OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account SET full_name = :name, phone = :phone,
                            password_hash = COALESCE(:passwordHash, password_hash), updated_at = :now
                        WHERE id = :id
                        """)
                .param("name", fullName).param("phone", phone).param("passwordHash", passwordHash)
                .param("now", now).param("id", userId).update();
    }

    /** Ẩn danh hoá tài khoản: giữ hàng để khoá ngoại của OTP và đơn vẫn đúng, xoá mọi dữ liệu cá nhân. */
    public void deleteAccount(UUID userId, OffsetDateTime now) {
        jdbc.sql("DELETE FROM user_device WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("""
                        UPDATE user_account SET email = 'deleted+' || id || '@invalid.local', phone = NULL,
                            full_name = 'Deleted user', password_hash = NULL,
                            google_sub = NULL, avatar_url = NULL, locked_until = NULL,
                            failed_login_count = 0, last_failed_login_at = NULL,
                            deleted_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", now).param("id", userId).update();
    }

    public record EditableAccount(AuthUser user, String passwordHash, boolean adminLocked) {}

    /** Ánh xạ tài khoản; trạng thái suy ra từ các cột mốc thời gian tại thời điểm đọc. */
    private AuthUser account(ResultSet rs, int row) throws SQLException {
        return account(rs, true);
    }

    private AuthUser account(ResultSet rs, boolean checkTemporaryLock) throws SQLException {
        String avatar = rs.getString("avatar_url");
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
        String status;
        if (rs.getObject("deleted_at", OffsetDateTime.class) != null) {
            status = "DELETED";
        } else if (rs.getObject("email_verified_at", OffsetDateTime.class) == null) {
            status = "UNVERIFIED";
        } else if (rs.getBoolean("admin_locked") || (checkTemporaryLock && lockedUntil != null && lockedUntil.isAfter(now))) {
            status = "LOCKED";
        } else {
            status = "ACTIVE";
        }
        return new AuthUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("phone"),
                rs.getString("full_name"), avatar == null ? null : URI.create(avatar),
                Role.valueOf(rs.getString("role")), status);
    }

    public boolean existsByEmail(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_account WHERE lower(email) = lower(:email))")
                .param("email", email)
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

    /** Ghi lượt gửi OTP email; phiên gửi lại chỉ có hiệu lực sau khi gửi thành công. */
    public OtpRecord createOtp(UUID userId, OtpPurpose purpose, String targetEmail, UUID clientKey, String codeHash,
                               OffsetDateTime now, OffsetDateTime expiresAt, boolean usable) {
        return jdbc.sql("""
                        INSERT INTO otp_verification (user_id, purpose, target_email, client_key, code_hash, sent_at, expires_at, delivered)
                        VALUES (:userId, CAST(:purpose AS otp_purpose), :targetEmail, :clientKey, :codeHash, :now, :expiresAt,
                                :usable)
                        RETURNING id, user_id, purpose, target_email, client_key, code_hash, attempts, sent_at, expires_at, consumed_at
                        """)
                .param("userId", userId)
                .param("purpose", purpose.name())
                .param("targetEmail", targetEmail)
                .param("clientKey", clientKey)
                .param("codeHash", codeHash)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .param("usable", usable)
                .query(AuthRepository::otp)
                .single();
    }

    /** Khoá dòng OTP để kiểm tra trong transaction; service khoá tài khoản chủ sau đó. */
    public Optional<OtpWithUser> findOtpForUpdate(UUID otpId) {
        return jdbc.sql("""
                        SELECT o.id, o.user_id, o.purpose, o.target_email, o.client_key, o.code_hash, o.attempts,
                               o.sent_at, o.expires_at, o.consumed_at
                        FROM otp_verification o
                        WHERE o.id = :id AND o.purpose IN ('VERIFY', 'RESET')
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

    /** Đặt mật khẩu mới và gỡ khoá tạm do sai mật khẩu. */
    public void updatePasswordAndUnlock(UUID userId, String passwordHash, OffsetDateTime now) {
        jdbc.sql("""
                        UPDATE user_account
                        SET password_hash = :passwordHash,
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

    private static OtpRecord otp(ResultSet rs, int row) throws SQLException {
        return new OtpRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                OtpPurpose.valueOf(rs.getString("purpose")), rs.getString("target_email"),
                rs.getObject("client_key", UUID.class), rs.getString("code_hash"), rs.getInt("attempts"),
                rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("consumed_at", OffsetDateTime.class));
    }

    public enum OtpPurpose { VERIFY, RESET }

    public record PasswordAccount(AuthUser user, String passwordHash, boolean adminLocked) {
    }

    public record OtpRecord(
            UUID id,
            UUID userId,
            OtpPurpose purpose,
            String targetEmail,
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
