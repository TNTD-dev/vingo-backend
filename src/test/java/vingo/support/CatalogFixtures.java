package vingo.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Dựng dữ liệu catalog và booking cho test bằng SQL thật:
 * venue → hall → seat_layout (PUBLISHED) → zone → seat → event → showtime → price_tier → zone_price
 * → showtime_seat / showtime_zone_stock.
 *
 * <p>Ví dụ:
 * <pre>{@code
 * fixtures.event().title("Sơn Tùng M-TP Sky Tour").city("HN")
 *         .tier("VIP", 2_000_000).tier("Chưa gán", 100_000)
 *         .showtime(s -> s.startsAt(clock.instant().plus(Duration.ofDays(10))).onSale().seated(10, "VIP").standing(100))
 *         .create();
 * }</pre>
 *
 * <p>Mốc giờ mặc định tính từ {@link Clock} truyền vào. Container Postgres dùng chung giữa các test
 * nên gọi {@link #reset()} ở {@code @BeforeEach} nếu test đếm hoặc liệt kê toàn bộ sự kiện.
 */
public final class CatalogFixtures {

    private static final String DEFAULT_TIER = "Mặc định";
    private static final long DEFAULT_PRICE = 500_000;

    private final JdbcClient jdbc;
    private final Clock clock;

    public CatalogFixtures(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Xoá toàn bộ dữ liệu catalog, booking và các bảng phụ thuộc. Giữ nguyên {@code seat_type} và {@code users}. */
    public void reset() {
        jdbc.sql("""
                TRUNCATE showtime_zone_stock, showtime_seat, zone_price, price_tier, showtime, event,
                         seat_pair, seat, zone, seat_layout, hall, venue CASCADE
                """).update();
    }

    public EventBuilder event() {
        return new EventBuilder();
    }

    /** Kết quả {@link EventBuilder#create()}: id các dòng đã tạo, tra theo tên hạng giá. */
    public record CreatedEvent(UUID id, List<UUID> showtimeIds, Map<String, UUID> tierIds) {

        public UUID showtimeId() {
            return showtimeIds.getFirst();
        }
    }

    private record ZoneSpec(String kind, String name, int size, String tier, int perRow) {
    }

    // ------------------------------------------------------------------ Showtime

    public final class ShowtimeBuilder {

        private Instant startsAt = clock.instant().plus(Duration.ofDays(7));
        private Duration length = Duration.ofHours(2);
        private String status = "SCHEDULED";
        private Instant saleOpensAt;
        private Instant saleClosesAt;
        private String city;
        private String venueName;
        private int sold;
        private final List<ZoneSpec> zones = new ArrayList<>();

        private ShowtimeBuilder() {
        }

        public ShowtimeBuilder startsAt(Instant startsAt) {
            this.startsAt = startsAt;
            return this;
        }

        public ShowtimeBuilder length(Duration length) {
            this.length = length;
            return this;
        }

        public ShowtimeBuilder draft() {
            this.status = "DRAFT";
            return this;
        }

        public ShowtimeBuilder scheduled() {
            this.status = "SCHEDULED";
            return this;
        }

        public ShowtimeBuilder cancelled() {
            this.status = "CANCELLED";
            return this;
        }

        /** Đang mở bán: mở từ hôm qua, đóng lúc suất bắt đầu. Cần {@code startsAt} sau now. */
        public ShowtimeBuilder onSale() {
            this.saleOpensAt = clock.instant().minus(Duration.ofDays(1));
            this.saleClosesAt = null;
            return this;
        }

        /** Chưa mở bán: mở sau now 1 ngày. Cần {@code startsAt} sau mốc đó. */
        public ShowtimeBuilder salesNotOpen() {
            this.saleOpensAt = clock.instant().plus(Duration.ofDays(1));
            this.saleClosesAt = null;
            return this;
        }

        /** Đã đóng bán: mở từ 30 ngày trước, đóng cách now 1 giờ. */
        public ShowtimeBuilder salesClosed() {
            this.saleOpensAt = clock.instant().minus(Duration.ofDays(30));
            this.saleClosesAt = clock.instant().minus(Duration.ofHours(1));
            return this;
        }

        public ShowtimeBuilder saleWindow(Instant opensAt, Instant closesAt) {
            this.saleOpensAt = opensAt;
            this.saleClosesAt = closesAt;
            return this;
        }

        /** Mã thành phố của sân tổ chức suất này (mặc định lấy của sự kiện). */
        public ShowtimeBuilder city(String city) {
            this.city = city;
            return this;
        }

        public ShowtimeBuilder venue(String venueName) {
            this.venueName = venueName;
            return this;
        }

        /** Khu ghế ngồi dùng hạng giá mặc định. */
        public ShowtimeBuilder seated(int seats) {
            return seated(seats, null);
        }

        /** Khu ghế ngồi gán hạng giá {@code tier}; {@code null} là hạng mặc định. */
        public ShowtimeBuilder seated(int seats, String tier) {
            zones.add(new ZoneSpec("SEATED", "Ghế ngồi " + (zones.size() + 1), seats, tier, 0));
            return this;
        }

        /** Khu ghế ngồi nhiều hàng: hàng A, B, C... mỗi hàng {@code perRow} ghế đánh số từ 1. */
        public ShowtimeBuilder seatedRows(int rows, int perRow, String tier) {
            zones.add(new ZoneSpec("SEATED", "Ghế ngồi " + (zones.size() + 1), rows * perRow, tier, perRow));
            return this;
        }

        public ShowtimeBuilder standing(int capacity) {
            return standing(capacity, null);
        }

        public ShowtimeBuilder standing(int capacity, String tier) {
            zones.add(new ZoneSpec("STANDING", "Khu đứng " + (zones.size() + 1), capacity, tier, 0));
            return this;
        }

        /** Số vé đã bán của suất: ghế ngồi trước, rồi tới khu đứng. */
        public ShowtimeBuilder sold(int tickets) {
            this.sold = tickets;
            return this;
        }
    }

    // ------------------------------------------------------------------ Event

    public final class EventBuilder {

        private String title = "Sự kiện test";
        private String description;
        private String category = "CONCERT";
        private String status = "PUBLISHED";
        private String posterUrl;
        private String city = "HCM";
        private String venueName;
        private long defaultPrice = DEFAULT_PRICE;
        private final Map<String, Long> tiers = new LinkedHashMap<>();
        private final List<ShowtimeBuilder> showtimes = new ArrayList<>();

        private EventBuilder() {
        }

        public EventBuilder title(String title) {
            this.title = title;
            return this;
        }

        public EventBuilder description(String description) {
            this.description = description;
            return this;
        }

        /** FOOTBALL, CONCERT, THEATER hoặc OTHER. */
        public EventBuilder category(String category) {
            this.category = category;
            return this;
        }

        /** DRAFT, PUBLISHED, ENDED hoặc CANCELLED. Mặc định PUBLISHED. */
        public EventBuilder status(String status) {
            this.status = status;
            return this;
        }

        public EventBuilder draft() {
            return status("DRAFT");
        }

        public EventBuilder posterUrl(String posterUrl) {
            this.posterUrl = posterUrl;
            return this;
        }

        /** Mã thành phố mặc định của các suất (ví dụ {@code HN}). */
        public EventBuilder city(String city) {
            this.city = city;
            return this;
        }

        public EventBuilder venue(String venueName) {
            this.venueName = venueName;
            return this;
        }

        /** Giá của hạng mặc định, dùng cho khu không nêu tên hạng. */
        public EventBuilder defaultPrice(long price) {
            this.defaultPrice = price;
            return this;
        }

        /** Khai báo hạng giá. Hạng không khu nào dùng sẽ không có dòng zone_price (chưa gán khu). */
        public EventBuilder tier(String name, long price) {
            tiers.put(name, price);
            return this;
        }

        /** Thêm một suất. Gọi nhiều lần cho sự kiện lưu diễn. Không gọi thì tạo một suất mặc định. */
        public EventBuilder showtime(Consumer<ShowtimeBuilder> customizer) {
            ShowtimeBuilder builder = new ShowtimeBuilder();
            customizer.accept(builder);
            showtimes.add(builder);
            return this;
        }

        public CreatedEvent create() {
            if (showtimes.isEmpty()) {
                showtime(s -> s.onSale().seated(10));
            }
            Map<String, UUID> tierIds = new LinkedHashMap<>();
            UUID eventId = insertEvent();
            for (ShowtimeBuilder s : showtimes) {
                for (ZoneSpec z : s.zones) {
                    if (z.tier() == null) {
                        tiers.putIfAbsent(DEFAULT_TIER, defaultPrice);
                    } else if (!tiers.containsKey(z.tier())) {
                        throw new IllegalArgumentException("Hạng giá chưa khai báo: " + z.tier());
                    }
                }
            }
            tiers.forEach((name, price) -> tierIds.put(name, insertTier(eventId, name, price)));

            Map<String, UUID> venues = new HashMap<>();
            UUID buyer = null;
            List<UUID> showtimeIds = new ArrayList<>();
            for (ShowtimeBuilder s : showtimes) {
                String cityCode = s.city != null ? s.city : city;
                String venueLabel = s.venueName != null ? s.venueName : venueName != null ? venueName : "Sân " + cityCode;
                UUID venueId = venues.computeIfAbsent(cityCode + "/" + venueLabel, k -> insertVenue(venueLabel, cityCode));
                UUID hallId = insertHall(venueId);
                UUID layoutId = insertLayout(hallId);
                UUID showtimeId = insertShowtime(eventId, hallId, layoutId, s);
                showtimeIds.add(showtimeId);

                // Tạo zone, giá và ghế theo thứ tự khai báo; ghi nhớ để phân bổ vé đã bán.
                Map<UUID, ZoneSpec> created = new LinkedHashMap<>();
                Map<UUID, List<UUID>> seatsByZone = new HashMap<>();
                for (ZoneSpec z : s.zones) {
                    UUID zoneId = insertZone(layoutId, z);
                    insertZonePrice(eventId, zoneId, tierIds.get(z.tier() != null ? z.tier() : DEFAULT_TIER));
                    created.put(zoneId, z);
                    if (z.kind().equals("SEATED")) {
                        seatsByZone.put(zoneId, insertSeats(zoneId, z.size(), z.perRow()));
                    }
                }

                // Vé đã bán: ghế ngồi trước, rồi tới khu đứng. Đơn PAID chỉ để thoả ràng buộc order_id của ghế SOLD.
                int remainingSold = s.sold;
                UUID order = null;
                if (remainingSold > 0) {
                    if (buyer == null) {
                        buyer = insertBuyer();
                    }
                    order = insertPaidOrder(buyer, showtimeId);
                }
                for (var entry : created.entrySet()) {
                    if (!entry.getValue().kind().equals("SEATED")) {
                        continue;
                    }
                    for (UUID seatId : seatsByZone.get(entry.getKey())) {
                        boolean sell = remainingSold > 0;
                        insertShowtimeSeat(showtimeId, seatId, sell ? order : null);
                        if (sell) {
                            remainingSold--;
                        }
                    }
                }
                for (var entry : created.entrySet()) {
                    if (!entry.getValue().kind().equals("STANDING")) {
                        continue;
                    }
                    int capacity = entry.getValue().size();
                    int soldHere = Math.min(remainingSold, capacity);
                    insertStock(showtimeId, entry.getKey(), capacity, soldHere);
                    remainingSold -= soldHere;
                }
                if (remainingSold > 0) {
                    throw new IllegalArgumentException("sold(" + s.sold + ") vượt sức chứa của suất");
                }
            }
            return new CreatedEvent(eventId, showtimeIds, tierIds);
        }

        // ---------------------------------------------------------------- SQL

        private UUID insertEvent() {
            return jdbc.sql("""
                            INSERT INTO event (title, description, category, poster_url, status, published_at)
                            VALUES (:title, :description, :category, :poster, :status,
                                    CASE WHEN :status = 'PUBLISHED' THEN :now END)
                            RETURNING id
                            """)
                    .param("title", title)
                    .param("description", description)
                    .param("category", category)
                    .param("poster", posterUrl)
                    .param("status", status)
                    .param("now", utc(clock.instant()))
                    .query(UUID.class).single();
        }

        private UUID insertTier(UUID eventId, String name, long price) {
            return jdbc.sql("INSERT INTO price_tier (event_id, name, price) VALUES (:e, :n, :p) RETURNING id")
                    .param("e", eventId).param("n", name).param("p", price)
                    .query(UUID.class).single();
        }
    }


    // ------------------------------------------------------------------ Chỉnh dữ liệu sau khi tạo

    /** Id các khu của suất theo {@code name} (cùng thứ tự API trả). */
    public List<UUID> zoneIds(UUID showtimeId) {
        return jdbc.sql("""
                        SELECT z.id FROM zone z JOIN showtime s ON s.seat_layout_id = z.seat_layout_id
                        WHERE s.id = :s ORDER BY z.name, z.id
                        """)
                .param("s", showtimeId).query(UUID.class).list();
    }

    /** Id các ghế của khu theo {@code row_label}, {@code number}. */
    public List<UUID> seatIds(UUID zoneId) {
        return jdbc.sql("SELECT id FROM seat WHERE zone_id = :z ORDER BY row_label, number")
                .param("z", zoneId).query(UUID.class).list();
    }

    /** Đặt ghế sang HELD của một đơn PENDING mới, hết hạn giữ lúc {@code heldUntil}. */
    public void holdSeat(UUID showtimeId, UUID seatId, Instant heldUntil) {
        UUID order = insertOrder(showtimeId, "PENDING", heldUntil);
        jdbc.sql("""
                        UPDATE showtime_seat SET status = 'HELD', order_id = :o, held_until = :until
                        WHERE showtime_id = :s AND seat_id = :seat
                        """)
                .param("o", order).param("until", utc(heldUntil)).param("s", showtimeId).param("seat", seatId)
                .update();
    }

    public void blockSeat(UUID showtimeId, UUID seatId) {
        jdbc.sql("UPDATE showtime_seat SET status = 'BLOCKED', order_id = NULL, held_until = NULL "
                        + "WHERE showtime_id = :s AND seat_id = :seat")
                .param("s", showtimeId).param("seat", seatId).update();
    }

    public void sellSeat(UUID showtimeId, UUID seatId) {
        UUID order = insertOrder(showtimeId, "PAID", clock.instant());
        jdbc.sql("UPDATE showtime_seat SET status = 'SOLD', order_id = :o, held_until = NULL "
                        + "WHERE showtime_id = :s AND seat_id = :seat")
                .param("o", order).param("s", showtimeId).param("seat", seatId).update();
    }

    /** Xoá dòng showtime_seat của ghế, mô phỏng suất chưa được khởi tạo tồn kho. */
    public void deleteShowtimeSeat(UUID showtimeId, UUID seatId) {
        jdbc.sql("DELETE FROM showtime_seat WHERE showtime_id = :s AND seat_id = :seat")
                .param("s", showtimeId).param("seat", seatId).update();
    }

    public void deleteZoneStock(UUID showtimeId, UUID zoneId) {
        jdbc.sql("DELETE FROM showtime_zone_stock WHERE showtime_id = :s AND zone_id = :z")
                .param("s", showtimeId).param("z", zoneId).update();
    }

    /** Đặt số đã bán và đang giữ của khu đứng. */
    public void setZoneStock(UUID showtimeId, UUID zoneId, int sold, int held) {
        jdbc.sql("UPDATE showtime_zone_stock SET sold = :sold, held = :held WHERE showtime_id = :s AND zone_id = :z")
                .param("sold", sold).param("held", held).param("s", showtimeId).param("z", zoneId).update();
    }

    /** Biến hai ghế thành một cặp ghế đôi (loại COUPLE, ghi vào seat_pair theo thứ tự id). */
    public void makeCouple(UUID seatA, UUID seatB) {
        jdbc.sql("UPDATE seat SET seat_type_id = (SELECT id FROM seat_type WHERE code = 'COUPLE') "
                        + "WHERE id IN (:a, :b)")
                .param("a", seatA).param("b", seatB).update();
        UUID low = seatA.compareTo(seatB) < 0 ? seatA : seatB;
        UUID high = low.equals(seatA) ? seatB : seatA;
        jdbc.sql("INSERT INTO seat_pair (seat_a_id, seat_b_id) VALUES (:a, :b)")
                .param("a", low).param("b", high).update();
    }

    private UUID insertOrder(UUID showtimeId, String status, Instant expiresAt) {
        UUID buyer = insertBuyer();
        return jdbc.sql("""
                        INSERT INTO orders (code, user_id, showtime_id, status, total_amount, expires_at, paid_at)
                        VALUES (:code, :u, :s, :status, 0, :exp, CASE WHEN :status = 'PAID' THEN :exp END) RETURNING id
                        """)
                .param("code", UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase())
                .param("u", buyer).param("s", showtimeId).param("status", status).param("exp", utc(expiresAt))
                .query(UUID.class).single();
    }

    // ------------------------------------------------------------------ SQL dùng chung

    private UUID insertVenue(String name, String city) {
        return jdbc.sql("INSERT INTO venue (name, type, city) VALUES (:n, 'STADIUM', :c) RETURNING id")
                .param("n", name).param("c", city).query(UUID.class).single();
    }

    private UUID insertHall(UUID venueId) {
        return jdbc.sql("INSERT INTO hall (venue_id, name) VALUES (:v, :n) RETURNING id")
                .param("v", venueId).param("n", "Hall " + UUID.randomUUID()).query(UUID.class).single();
    }

    private UUID insertLayout(UUID hallId) {
        return jdbc.sql("""
                        INSERT INTO seat_layout (hall_id, status, published_at) VALUES (:h, 'PUBLISHED', :now) RETURNING id
                        """)
                .param("h", hallId).param("now", utc(clock.instant())).query(UUID.class).single();
    }

    private UUID insertShowtime(UUID eventId, UUID hallId, UUID layoutId, CatalogFixtures.ShowtimeBuilder s) {
        Instant endsAt = s.startsAt.plus(s.length);
        Instant opens = s.saleOpensAt != null ? s.saleOpensAt : s.startsAt.minus(Duration.ofDays(30));
        Instant closes = s.saleClosesAt != null ? s.saleClosesAt : s.startsAt;
        return jdbc.sql("""
                        INSERT INTO showtime (event_id, hall_id, seat_layout_id, starts_at, ends_at,
                                              sale_opens_at, sale_closes_at, status)
                        VALUES (:e, :h, :l, :starts, :ends, :opens, :closes, :status) RETURNING id
                        """)
                .param("e", eventId).param("h", hallId).param("l", layoutId)
                .param("starts", utc(s.startsAt)).param("ends", utc(endsAt))
                .param("opens", utc(opens)).param("closes", utc(closes))
                .param("status", s.status)
                .query(UUID.class).single();
    }

    private UUID insertZone(UUID layoutId, ZoneSpec z) {
        return jdbc.sql("""
                        INSERT INTO zone (seat_layout_id, name, kind, capacity) VALUES (:l, :n, :k, :c) RETURNING id
                        """)
                .param("l", layoutId).param("n", z.name()).param("k", z.kind()).param("c", z.size())
                .query(UUID.class).single();
    }

    private void insertZonePrice(UUID eventId, UUID zoneId, UUID tierId) {
        jdbc.sql("INSERT INTO zone_price (event_id, zone_id, price_tier_id) VALUES (:e, :z, :t)")
                .param("e", eventId).param("z", zoneId).param("t", tierId).update();
    }

    private List<UUID> insertSeats(UUID zoneId, int count, int perRow) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // perRow = 0: một hàng A duy nhất. Ngược lại hàng A, B, C... mỗi hàng perRow ghế.
            String row = perRow == 0 ? "A" : String.valueOf((char) ('A' + i / perRow));
            int number = perRow == 0 ? i + 1 : i % perRow + 1;
            int y = perRow == 0 ? 0 : i / perRow;
            ids.add(jdbc.sql("""
                            INSERT INTO seat (zone_id, seat_type_id, row_label, number, x, y)
                            VALUES (:z, (SELECT id FROM seat_type WHERE code = 'STANDARD'), :row, :n, :n, :y) RETURNING id
                            """)
                    .param("z", zoneId).param("row", row).param("n", number).param("y", y)
                    .query(UUID.class).single());
        }
        return ids;
    }

    private void insertShowtimeSeat(UUID showtimeId, UUID seatId, UUID soldOrder) {
        jdbc.sql("""
                        INSERT INTO showtime_seat (showtime_id, seat_id, status, order_id)
                        VALUES (:s, :seat, :status, :o)
                        """)
                .param("s", showtimeId).param("seat", seatId)
                .param("status", soldOrder != null ? "SOLD" : "AVAILABLE")
                .param("o", soldOrder)
                .update();
    }

    private void insertStock(UUID showtimeId, UUID zoneId, int capacity, int sold) {
        jdbc.sql("""
                        INSERT INTO showtime_zone_stock (showtime_id, zone_id, capacity, sold, held)
                        VALUES (:s, :z, :c, :sold, 0)
                        """)
                .param("s", showtimeId).param("z", zoneId).param("c", capacity).param("sold", sold).update();
    }

    private UUID insertBuyer() {
        return jdbc.sql("""
                        INSERT INTO users (email, full_name, password_hash, role, status)
                        VALUES (:email, 'Người mua test', 'x', 'BUYER', 'ACTIVE') RETURNING id
                        """)
                .param("email", UUID.randomUUID() + "@test.vingo").query(UUID.class).single();
    }

    private UUID insertPaidOrder(UUID buyerId, UUID showtimeId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        INSERT INTO orders (code, user_id, showtime_id, status, total_amount, expires_at, paid_at)
                        VALUES (:code, :u, :s, 'PAID', 0, :now, :now) RETURNING id
                        """)
                .param("code", UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase())
                .param("u", buyerId).param("s", showtimeId).param("now", utc(now))
                .query(UUID.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
