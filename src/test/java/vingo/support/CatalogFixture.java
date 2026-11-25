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
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Dựng khu A có 10 ghế, ghế 9–10 là ghế đôi, hạng VIP 500.000đ; khu GA có sức chứa mặc định 5,
 * hạng GA 200.000đ. Mỗi lần gọi event() hoặc onSaleShowtime() tạo catalog mới vì DB không dọn giữa các test.
 */
public class CatalogFixture {

    public static final long VIP_PRICE = 500_000;
    public static final long GA_PRICE = 200_000;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger(10_000_000);

    private final JdbcClient jdbc;
    private final Clock clock;

    public CatalogFixture(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Event event() {
        return event(EventSpec.defaults());
    }

    public Event event(EventSpec spec) {return event(spec,false);}
    public Event eventWithTwoSeatedZones() {return event(EventSpec.defaults(),true);}
    private Event event(EventSpec spec, boolean splitZones) {
        UUID venueId = jdbc.sql("""
                        INSERT INTO venue (name, city)
                        VALUES ('Test venue', :city) RETURNING id
                        """)
                .param("city", spec.city()).query(UUID.class).single();
        UUID layoutId = VenueSchemaFixture.draft(jdbc, venueId);
        UUID seatedZoneId = jdbc.sql("""
                        INSERT INTO zone (seat_map_id, code, name, kind, capacity)
                        VALUES (:layout, 'A', 'A', 'SEATED', NULL) RETURNING id
                        """)
                .param("layout", layoutId).query(UUID.class).single();
        UUID standingZoneId = jdbc.sql("""
                        INSERT INTO zone (seat_map_id, code, name, kind, capacity)
                        VALUES (:layout, 'GA', 'GA', 'STANDING', :capacity) RETURNING id
                        """)
                .param("layout", layoutId).param("capacity", spec.standingCapacity()).query(UUID.class).single();

        UUID secondZone = splitZones
                ? jdbc.sql("INSERT INTO zone (seat_map_id, code, name, kind, capacity) VALUES (:id, 'B', 'B', 'SEATED', NULL) RETURNING id")
                        .param("id", layoutId).query(UUID.class).single()
                : null;
        List<UUID> seats = new ArrayList<>();
        for (int number = 1; number <= 10; number++) {
            UUID zone = splitZones && number >= 5 && number <= 8 ? secondZone : seatedZoneId;
            seats.add(jdbc.sql("""
                            INSERT INTO seat (zone_id, seat_map_id, zone_kind, row_label, number)
                            VALUES (:zone, :layout, 'SEATED', 'A', :number)
                            RETURNING id
                            """)
                    .param("zone", zone).param("layout", layoutId).param("number", number)
                    .query(UUID.class).single());
        }

        UUID eventId = jdbc.sql("""
                        INSERT INTO event (seat_map_id, title, category, status, max_tickets_per_order)
                        VALUES (:layout, :title, :category, :status::event_status, :maxTickets) RETURNING id
                        """)
                .param("layout", layoutId)
                .param("title", spec.title()).param("category", spec.category()).param("status", spec.status())
                .param("maxTickets", spec.maxTicketsPerOrder()).query(UUID.class).single();
        UUID createdBy = buyer();
        UUID vipSkuId = jdbc.sql("""
                        INSERT INTO ticket_type (event_id, name, color)
                        VALUES (:event, 'VIP', '#FF0000') RETURNING id
                        """)
                .param("event", eventId).query(UUID.class).single();
        UUID gaSkuId = jdbc.sql("""
                        INSERT INTO ticket_type (event_id, name, color)
                        VALUES (:event, 'GA', '#00FF00') RETURNING id
                        """)
                .param("event", eventId).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO ticket_type_price (ticket_type_id, price, effective_from, created_by)
                        VALUES (:vipSku, :vipPrice, :now, :createdBy), (:gaSku, :gaPrice, :now, :createdBy)
                        """)
                .param("vipSku", vipSkuId).param("vipPrice", VIP_PRICE)
                .param("gaSku", gaSkuId).param("gaPrice", GA_PRICE)
                .param("now", now().minusDays(30)).param("createdBy", createdBy).update();
        jdbc.sql("""
                        INSERT INTO event_zone (event_id, seat_map_id, zone_id, ticket_type_id)
                        VALUES (:event, :layout, :seatedZone, :vipSku), (:event, :layout, :standingZone, :gaSku)
                        """)
                .param("event", eventId).param("layout", layoutId)
                .param("seatedZone", seatedZoneId).param("vipSku", vipSkuId)
                .param("standingZone", standingZoneId).param("gaSku", gaSkuId).update();
        if (splitZones) {
            jdbc.sql("INSERT INTO event_zone (event_id, seat_map_id, zone_id, ticket_type_id) VALUES (:e, :l, :z, :p)")
                    .param("e", eventId).param("l", layoutId).param("z", secondZone).param("p", vipSkuId).update();
        }
        return new Event(eventId, venueId, layoutId, seatedZoneId, standingZoneId,
                vipSkuId, gaSkuId, List.copyOf(seats));
    }

    public Showtime showtime(Event event, Window window) {
        OffsetDateTime now = now();
        UUID showtimeId = jdbc.sql("""
                        INSERT INTO showtime (event_id, seat_map_id, starts_at, ends_at,
                                              sale_opens_at, sale_closes_at, cancelled_at)
                        VALUES (:event, :layout, :starts, :ends, :opens, :closes,
                                CASE WHEN :status = 'CANCELLED' THEN :now ELSE NULL END) RETURNING id
                        """)
                .param("event", event.id()).param("layout", event.layoutId())
                .param("starts", now.plus(window.startsIn())).param("ends", now.plus(window.endsIn()))
                .param("opens", now.plus(window.saleOpensIn())).param("closes", now.plus(window.saleClosesIn()))
                .param("status", window.status()).param("now", now).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO seat_inventory (showtime_id, seat_map_id, seat_id, zone_id, status)
                        SELECT :showtime, :layout, s.id, s.zone_id, 'AVAILABLE'::seat_status
                        FROM seat s WHERE s.seat_map_id = :layout
                        """)
                .param("showtime", showtimeId).param("layout", event.layoutId()).update();
        jdbc.sql("""
                        INSERT INTO standing_inventory (showtime_id, seat_map_id, zone_id, zone_kind, capacity)
                        SELECT :showtime, :layout, id, 'STANDING'::zone_kind, capacity
                        FROM zone WHERE id = :zone
                        """)
                .param("showtime", showtimeId).param("layout", event.layoutId())
                .param("zone", event.standingZoneId()).update();
        return new Showtime(showtimeId, event);
    }

    public Showtime onSaleShowtime() {
        return showtime(event(), Window.onSale());
    }

    public UUID buyer() {
        return jdbc.sql("""
                        INSERT INTO user_account (email, full_name, password_hash, role, email_verified_at, phone)
                        VALUES (:email, 'Test buyer', 'x', 'BUYER', :now, :phone) RETURNING id
                        """)
                .param("phone", uniquePhone()).param("now", now())
                .param("email", UUID.randomUUID() + "@test.vingo").query(UUID.class).single();
    }

    /** Sinh số E.164 không trùng giữa các fixture và luồng trong cùng lần chạy test. */
    public static String uniquePhone() {
        return "+849" + PHONE_SEQUENCE.getAndIncrement();
    }

    public UUID order(UUID userId, UUID showtimeId, String status, OffsetDateTime expiresAt) {
        String dbStatus = "REFUNDED".equals(status) ? "REJECTED" : status;
        while (true) {
            var id = jdbc.sql("""
                            INSERT INTO ticket_order (code, user_id, showtime_id, status, total_amount, expires_at,
                                                      buyer_name, buyer_email, buyer_phone, paid_at)
                            VALUES (:code, :user, :showtime, :status::order_status, 0, :expires,
                                    CASE WHEN :status = 'PAID' THEN 'Test Buyer' ELSE NULL END,
                                    CASE WHEN :status = 'PAID' THEN 'buyer@vingo.test' ELSE NULL END,
                                    CASE WHEN :status = 'PAID' THEN '+84901234567' ELSE NULL END,
                                    CASE WHEN :status = 'PAID' THEN :now ELSE NULL END)
                            ON CONFLICT (code) DO NOTHING RETURNING id
                            """)
                    .param("code", UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT))
                    .param("user", userId).param("showtime", showtimeId).param("status", dbStatus)
                    .param("expires", expiresAt).param("now", now()).query(UUID.class).optional();
            if (id.isPresent()) {
                if ("NEEDS_REVIEW".equals(dbStatus)) {
                    jdbc.sql("""
                                    INSERT INTO order_status_history (order_id, from_status, to_status, reason)
                                    VALUES (:id, 'PENDING', 'NEEDS_REVIEW', 'AMOUNT_MISMATCH')
                                    """)
                            .param("id", id.get()).update();
                }
                return id.get();
            }
        }
    }

    public void setSeat(UUID showtimeId, UUID seatId, String status, UUID orderId, OffsetDateTime heldUntil) {
        jdbc.sql("""
                        UPDATE seat_inventory SET status = :status::seat_status, order_id = :order, held_until = :heldUntil
                        WHERE showtime_id = :showtime AND seat_id = :seat
                        """)
                .param("status", status).param("order", orderId, Types.OTHER)
                .param("heldUntil", heldUntil, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("showtime", showtimeId).param("seat", seatId).update();
    }

    public void setZoneStock(UUID showtimeId, UUID zoneId, int sold, int held) {
        jdbc.sql("""
                        UPDATE standing_inventory SET sold = :sold, held = :held
                        WHERE showtime_id = :showtime AND zone_id = :zone
                        """)
                .param("sold", sold).param("held", held).param("showtime", showtimeId).param("zone", zoneId).update();
    }

    public OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record Event(UUID id, UUID venueId, UUID layoutId, UUID seatedZoneId,
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
