package vingo.auth.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.api.model.AdminAccount;

@Repository
public class AdminAccountRepository {
    private static final String COLUMNS = "id, email, phone, full_name, role, status, admin_locked, locked_until, created_at, (password_hash IS NOT NULL) AS has_password";
    private final JdbcClient jdbc;

    public AdminAccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<AdminAccount> list(String q, String role, String status, UUID after, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM users WHERE
                 (CAST(:q AS text) IS NULL OR strpos(lower(full_name), lower(:q)) > 0
                  OR strpos(lower(email), lower(:q)) > 0 OR strpos(COALESCE(phone, ''), :q) > 0)
                 AND (CAST(:role AS text) IS NULL OR role = :role)
                 AND (CAST(:status AS text) IS NULL OR status = :status)
                 AND (CAST(:after AS uuid) IS NULL OR id < :after)
                 ORDER BY id DESC LIMIT :limit
                """)
                .param("q", q).param("role", role).param("status", status)
                .param("after", after).param("limit", limit).query(AdminAccountRepository::map).list();
    }

    public Optional<AdminAccount> find(UUID id, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(AdminAccountRepository::map).optional();
    }

    /** Tuần tự hóa các thay đổi quyền/khóa để bảo vệ ADMIN hoạt động cuối cùng. */
    public void lockAdministration() {
        jdbc.sql("SELECT pg_advisory_xact_lock(86420161116)").query((rs, row) -> true).single();
    }

    public long activeAdmins() {
        return jdbc.sql("SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND NOT admin_locked")
                .query(Long.class).single();
    }

    public void update(UUID id, String role, String status, boolean locked, boolean resetLock, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE users SET role = :role, status = :status, admin_locked = :locked,
                    locked_until = CASE WHEN :resetLock THEN NULL ELSE locked_until END,
                    auth_version = auth_version + 1, updated_at = :now WHERE id = :id
                """)
                .param("id", id).param("role", role).param("status", status)
                .param("locked", locked).param("resetLock", resetLock).param("now", now).update();
        jdbc.sql("UPDATE sessions SET revoked_at = :now WHERE user_id = :id AND revoked_at IS NULL")
                .param("id", id).param("now", now).update();
    }

    public void audit(UUID actor, AdminAccount before, AdminAccount after, String reason, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, entity, entity_id, reason, at, before, after)
                VALUES (:actor, 'UPDATE_ACCOUNT', 'users', :id, :reason, :now,
                    jsonb_build_object('role', CAST(:oldRole AS text), 'status', CAST(:oldStatus AS text),
                                       'admin_locked', CAST(:oldLocked AS boolean)),
                    jsonb_build_object('role', CAST(:newRole AS text), 'status', CAST(:newStatus AS text),
                                       'admin_locked', CAST(:newLocked AS boolean)))
                """)
                .param("actor", actor).param("id", before.getId()).param("reason", reason).param("now", now)
                .param("oldRole", before.getRole().getValue()).param("oldStatus", before.getStatus().getValue())
                .param("oldLocked", before.getAdminLocked()).param("newRole", after.getRole().getValue())
                .param("newStatus", after.getStatus().getValue()).param("newLocked", after.getAdminLocked()).update();
    }

    private static AdminAccount map(ResultSet rs, int row) throws SQLException {
        return new AdminAccount().id(rs.getObject("id", UUID.class)).email(rs.getString("email"))
                .phone(rs.getString("phone")).fullName(rs.getString("full_name"))
                .role(AdminAccount.RoleEnum.fromValue(rs.getString("role")))
                .status(AdminAccount.StatusEnum.fromValue(rs.getString("status")))
                .adminLocked(rs.getBoolean("admin_locked")).hasPassword(rs.getBoolean("has_password"))
                .lockedUntil(rs.getObject("locked_until", OffsetDateTime.class))
                .createdAt(rs.getObject("created_at", OffsetDateTime.class));
    }
}
