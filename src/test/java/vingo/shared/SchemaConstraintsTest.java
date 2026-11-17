package vingo.shared;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import vingo.support.IntegrationTest;

/** Chốt chặn cuối ở DB chống bán trùng (lớp 2 và 4 trong tài liệu kiến trúc). */
class SchemaConstraintsTest extends IntegrationTest {

    private UUID showtimeId;
    private UUID zoneId;
    private UUID seatId;

    @BeforeEach
    void seedCatalog() {
        var showtime=catalog.onSaleShowtime();
        showtimeId=showtime.id();zoneId=showtime.event().seatedZoneId();seatId=showtime.event().seat(1);

    }

    @Test
    void zoneStock_rejectsOversell_whenSoldPlusHeldExceedsCapacity() {
        jdbc.sql("INSERT INTO showtime_zone_stock (showtime_id, zone_id, capacity, sold, held) VALUES (:s, :z, 5, 3, 2)")
                .param("s", showtimeId).param("z", zoneId).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE showtime_zone_stock SET held = held + 1 WHERE showtime_id = :s")
                .param("s", showtimeId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ticket_rejectsSecondLiveTicket_forSameSeatAndShowtime() {
        UUID item = orderItem();
        insertTicket(item, 1);

        assertThatThrownBy(() -> insertTicket(item, 2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orders_rejectsSecondPendingOrder_forSameUserAndShowtime() {
        UUID user = id("INSERT INTO users (email, full_name, password_hash) VALUES (gen_random_uuid() || '@t.vn', 'U', 'x') RETURNING id");
        insertPendingOrder(user);

        assertThatThrownBy(() -> insertPendingOrder(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID orderItem() {
        UUID user = id("INSERT INTO users (email, full_name, password_hash) VALUES (gen_random_uuid() || '@t.vn', 'U', 'x') RETURNING id");
        UUID order = insertPendingOrder(user);
        UUID event = jdbc.sql("SELECT event_id FROM showtime WHERE id = :s").param("s", showtimeId).query(UUID.class).single();
        UUID tier = jdbc.sql("SELECT id FROM price_tier WHERE event_id=:e AND name='VIP'")
                .param("e", event).query(UUID.class).single();
        return jdbc.sql("""
                        INSERT INTO order_item (order_id, zone_id, seat_id, price_tier_id, quantity, unit_price)
                        VALUES (:o, :z, :seat, :t, 1, 500000) RETURNING id
                        """)
                .param("o", order).param("z", zoneId).param("seat", seatId).param("t", tier)
                .query(UUID.class).single();
    }

    private UUID insertPendingOrder(UUID user) {
        return jdbc.sql("""
                        INSERT INTO orders (code, user_id, showtime_id, total_amount, expires_at)
                        VALUES (upper(substr(md5(random()::text), 1, 6)), :u, :s, 500000, now() + interval '10 minutes')
                        RETURNING id
                        """)
                .param("u", user).param("s", showtimeId).query(UUID.class).single();
    }

    private void insertTicket(UUID item, int seq) {
        jdbc.sql("""
                        INSERT INTO ticket (order_item_id, seq, showtime_id, zone_id, seat_id, code, qr_token)
                        VALUES (:i, :seq, :s, :z, :seat, gen_random_uuid()::text, gen_random_uuid()::text)
                        """)
                .param("i", item).param("seq", seq).param("s", showtimeId).param("z", zoneId).param("seat", seatId)
                .update();
    }

    private UUID id(String sql) {
        return jdbc.sql(sql).query(UUID.class).single();
    }
}
