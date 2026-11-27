package vingo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Uses an isolated database like SchemaV2MigrationTest so earlier schemas can be populated safely. */
class IdentityCleanupMigrationTest {

    private FluentConfiguration migrations(PostgreSQLContainer db) {
        return Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .target("20261125.1800")
                .placeholders(Map.of("allowDestructiveReset", "false"));
    }

    @Test
    void populatedSchema_preservesEmailOtpsAndAccounts_removesPhoneOtpsAndBackfillsTargetEmail() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:18-alpine")) {
            db.start();
            migrations(db).target("20261125.1710").load().migrate();
            try (var connection = db.createConnection("")) {
                seedLegacyIdentity(connection);
                String expectedAccounts = scalar(connection, """
                        SELECT jsonb_agg(to_jsonb(u) - 'phone_verified_at' ORDER BY id)::text FROM user_account u
                        """);
                String expectedOtps = scalar(connection, """
                        SELECT jsonb_agg((to_jsonb(o) - 'phone') || jsonb_build_object('target_email', u.email)
                            ORDER BY o.id)::text
                        FROM otp_verification o JOIN user_account u ON u.id = o.user_id WHERE purpose <> 'PHONE'
                        """);

                var migrated = migrations(db).load();
                migrated.migrate();

                assertThat(migrated.info().current().getVersion().toString()).isEqualTo("20261125.1800");
                assertThat(scalar(connection, "SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM user_account u"))
                        .isEqualTo(expectedAccounts);
                assertThat(scalar(connection, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM otp_verification o"))
                        .isEqualTo(expectedOtps);
                assertThat(scalar(connection, "SELECT count(*) FROM otp_verification")).isEqualTo("3");
                assertThat(scalar(connection, "SELECT enum_range(NULL::otp_purpose)::text")).isEqualTo("{VERIFY,RESET}");
                assertThat(scalar(connection, """
                        SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
                            AND ((table_name = 'user_account' AND column_name = 'phone_verified_at')
                              OR (table_name = 'otp_verification' AND column_name = 'phone'))
                        """)).isEqualTo("0");
                assertThat(scalar(connection, """
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'otp_verification' AND column_name = 'target_email'
                        """)).isEqualTo("NO");
                assertThat(scalar(connection, """
                        SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                            AND indexname IN ('user_account_verified_phone_uk', 'otp_verification_phone_idx')
                        """)).isEqualTo("0");
                assertThat(scalar(connection, """
                        SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                            AND indexname IN ('otp_verification_active_idx', 'otp_verification_client_idx')
                        """)).isEqualTo("2");
                assertThat(scalar(connection, "SELECT count(*) FROM user_account WHERE phone = '+84912345678'"))
                        .isEqualTo("2");
                execute(connection, """
                        INSERT INTO user_account (email, full_name, phone, password_hash)
                        VALUES ('third@example.invalid', 'Shared contact', '+84912345678', 'fixture')
                        """);
            }
        }
    }

    @Test
    void invalidAccounts_stopMigrationWithViolationCounts_andLeaveAllRowsAndSchemaUntouched() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:18-alpine")) {
            db.start();
            migrations(db).target("20261125.1710").load().migrate();
            try (var connection = db.createConnection("")) {
                seedLegacyIdentity(connection);
                for (String violation : new String[]{"google_email_unverified", "admin_without_password", "deleted_personal_data"}) {
                    execute(connection, """
                            INSERT INTO user_account (email, full_name, password_hash, email_verified_at)
                            VALUES ('invalid.one@example.invalid', 'Invalid one', 'fixture', now()),
                                   ('invalid.two@example.invalid', 'Invalid two', 'fixture', now())
                            """);
                    String invalidFields = switch (violation) {
                        case "google_email_unverified" -> "google_sub = id::text, email_verified_at = NULL";
                        case "admin_without_password" -> "role = 'ADMIN', google_sub = id::text, password_hash = NULL";
                        case "deleted_personal_data" -> "deleted_at = now()";
                        default -> throw new IllegalArgumentException(violation);
                    };
                    execute(connection, "UPDATE user_account SET " + invalidFields + " WHERE email LIKE 'invalid.%'");
                    String beforeAccounts = scalar(connection, "SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM user_account u");
                    String beforeOtps = scalar(connection, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM otp_verification o");

                    assertThatThrownBy(() -> migrations(db).load().migrate()).as(violation)
                            .isInstanceOf(FlywayException.class)
                            .hasStackTraceContaining("Identity cleanup blocked")
                            .hasStackTraceContaining(violation + "=2");

                    assertThat(scalar(connection, "SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM user_account u"))
                            .as("accounts after %s", violation).isEqualTo(beforeAccounts);
                    assertThat(scalar(connection, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM otp_verification o"))
                            .as("OTPs including PHONE after %s", violation).isEqualTo(beforeOtps);
                    assertThat(scalar(connection, "SELECT enum_range(NULL::otp_purpose)::text")).isEqualTo("{VERIFY,RESET,PHONE}");
                    assertThat(scalar(connection, """
                            SELECT count(*) FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'otp_verification' AND column_name = 'target_email'
                            """)).isEqualTo("0");
                    assertThat(scalar(connection, """
                            SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                                AND indexname IN ('user_account_verified_phone_uk', 'otp_verification_phone_idx')
                            """)).isEqualTo("2");
                    assertThat(migrations(db).load().info().current().getVersion().toString()).isEqualTo("20261125.1710");
                    execute(connection, "DELETE FROM user_account WHERE email LIKE 'invalid.%'");
                }
                // A blocked migration leaves no failed history entry requiring repair before retry.
                migrations(db).load().migrate();
            }
        }
    }

    private void seedLegacyIdentity(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO user_account (email, full_name, password_hash, email_verified_at, phone, phone_verified_at)
                VALUES ('first@example.invalid', 'First', 'fixture', now(), '+84912345678', now()),
                       ('second@example.invalid', 'Second', 'fixture', NULL, '+84912345678', NULL)
                """);
        execute(connection, """
                INSERT INTO otp_verification (user_id, purpose, phone, client_key, code_hash, attempts,
                    delivered, sent_at, expires_at, consumed_at)
                SELECT u.id, v.purpose::otp_purpose, CASE WHEN v.purpose = 'PHONE' THEN u.phone END,
                    gen_random_uuid(), 'fixture-' || v.label, v.attempts, v.delivered,
                    now() - interval '1 minute', now() + interval '4 minutes',
                    CASE WHEN v.consumed THEN now() END
                FROM (VALUES ('VERIFY', 'active', 0, true, false, 'second@example.invalid'),
                             ('VERIFY', 'consumed', 2, true, true, 'first@example.invalid'),
                             ('RESET', 'undelivered', 7, false, false, 'first@example.invalid'),
                             ('PHONE', 'legacy', 0, true, false, 'first@example.invalid'))
                    AS v(purpose, label, attempts, delivered, consumed, email)
                JOIN user_account u ON u.email = v.email
                """);
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
