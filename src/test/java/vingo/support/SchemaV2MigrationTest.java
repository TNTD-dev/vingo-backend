package vingo.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class SchemaV2MigrationTest {

    private FluentConfiguration migrations(PostgreSQLContainer db) {
        return Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .placeholders(Map.of("allowDestructiveReset", "false"));
    }

    @Test
    void emptyDatabaseMigratesAndLoadsBothSeedsInOrder() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:18-alpine").withDatabaseName("vingo_admin_ui_v2")) {
            db.start();
            migrations(db).load().migrate();
            for (String seed : new String[]{"seed.sql", "seed-admin-ui.sql"}) {
                String script = "/tmp/" + seed;
                db.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts", seed).toAbsolutePath()), script);
                var load = db.execInContainer("psql", "-X", "-U", db.getUsername(), "-d", db.getDatabaseName(),
                        "-v", "ON_ERROR_STOP=1", "-f", script);
                assertThat(load.getExitCode()).as("psql %s", seed).isZero();
            }
        }
    }

    @Test
    void populatedV1DatabaseStopsBeforeDroppingData() throws Exception {
        try (var db = new PostgreSQLContainer("postgres:18-alpine")) {
            db.start();
            migrations(db).target("20261118.1600").load().migrate();
            try (var connection = db.createConnection(""); var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO users (email, full_name, status)
                        VALUES ('migration-guard@example.invalid', 'Migration guard', 'DELETED')
                        """);
                assertThatThrownBy(() -> migrations(db).load().migrate()).isInstanceOf(FlywayException.class)
                        .hasStackTraceContaining("Dừng reset schema v2");
                try (var rows = statement.executeQuery("SELECT count(*) FROM users WHERE full_name = 'Migration guard'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isOne();
                }
            }
        }
    }
}
