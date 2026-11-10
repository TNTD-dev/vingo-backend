package vn.vingo.shared;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import vn.vingo.support.IntegrationTest;

/** Chốt chặn cuối ở DB chống bán trùng (lớp 2 và 4 trong tài liệu kiến trúc). */
class SchemaConstraintsTest extends IntegrationTest {

    private UUID showtimeId;
    private UUID zoneId;
    private UUID seatId;

    @BeforeEach
    void seedCatalog() {
        UUID venue = id("INSERT INTO venue (name, type) VALUES ('Sân test', 'STADIUM') RETURNING id");
        UUID hall = jdbc.sql("INSERT INTO hall (venue_id, name) VALUES (:v, :n) RETURNING id")
                .param("v", venue).param("n", "Hall " + UUID.randomUUID()).query(UUID.class).single();
        UUID layout = jdbc.sql("INSERT INTO seat_layout (hall_id) VALUES (:h) RETURNING id")
                .param("h", hall).query(UUID.class).single();
        zoneId = jdbc.sql("INSERT INTO zone (seat_layout_id, name, kind, capacity) VALUES (:l, 'A', 'SEATED', 10) RETURNING id")
                .param("l", layout).query(UUID.class).single();
        seatId = jdbc.sql("""
                        INSERT INTO seat (zone_id, seat_type_id, row_label, number, x, y)
                        VALUES (:z, (SELECT id FROM seat_type WHERE code = 'STANDARD'), 'A', 1, 0, 0) RETURNING id
                        """)
                .param("z", zoneId).query(UUID.class).single();
        UUID event = id("INSERT INTO event (title, category) VALUES ('Trận test', 'FOOTBALL') RETURNING id");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        showtimeId = jdbc.sql("""
                        INSERT INTO showtime (event_id, hall_id, seat_layout_id, starts_at, ends_at, sale_opens_at, sale_closes_at, status)
                        VALUES (:e, :h, :l, :s, :s + interval '2 hours', :s - interval '30 days', :s, 'SCHEDULED') RETURNING id
                        """)
                .param("e", event).param("h", hall).param("l", layout).param("s", start)
                .query(UUID.class).single();
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
        UUID tier = jdbc.sql("INSERT INTO price_tier (event_id, name, price) VALUES (:e, 'VIP', 500000) RETURNING id")
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
