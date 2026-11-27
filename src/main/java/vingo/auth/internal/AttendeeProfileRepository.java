package vingo.auth.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.api.model.AttendeeProfile;
import vingo.api.model.AttendeeRequest;

@Repository
public class AttendeeProfileRepository {
    private static final String COLUMNS = "id, full_name, email, phone, created_at, updated_at";
    private final JdbcClient jdbc;

    public AttendeeProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockLiveOwner(UUID userId) {
        return jdbc.sql("SELECT id FROM user_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE")
                .param(userId).query(UUID.class).optional().isPresent();
    }

    public int count(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM attendee_profile WHERE user_id = ?")
                .param(userId).query(Integer.class).single();
    }

    public List<AttendeeProfile> list(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attendee_profile WHERE user_id = ? ORDER BY created_at, id")
                .param(userId).query(AttendeeProfile.class).list();
    }

    public Optional<AttendeeProfile> find(UUID userId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attendee_profile WHERE user_id = ? AND id = ?")
                .params(userId, id).query(AttendeeProfile.class).optional();
    }

    public AttendeeProfile insert(UUID userId, AttendeeRequest details, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO attendee_profile (user_id, full_name, email, phone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING %s
                """.formatted(COLUMNS)).params(userId, details.getFullName(), details.getEmail(), details.getPhone(), now, now)
                .query(AttendeeProfile.class).single();
    }

    public Optional<AttendeeProfile> replace(UUID userId, UUID id, AttendeeRequest details, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE attendee_profile SET full_name = ?, email = ?, phone = ?, updated_at = ?
                WHERE user_id = ? AND id = ? RETURNING %s
                """.formatted(COLUMNS)).params(details.getFullName(), details.getEmail(), details.getPhone(), now, userId, id)
                .query(AttendeeProfile.class).optional();
    }

    public boolean delete(UUID userId, UUID id) {
        return jdbc.sql("DELETE FROM attendee_profile WHERE user_id = ? AND id = ?").params(userId, id).update() == 1;
    }
}
