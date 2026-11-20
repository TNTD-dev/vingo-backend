package vingo.auth.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.api.model.AdminAccount;

/** Quản trị tài khoản. Trạng thái không lưu cột: suy ra từ mốc thời gian và cờ khoá. */
@Repository
public class AdminAccountRepository {

    /** Trạng thái suy ra: đã xoá, chưa xác minh email, bị khoá (quản trị hoặc tạm thời), còn hoạt động. */
    private static final String STATUS_EXPR = """
            CASE WHEN deleted_at IS NOT NULL THEN 'DELETED'
                 WHEN email_verified_at IS NULL THEN 'UNVERIFIED'
                 WHEN admin_locked OR (locked_until IS NOT NULL AND locked_until > now()) THEN 'LOCKED'
                 ELSE 'ACTIVE' END
            """;
    private static final String ACCOUNTS = """
            (SELECT id, email, phone, full_name, role, email_verified_at, phone_verified_at,
                    admin_locked, locked_until, deleted_at, created_at, password_hash,
                    (%s) AS status
             FROM user_account) a
            """.formatted(STATUS_EXPR);
    private static final String COLUMNS = "id, email, phone, full_name, role, email_verified_at, phone_verified_at, admin_locked, locked_until, deleted_at, created_at, (password_hash IS NOT NULL) AS has_password";

    private final JdbcClient jdbc;

    public AdminAccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<AdminAccount> list(String q, String role, String status, UUID after, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + ACCOUNTS + """
                 WHERE
                 (CAST(:q AS text) IS NULL OR strpos(lower(full_name), lower(:q)) > 0
                  OR strpos(lower(email), lower(:q)) > 0 OR strpos(COALESCE(phone, ''), :q) > 0)
                 AND (CAST(:role AS text) IS NULL OR role::text = :role)
                 AND (CAST(:status AS text) IS NULL OR status = :status)
                 AND (CAST(:after AS uuid) IS NULL OR id < :after)
                 ORDER BY id DESC LIMIT :limit
                """)
                .param("q", q).param("role", role).param("status", status)
                .param("after", after).param("limit", limit).query(AdminAccountRepository::map).list();
    }

    public Optional<AdminAccount> find(UUID id, boolean lock) {
        if (lock) {
            // Khoá dòng gốc trước; subquery không nhận FOR UPDATE.
            jdbc.sql("SELECT id FROM user_account WHERE id = :id FOR UPDATE")
                    .param("id", id).query(UUID.class).optional();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + ACCOUNTS + " WHERE id = :id")
                .param("id", id).query(AdminAccountRepository::map).optional();
    }

    /** Tuần tự hóa các thay đổi quyền/khóa để bảo vệ ADMIN hoạt động cuối cùng. */
    public void lockAdministration() {
        jdbc.sql("SELECT pg_advisory_xact_lock(86420161116)").query((rs, row) -> true).single();
    }

    public long activeAdmins() {
        return jdbc.sql("SELECT count(*) FROM user_account WHERE role = 'ADMIN' AND deleted_at IS NULL AND email_verified_at IS NOT NULL AND NOT admin_locked AND (locked_until IS NULL OR locked_until <= now())")
                .query(Long.class).single();
    }

    /** Cập nhật vai trò và cờ khoá; khoá/mở quyền thì thu hồi mọi phiên và tăng auth_version. */
    public void update(UUID id, String role, boolean locked, boolean resetLock, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE user_account SET role = CAST(:role AS user_role), admin_locked = :locked,
                    locked_until = CASE WHEN :resetLock THEN NULL ELSE locked_until END,
                    auth_version = auth_version + 1, updated_at = :now WHERE id = :id
                """)
                .param("id", id).param("role", role)
                .param("locked", locked).param("resetLock", resetLock).param("now", now).update();
        jdbc.sql("DELETE FROM login_session WHERE user_id = :id")
                .param("id", id).update();
    }

    /** Ghi thao tác: before/after chỉ chứa các trường thực sự đổi. */
    public void audit(UUID actor, AdminAccount before, AdminAccount after, String reason, OffsetDateTime now) {
        var beforeJson = new ArrayList<String>();
        var afterJson = new ArrayList<String>();
        if (before.getRole() != after.getRole()) {
            beforeJson.add("\"role\":\"" + before.getRole().getValue() + "\"");
            afterJson.add("\"role\":\"" + after.getRole().getValue() + "\"");
        }
        if (!before.getAdminLocked().equals(after.getAdminLocked())) {
            beforeJson.add("\"admin_locked\":" + before.getAdminLocked());
            afterJson.add("\"admin_locked\":" + after.getAdminLocked());
        }
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, entity, entity_id, reason, at, before, after)
                VALUES (:actor, 'UPDATE_ACCOUNT', 'user_account', :id, :reason, :now,
                        CAST(:before AS jsonb), CAST(:after AS jsonb))
                """)
                .param("actor", actor).param("id", before.getId()).param("reason", reason).param("now", now)
                .param("before", "{" + String.join(",", beforeJson) + "}")
                .param("after", "{" + String.join(",", afterJson) + "}")
                .update();
    }

    private static AdminAccount map(ResultSet rs, int row) throws SQLException {
        return new AdminAccount().id(rs.getObject("id", UUID.class)).email(rs.getString("email"))
                .phone(rs.getString("phone")).fullName(rs.getString("full_name"))
                .role(AdminAccount.RoleEnum.fromValue(rs.getString("role")))
                .emailVerifiedAt(rs.getObject("email_verified_at", OffsetDateTime.class))
                .phoneVerifiedAt(rs.getObject("phone_verified_at", OffsetDateTime.class))
                .adminLocked(rs.getBoolean("admin_locked")).hasPassword(rs.getBoolean("has_password"))
                .lockedUntil(rs.getObject("locked_until", OffsetDateTime.class))
                .deletedAt(rs.getObject("deleted_at", OffsetDateTime.class))
                .createdAt(rs.getObject("created_at", OffsetDateTime.class));
    }
}
