package vingo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AttendeeProfileMigrationTest {
    @Test
    void upgradeFromIdentityCleanup_allowsProfilesForExistingAccountsAndEnforcesConstraints() {
        try (var db = new PostgreSQLContainer("postgres:18-alpine")) {
            db.start();
            var config = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .placeholders(Map.of("allowDestructiveReset", "false"));
            var previous = config.target("20261125.1800").load();
            previous.migrate();
            var jdbc = JdbcClient.create(previous.getConfiguration().getDataSource());
            UUID owner = jdbc.sql("""
                    INSERT INTO user_account (full_name, email, password_hash)
                    VALUES ('Buyer', 'buyer@example.invalid', 'fixture') RETURNING id
                    """).query(UUID.class).single();

            var upgraded = config.target("20261125.1900").load();
            assertThat(upgraded.migrate().migrationsExecuted).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM attendee_profile").query(Integer.class).single()).isZero();
            String insert = "INSERT INTO attendee_profile (user_id, full_name, email, phone) VALUES (?, ?, ?, ?)";
            UUID id = jdbc.sql(insert + " RETURNING id").params(owner, "First", null, null).query(UUID.class).single();
            assertThat(id.version()).isEqualTo(7);
            assertThat(jdbc.sql("SELECT created_at = updated_at FROM attendee_profile WHERE id = ?")
                    .param(id).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM pg_indexes WHERE tablename = 'attendee_profile'
                        AND indexdef LIKE '%(user_id, id)'
                    """).query(Integer.class).single()).isOne();
            assertThatThrownBy(() -> jdbc.sql(insert).params(owner, " padded ", null, null).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql(insert).params(owner, "First", "Upper@example.invalid", null).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql(insert).params(owner, "First", null, "invalid").update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), "First", null, null).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
