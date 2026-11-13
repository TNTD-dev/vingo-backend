package vingo.catalog.web;

import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vingo.support.IntegrationTest;

class SeatLayoutNameMigrationTest extends IntegrationTest {
    private final DataSource migrationDataSource;

    @Autowired
    SeatLayoutNameMigrationTest(PostgreSQLContainer postgres) {
        // Không đổi search_path của pool ứng dụng: mỗi connection migration là connection vật lý riêng.
        this.migrationDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void migration_backfillsPublishedLayoutNameWithoutChangingLinksAndEnforcesConstraints() throws Exception {
        String schema = "ev3_migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(migrationDataSource).schemas(schema).defaultSchema(schema)
                    .target("20261112.1500").load().migrate();
            try (var connection = migrationDataSource.getConnection()) {
                connection.setSchema(schema);
                var isolated = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID venue = isolated.sql("INSERT INTO venue (name, type, city) VALUES ('Sân cũ','STADIUM','HN') RETURNING id")
                        .query(UUID.class).single();
                UUID hall = isolated.sql("INSERT INTO hall (venue_id, name) VALUES (:id,'Sân chính') RETURNING id")
                        .param("id", venue).query(UUID.class).single();
                UUID layout = isolated.sql("INSERT INTO seat_layout (hall_id,status,published_at) VALUES (:id,'PUBLISHED',now()) RETURNING id")
                        .param("id", hall).query(UUID.class).single();
                long version = isolated.sql("SELECT version FROM seat_layout WHERE id = :id")
                        .param("id", layout).query(Long.class).single();
                UUID event = isolated.sql("INSERT INTO event (title,category) VALUES ('Sự kiện cũ','FOOTBALL') RETURNING id")
                        .query(UUID.class).single();
                UUID showtime = isolated.sql("""
                        INSERT INTO showtime (event_id,hall_id,seat_layout_id,starts_at,ends_at,
                                              sale_opens_at,sale_closes_at,status)
                        VALUES (:event,:hall,:layout,now()+interval '7 days',now()+interval '8 days',
                                now()+interval '1 day',now()+interval '6 days','DRAFT') RETURNING id
                        """).param("event", event).param("hall", hall).param("layout", layout)
                        .query(UUID.class).single();
                Flyway.configure().dataSource(migrationDataSource).schemas(schema).defaultSchema(schema).load().migrate();
                assertThat(isolated.sql("SELECT name FROM seat_layout WHERE id = :id")
                        .param("id", layout).query(String.class).single()).isEqualTo("Sơ đồ v" + version);
                assertThat(isolated.sql("SELECT seat_layout_id FROM showtime WHERE id = :id")
                        .param("id", showtime).query(UUID.class).single()).isEqualTo(layout);
                assertThat(isolated.sql("SELECT status FROM seat_layout WHERE id = :id")
                        .param("id", layout).query(String.class).single()).isEqualTo("PUBLISHED");
                assertThat(isolated.sql("SELECT version FROM seat_layout WHERE id = :id")
                        .param("id", layout).query(Long.class).single()).isEqualTo(version);
                assertThatThrownBy(() -> isolated.sql("UPDATE seat_layout SET name = '   ' WHERE id = :id")
                        .param("id", layout).update()).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> isolated.sql("UPDATE seat_layout SET name = NULL WHERE id = :id")
                        .param("id", layout).update()).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> isolated.sql("UPDATE seat_layout SET name = :name WHERE id = :id")
                        .param("id", layout).param("name", "A".repeat(201)).update())
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        } finally {
            // Chỉ xóa schema test có tên sinh từ UUID, không chạm schema public của bộ test chung.
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("public");
        assertThat(jdbc.sql("SELECT count(*) FROM venue").query(Long.class).single()).isNotNegative();
    }
}
