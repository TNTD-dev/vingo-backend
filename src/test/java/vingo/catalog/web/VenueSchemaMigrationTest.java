package vingo.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vingo.support.IntegrationTest;
import vingo.support.TestcontainersConfig;

/** Chạy migration sân v2 bằng Flyway trong schema riêng, dừng ở từng version để kiểm tra chuyển đổi. */
class VenueSchemaMigrationTest extends IntegrationTest {
    private final DataSource migrationSource;

    VenueSchemaMigrationTest() {
        PostgreSQLContainer postgres = TestcontainersConfig.postgres();
        migrationSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(migrationSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target).load();
    }

    @Test
    void migration_createsFreshSchemaWithBothPendingFiles_andRerunIsNoOp() throws Exception {
        String schema = "venue_v2_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var runner = flyway(schema, "20261116.1910");
            assertThat(runner.migrate().migrationsExecuted).isGreaterThan(2);
            assertThat(runner.validateWithResult().validationSuccessful).isTrue();
            assertThat(runner.migrate().migrationsExecuted).isZero();
            try (var connection = migrationSource.getConnection()) {
                connection.setSchema(schema);
                var isolated = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                assertThat(isolated.sql("SELECT count(*) FROM venue").query(Long.class).single()).isZero();
                assertThat(isolated.sql("SELECT to_regclass(:table) IS NULL").param("table", schema + ".hall")
                        .query(Boolean.class).single()).isTrue();
                assertThat(isolated.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema=:schema AND table_name IN ('venue_overview','venue_gate','venue_overview_area','venue_import_session','venue_import_file')")
                        .param("schema", schema).query(Long.class).single()).isEqualTo(5);
            }
        } finally {
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("public");
    }

    @Test
    void migration_preservesPublishedLegacyIdsAndShowtimeLink_withoutGuessingLayoutType() throws Exception {
        String schema = "venue_v2_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "20261116.1800").migrate();
            try (var connection = migrationSource.getConnection()) {
                connection.setSchema(schema);
                var isolated = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID venue = isolated.sql("INSERT INTO venue(name,type) VALUES ('Sân cũ','STADIUM') RETURNING id").query(UUID.class).single();
                UUID hall = isolated.sql("INSERT INTO hall(venue_id,name) VALUES (:id,'Sân chính') RETURNING id")
                        .param("id", venue).query(UUID.class).single();
                UUID layout = isolated.sql("INSERT INTO seat_layout(hall_id,name,status,published_at) VALUES (:id,'Concert hoặc bóng đá chưa phân loại','PUBLISHED',now()) RETURNING id")
                        .param("id", hall).query(UUID.class).single();
                Long version = isolated.sql("SELECT version FROM seat_layout WHERE id=:id").param("id", layout).query(Long.class).single();
                UUID event = isolated.sql("INSERT INTO event(title,category) VALUES ('Sự kiện cũ','CONCERT') RETURNING id").query(UUID.class).single();
                UUID show = isolated.sql("""
                        INSERT INTO showtime(event_id,hall_id,seat_layout_id,starts_at,ends_at,sale_opens_at,sale_closes_at)
                        VALUES (:event,:hall,:layout,now()+interval '7 days',now()+interval '8 days',now(),now()+interval '6 days') RETURNING id
                        """).param("event", event).param("hall", hall).param("layout", layout).query(UUID.class).single();
                assertThat(flyway(schema, "20261116.1910").migrate().migrationsExecuted).isEqualTo(2);
                assertThat(isolated.sql("SELECT venue_id FROM seat_layout WHERE id=:id").param("id", layout).query(UUID.class).single()).isEqualTo(venue);
                assertThat(isolated.sql("SELECT version FROM seat_layout WHERE id=:id").param("id", layout).query(Long.class).single()).isEqualTo(version);
                assertThat(isolated.sql("SELECT layout_schema_version=1 AND layout_type IS NULL AND overview_id IS NULL AND status='PUBLISHED' FROM seat_layout WHERE id=:id")
                        .param("id", layout).query(Boolean.class).single()).isTrue();
                assertThat(isolated.sql("SELECT seat_layout_id FROM showtime WHERE id=:id").param("id", show).query(UUID.class).single()).isEqualTo(layout);
            }
        } finally {
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }
}
