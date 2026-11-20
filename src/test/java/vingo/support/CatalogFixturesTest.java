package vingo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Kiểm tra fixture dựng đủ chuỗi dữ liệu catalog và booking. */
class CatalogFixturesTest extends IntegrationTest {

    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-11T03:00:00Z"));
        fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void create_buildsFullChain_forTouringEventWithUnassignedTier() {
        var created = fixtures.event().title("Tour test").city("HN")
                .tier("VIP", 2_000_000).tier("Chưa gán", 100_000)
                .showtime(s -> s.onSale().seated(10, "VIP").standing(100).sold(12))
                .showtime(s -> s.city("HCM").startsAt(clock.instant().plus(Duration.ofDays(20))).seated(5, "VIP"))
                .create();

        assertThat(created.showtimeIds()).hasSize(2);
        assertThat(count("venue")).isEqualTo(1);
        assertThat(count("seat")).isEqualTo(10);
        assertThat(count("seat_inventory")).isEqualTo(20);
        assertThat(count("event_zone")).isEqualTo(2);
        assertThat(count("sku")).isEqualTo(3); // VIP, Chưa gán, Mặc định (khu đứng)
        assertThat(jdbc.sql("SELECT count(*) FROM seat_inventory WHERE status = 'SOLD'").query(Integer.class).single())
                .isEqualTo(10);
        assertThat(jdbc.sql("SELECT sum(sold) FROM standing_inventory").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM event_zone WHERE sku_id = :t")
                .param("t", created.tierIds().get("Chưa gán")).query(Integer.class).single()).isZero();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
