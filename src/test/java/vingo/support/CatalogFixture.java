package vingo.support;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Dựng khu A có 10 ghế, ghế 9–10 là ghế đôi, hạng VIP 500.000đ; khu GA có sức chứa mặc định 5,
 * hạng GA 200.000đ. Mỗi lần gọi event() hoặc onSaleShowtime() tạo catalog mới vì DB không dọn giữa các test.
 */
public class CatalogFixture {

    public static final long VIP_PRICE = 500_000;
    public static final long GA_PRICE = 200_000;

    private final JdbcClient jdbc;
    private final Clock clock;

    public CatalogFixture(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Event event() {
        return event(EventSpec.defaults());
    }

    public Event event(EventSpec spec) {
        UUID venueId = jdbc.sql("""
                        INSERT INTO venue (name, type, city)
                        VALUES ('Test venue', 'STADIUM', :city) RETURNING id
                        """)
                .param("city", spec.city()).query(UUID.class).single();
        UUID hallId = jdbc.sql("INSERT INTO hall (venue_id, name) VALUES (:venue, 'Test hall') RETURNING id")
                .param("venue", venueId).query(UUID.class).single();
        UUID layoutId = jdbc.sql("""
                        INSERT INTO seat_layout (hall_id, status, published_at)
                        VALUES (:hall, 'PUBLISHED', :now) RETURNING id
                        """)
                .param("hall", hallId).param("now", now()).query(UUID.class).single();
        UUID seatedZoneId = jdbc.sql("""
                        INSERT INTO zone (seat_layout_id, name, kind, capacity)
                        VALUES (:layout, 'A', 'SEATED', 10) RETURNING id
                        """)
                .param("layout", layoutId).query(UUID.class).single();
        UUID standingZoneId = jdbc.sql("""
                        INSERT INTO zone (seat_layout_id, name, kind, capacity)
                        VALUES (:layout, 'GA', 'STANDING', :capacity) RETURNING id
                        """)
                .param("layout", layoutId).param("capacity", spec.standingCapacity()).query(UUID.class).single();

        List<UUID> seats = new ArrayList<>();
        for (int number = 1; number <= 10; number++) {
            seats.add(jdbc.sql("""
                            INSERT INTO seat (zone_id, seat_type_id, row_label, number, x, y)
                            VALUES (:zone, (SELECT id FROM seat_type WHERE code = :type), 'A', :number, :number, 0)
                            RETURNING id
                            """)
                    .param("zone", seatedZoneId).param("type", number >= 9 ? "COUPLE" : "STANDARD")
                    .param("number", number).query(UUID.class).single());
        }
        jdbc.sql("""
                        INSERT INTO seat_pair (seat_a_id, seat_b_id)
                        VALUES (LEAST(CAST(:a AS uuid), CAST(:b AS uuid)),
                                GREATEST(CAST(:a AS uuid), CAST(:b AS uuid)))
                        """)
                .param("a", seats.get(8)).param("b", seats.get(9)).update();

        UUID eventId = jdbc.sql("""
                        INSERT INTO event (title, category, status, max_tickets_per_order, published_at)
                        VALUES (:title, :category, :status, :maxTickets,
                                CASE WHEN :status = 'PUBLISHED' THEN :now ELSE NULL END) RETURNING id
                        """)
                .param("title", spec.title()).param("category", spec.category()).param("status", spec.status())
                .param("maxTickets", spec.maxTicketsPerOrder()).param("now", now()).query(UUID.class).single();
        UUID vipPriceTierId = jdbc.sql("""
                        INSERT INTO price_tier (event_id, name, price)
                        VALUES (:event, 'VIP', :price) RETURNING id
                        """)
                .param("event", eventId).param("price", VIP_PRICE).query(UUID.class).single();
        UUID gaPriceTierId = jdbc.sql("""
                        INSERT INTO price_tier (event_id, name, price)
                        VALUES (:event, 'GA', :price) RETURNING id
                        """)
                .param("event", eventId).param("price", GA_PRICE).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO zone_price (event_id, zone_id, price_tier_id)
                        VALUES (:event, :seatedZone, :vipTier), (:event, :standingZone, :gaTier)
                        """)
                .param("event", eventId).param("seatedZone", seatedZoneId).param("vipTier", vipPriceTierId)
                .param("standingZone", standingZoneId).param("gaTier", gaPriceTierId).update();
        return new Event(eventId, venueId, hallId, layoutId, seatedZoneId, standingZoneId,
                vipPriceTierId, gaPriceTierId, List.copyOf(seats));
    }

    public Showtime showtime(Event event, Window window) {
        OffsetDateTime now = now();
        UUID showtimeId = jdbc.sql("""
                        INSERT INTO showtime (event_id, hall_id, seat_layout_id, starts_at, ends_at,
                                              sale_opens_at, sale_closes_at, status)
                        VALUES (:event, :hall, :layout, :starts, :ends, :opens, :closes, :status) RETURNING id
                        """)
                .param("event", event.id()).param("hall", event.hallId()).param("layout", event.layoutId())
                .param("starts", now.plus(window.startsIn())).param("ends", now.plus(window.endsIn()))
                .param("opens", now.plus(window.saleOpensIn())).param("closes", now.plus(window.saleClosesIn()))
                .param("status", window.status()).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO showtime_seat (showtime_id, seat_id, status)
                        SELECT :showtime, id, 'AVAILABLE' FROM seat WHERE zone_id = :zone
                        """)
                .param("showtime", showtimeId).param("zone", event.seatedZoneId()).update();
        jdbc.sql("""
                        INSERT INTO showtime_zone_stock (showtime_id, zone_id, capacity)
                        SELECT :showtime, id, capacity FROM zone WHERE id = :zone
                        """)
                .param("showtime", showtimeId).param("zone", event.standingZoneId()).update();
        return new Showtime(showtimeId, event);
    }

    public Showtime onSaleShowtime() {
        return showtime(event(), Window.onSale());
    }

    public UUID buyer() {
        return jdbc.sql("""
                        INSERT INTO users (email, full_name, password_hash, role, status)
                        VALUES (:email, 'Test buyer', 'x', 'BUYER', 'ACTIVE') RETURNING id
                        """)
                .param("email", UUID.randomUUID() + "@test.vingo").query(UUID.class).single();
    }

    public UUID order(UUID userId, UUID showtimeId, String status, OffsetDateTime expiresAt) {
        while (true) {
            var id = jdbc.sql("""
                            INSERT INTO orders (code, user_id, showtime_id, status, total_amount, expires_at,
                                                paid_at, review_reason)
                            VALUES (:code, :user, :showtime, :status, 0, :expires,
                                    CASE WHEN :status = 'PAID' THEN :now ELSE NULL END,
                                    CASE WHEN :status = 'NEEDS_REVIEW' THEN 'AMOUNT_MISMATCH' ELSE NULL END)
                            ON CONFLICT (code) DO NOTHING RETURNING id
                            """)
                    .param("code", UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT))
                    .param("user", userId).param("showtime", showtimeId).param("status", status)
                    .param("expires", expiresAt).param("now", now()).query(UUID.class).optional();
            if (id.isPresent()) {
                return id.get();
            }
        }
    }

    public void setSeat(UUID showtimeId, UUID seatId, String status, UUID orderId, OffsetDateTime heldUntil) {
        jdbc.sql("""
                        UPDATE showtime_seat SET status = :status, order_id = :order, held_until = :heldUntil
                        WHERE showtime_id = :showtime AND seat_id = :seat
                        """)
                .param("status", status).param("order", orderId, Types.OTHER)
                .param("heldUntil", heldUntil, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("showtime", showtimeId).param("seat", seatId).update();
    }

    public void setZoneStock(UUID showtimeId, UUID zoneId, int sold, int held) {
        jdbc.sql("""
                        UPDATE showtime_zone_stock SET sold = :sold, held = :held
                        WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("sold", sold).param("held", held).param("showtime", showtimeId).param("zone", zoneId).update();
    }

    public OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record Event(UUID id, UUID venueId, UUID hallId, UUID layoutId, UUID seatedZoneId,
                        UUID standingZoneId, UUID vipPriceTierId, UUID gaPriceTierId, List<UUID> seats) {

        public UUID seat(int number) {
            return seats.get(number - 1);
        }
    }

    public record Showtime(UUID id, Event event) {}

    public record EventSpec(String title, String category, String city, String status,
                            int maxTicketsPerOrder, int standingCapacity) {

        public static EventSpec defaults() {
            return new EventSpec("Test event", "CONCERT", "City-" + UUID.randomUUID(), "PUBLISHED", 10, 5);
        }

        public EventSpec withTitle(String title) {
            return new EventSpec(title, category, city, status, maxTicketsPerOrder, standingCapacity);
        }

        public EventSpec withCategory(String category) {
            return new EventSpec(title, category, city, status, maxTicketsPerOrder, standingCapacity);
        }

        public EventSpec withCity(String city) {
            return new EventSpec(title, category, city, status, maxTicketsPerOrder, standingCapacity);
        }

        public EventSpec withStatus(String status) {
            return new EventSpec(title, category, city, status, maxTicketsPerOrder, standingCapacity);
        }

        public EventSpec withMaxTicketsPerOrder(int maxTicketsPerOrder) {
            return new EventSpec(title, category, city, status, maxTicketsPerOrder, standingCapacity);
        }

        public EventSpec withStandingCapacity(int standingCapacity) {
            return new EventSpec(title, category, city, status, maxTicketsPerOrder, standingCapacity);
        }
    }

    public record Window(String status, Duration saleOpensIn, Duration saleClosesIn,
                         Duration startsIn, Duration endsIn) {

        public static Window onSale() {
            return new Window("SCHEDULED", Duration.ofDays(-1), Duration.ofDays(7),
                    Duration.ofDays(7), Duration.ofDays(7).plusHours(2));
        }

        public static Window notOpenYet() {
            return new Window("SCHEDULED", Duration.ofDays(1), Duration.ofDays(7),
                    Duration.ofDays(7), Duration.ofDays(7).plusHours(2));
        }

        public static Window salesClosed() {
            return new Window("SCHEDULED", Duration.ofDays(-7), Duration.ofDays(-1),
                    Duration.ofDays(1), Duration.ofDays(1).plusHours(2));
        }

        public static Window finished() {
            return new Window("SCHEDULED", Duration.ofDays(-14), Duration.ofDays(-7),
                    Duration.ofDays(-7), Duration.ofDays(-7).plusHours(2));
        }

        public static Window cancelled() {
            return new Window("CANCELLED", Duration.ofDays(-1), Duration.ofDays(7),
                    Duration.ofDays(7), Duration.ofDays(7).plusHours(2));
        }

        public static Window draft() {
            return new Window("DRAFT", Duration.ofDays(-1), Duration.ofDays(7),
                    Duration.ofDays(7), Duration.ofDays(7).plusHours(2));
        }
    }
}
