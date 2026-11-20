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

    /** Xoá toàn bộ dữ liệu catalog, booking và các bảng phụ thuộc. Giữ nguyên {@code user_account}. */
    public void reset() {
        jdbc.sql("""
                TRUNCATE standing_inventory, seat_inventory, event_zone, sku_price, sku, showtime, event,
                         seat, zone, seat_map, venue_map, venue CASCADE
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
        private int coupleFirst=-1;
        public ShowtimeBuilder couple(int firstIndex) {this.coupleFirst=firstIndex;return this;}


        private Instant startsAt = clock.instant().plus(Duration.ofDays(7));
        private Duration length = Duration.ofHours(2);
        private String status = "SCHEDULED";
        private Instant saleOpensAt;
        private Instant saleClosesAt;
        private String city;
        private String venueName;
        private int sold;
        private String layoutSvg;
        private boolean publishLayout = true;
        private boolean omitLayoutSvg;
        private final List<ZoneSpec> zones = new ArrayList<>();

        private ShowtimeBuilder() {
        }

        public ShowtimeBuilder layoutSvg(String svg) {
            this.layoutSvg = svg;
            return this;
        }

        public ShowtimeBuilder draftLayout() {
            this.publishLayout = false;
            return this;
        }

        public ShowtimeBuilder draftLayoutWithoutSvg() {
            this.omitLayoutSvg = true;
            return draftLayout();
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
            ShowtimeBuilder firstShowtime = showtimes.getFirst();
            String eventCity = firstShowtime.city != null ? firstShowtime.city : city;
            String eventVenueLabel = firstShowtime.venueName != null
                    ? firstShowtime.venueName
                    : venueName != null ? venueName : "Sân " + eventCity;
            UUID venueId = insertVenue(eventVenueLabel, eventCity);
            UUID layoutId = VenueSchemaFixture.draft(jdbc, venueId);
            if (firstShowtime.layoutSvg != null) {
                jdbc.sql("UPDATE seat_map SET svg = :svg WHERE id = :id")
                        .param("svg", firstShowtime.layoutSvg).param("id", layoutId).update();
            }

            Map<String, UUID> tierIds = new LinkedHashMap<>();
            UUID eventId = insertEvent(venueId, layoutId);
            for (ShowtimeBuilder s : showtimes) {
                for (ZoneSpec z : s.zones) {
                    if (z.tier() == null) {
                        tiers.putIfAbsent(DEFAULT_TIER, defaultPrice);
                    } else if (!tiers.containsKey(z.tier())) {
                        throw new IllegalArgumentException("Hạng giá chưa khai báo: " + z.tier());
                    }
                }
            }
            UUID buyer = insertBuyer();
            tiers.forEach((name, price) -> tierIds.put(name, insertTier(eventId, name, price, buyer)));

            List<UUID> showtimeIds = new ArrayList<>();
            Map<String, UUID> zonesByCode = new LinkedHashMap<>();
            Map<UUID, ZoneSpec> createdZones = new LinkedHashMap<>();
            Map<UUID, List<UUID>> seatsByZone = new HashMap<>();

            // Tạo zone, giá và ghế từ showtime đầu tiên (v2: showtime dùng chung seat_map của event).
            int zoneSeq = 1;
            for (ZoneSpec z : firstShowtime.zones) {
                String code = "Z" + zoneSeq++;
                UUID zoneId = insertZone(layoutId, code, z);
                insertZonePrice(eventId, layoutId, zoneId, tierIds.get(z.tier() != null ? z.tier() : DEFAULT_TIER));
                zonesByCode.put(code, zoneId);
                createdZones.put(zoneId, z);
                if (z.kind().equals("SEATED")) {
                    seatsByZone.put(zoneId, insertSeats(layoutId, zoneId, z.size(), z.perRow()));
                }
            }

            for (ShowtimeBuilder s : showtimes) {
                UUID showtimeId = insertShowtime(eventId, layoutId, s);
                showtimeIds.add(showtimeId);

                int remainingSold = s.sold;
                UUID order = null;
                if (remainingSold > 0) {
                    order = insertPaidOrder(buyer, showtimeId);
                }
                for (var entry : createdZones.entrySet()) {
                    if (!entry.getValue().kind().equals("SEATED")) {
                        continue;
                    }
                    for (UUID seatId : seatsByZone.get(entry.getKey())) {
                        boolean sell = remainingSold > 0;
                        insertShowtimeSeat(showtimeId, layoutId, entry.getKey(), seatId, sell ? order : null);
                        if (sell) {
                            remainingSold--;
                        }
                    }
                }
                for (var entry : createdZones.entrySet()) {
                    if (!entry.getValue().kind().equals("STANDING")) {
                        continue;
                    }
                    int capacity = entry.getValue().size();
                    int soldHere = Math.min(remainingSold, capacity);
                    insertStock(showtimeId, layoutId, entry.getKey(), capacity, soldHere);
                    remainingSold -= soldHere;
                }
                if (remainingSold > 0) {
                    throw new IllegalArgumentException("sold(" + s.sold + ") vượt sức chứa của suất");
                }
            }
            return new CreatedEvent(eventId, showtimeIds, tierIds);
        }

        // ---------------------------------------------------------------- SQL

        private UUID insertEvent(UUID venueId, UUID layoutId) {
            String mappedCategory = "FOOTBALL".equals(category) ? "FOOTBALL" : "CONCERT";
            return jdbc.sql("""
                            INSERT INTO event (seat_map_id, title, description, category, poster_desktop_url, status)
                            VALUES (:layout, :title, :description, :category, :poster, :status::event_status)
                            RETURNING id
                            """)
                    .param("layout", layoutId)
                    .param("title", title)
                    .param("description", description)
                    .param("category", mappedCategory)
                    .param("poster", posterUrl)
                    .param("status", status)
                    .query(UUID.class).single();
        }

        private UUID insertTier(UUID eventId, String name, long price, UUID createdBy) {
            UUID skuId = jdbc.sql("INSERT INTO sku (event_id, name, color) VALUES (:e, :n, '#336699') RETURNING id")
                    .param("e", eventId).param("n", name)
                    .query(UUID.class).single();
            jdbc.sql("INSERT INTO sku_price (sku_id, price, effective_from, created_by) VALUES (:s, :p, :now, :u)")
                    .param("s", skuId).param("p", price).param("now", utc(clock.instant()).minusDays(30))
                    .param("u", createdBy)
                    .update();
            return skuId;
        }
    }

    // ------------------------------------------------------------------ Chỉnh dữ liệu sau khi tạo

    /** Id các khu của suất theo {@code name} (cùng thứ tự API trả). */
    public List<UUID> zoneIds(UUID showtimeId) {
        return jdbc.sql("""
                        SELECT z.id FROM zone z JOIN showtime s ON s.seat_map_id = z.seat_map_id
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
                        UPDATE seat_inventory SET status = 'HELD', order_id = :o, held_until = :until
                        WHERE showtime_id = :s AND seat_id = :seat
                        """)
                .param("o", order).param("until", utc(heldUntil)).param("s", showtimeId).param("seat", seatId)
                .update();
    }

    public void blockSeat(UUID showtimeId, UUID seatId) {
        jdbc.sql("UPDATE seat_inventory SET status = 'BLOCKED', order_id = NULL, held_until = NULL "
                        + "WHERE showtime_id = :s AND seat_id = :seat")
                .param("s", showtimeId).param("seat", seatId).update();
    }

    public void sellSeat(UUID showtimeId, UUID seatId) {
        UUID order = insertOrder(showtimeId, "PAID", clock.instant());
        jdbc.sql("UPDATE seat_inventory SET status = 'SOLD', order_id = :o, held_until = NULL "
                        + "WHERE showtime_id = :s AND seat_id = :seat")
                .param("o", order).param("s", showtimeId).param("seat", seatId).update();
    }

    /** Xoá dòng seat_inventory của ghế, mô phỏng suất chưa được khởi tạo tồn kho. */
    public void deleteShowtimeSeat(UUID showtimeId, UUID seatId) {
        jdbc.sql("DELETE FROM seat_inventory WHERE showtime_id = :s AND seat_id = :seat")
                .param("s", showtimeId).param("seat", seatId).update();
    }

    public void deleteZoneStock(UUID showtimeId, UUID zoneId) {
        jdbc.sql("DELETE FROM standing_inventory WHERE showtime_id = :s AND zone_id = :z")
                .param("s", showtimeId).param("z", zoneId).update();
    }

    /** Đặt số đã bán và đang giữ của khu đứng. */
    public void setZoneStock(UUID showtimeId, UUID zoneId, int sold, int held) {
        jdbc.sql("UPDATE standing_inventory SET sold = :sold, held = :held WHERE showtime_id = :s AND zone_id = :z")
                .param("sold", sold).param("held", held).param("s", showtimeId).param("z", zoneId).update();
    }

    /** Schema v2 không còn seat_pair/COUPLE. Giữ method rỗng để tương thích builder. */
    public void makeCouple(UUID seatA, UUID seatB) {
    }

    private UUID insertOrder(UUID showtimeId, String status, Instant expiresAt) {
        UUID buyer = insertBuyer();
        return jdbc.sql("""
                        INSERT INTO ticket_order (code, user_id, showtime_id, status, total_amount, expires_at,
                                                  buyer_name, buyer_email, buyer_phone, paid_at)
                        VALUES (:code, :u, :s, :status::order_status, 0, :exp,
                                CASE WHEN :status = 'PAID' THEN 'Test Buyer' END,
                                CASE WHEN :status = 'PAID' THEN 'buyer@vingo.test' END,
                                CASE WHEN :status = 'PAID' THEN '+84901234567' END,
                                CASE WHEN :status = 'PAID' THEN :exp END) RETURNING id
                        """)
                .param("code", UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase())
                .param("u", buyer).param("s", showtimeId).param("status", status).param("exp", utc(expiresAt))
                .query(UUID.class).single();
    }

    // ------------------------------------------------------------------ SQL dùng chung

    private UUID insertVenue(String name, String city) {
        return jdbc.sql("INSERT INTO venue (name, city) VALUES (:n, :c) RETURNING id")
                .param("n", name).param("c", city).query(UUID.class).single();
    }

    private UUID insertShowtime(UUID eventId, UUID layoutId, CatalogFixtures.ShowtimeBuilder s) {
        Instant endsAt = s.startsAt.plus(s.length);
        Instant opens = s.saleOpensAt != null ? s.saleOpensAt : s.startsAt.minus(Duration.ofDays(30));
        Instant closes = s.saleClosesAt != null ? s.saleClosesAt : s.startsAt;
        return jdbc.sql("""
                        INSERT INTO showtime (event_id, seat_map_id, starts_at, ends_at,
                                              sale_opens_at, sale_closes_at, cancelled_at)
                        VALUES (:e, :l, :starts, :ends, :opens, :closes,
                                CASE WHEN :status = 'CANCELLED' THEN :now END) RETURNING id
                        """)
                .param("e", eventId).param("l", layoutId)
                .param("starts", utc(s.startsAt)).param("ends", utc(endsAt))
                .param("opens", utc(opens)).param("closes", utc(closes))
                .param("status", s.status)
                .param("now", utc(clock.instant()))
                .query(UUID.class).single();
    }

    private UUID insertZone(UUID layoutId, String code, ZoneSpec z) {
        Integer capacity = "STANDING".equals(z.kind()) ? z.size() : null;
        return jdbc.sql("""
                        INSERT INTO zone (seat_map_id, code, name, kind, capacity)
                        VALUES (:l, :code, :n, :k::zone_kind, :c) RETURNING id
                        """)
                .param("l", layoutId).param("code", code).param("n", z.name()).param("k", z.kind()).param("c", capacity)
                .query(UUID.class).single();
    }

    private void insertZonePrice(UUID eventId, UUID layoutId, UUID zoneId, UUID skuId) {
        jdbc.sql("INSERT INTO event_zone (event_id, seat_map_id, zone_id, sku_id) VALUES (:e, :l, :z, :t)")
                .param("e", eventId).param("l", layoutId).param("z", zoneId).param("t", skuId).update();
    }

    private List<UUID> insertSeats(UUID layoutId, UUID zoneId, int count, int perRow) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String row = perRow == 0 ? "A" : String.valueOf((char) ('A' + i / perRow));
            int number = perRow == 0 ? i + 1 : i % perRow + 1;
            ids.add(jdbc.sql("""
                            INSERT INTO seat (seat_map_id, zone_id, zone_kind, row_label, number)
                            VALUES (:l, :z, 'SEATED', :row, :n) RETURNING id
                            """)
                    .param("l", layoutId).param("z", zoneId).param("row", row).param("n", number)
                    .query(UUID.class).single());
        }
        return ids;
    }

    private void insertShowtimeSeat(UUID showtimeId, UUID layoutId, UUID zoneId, UUID seatId, UUID soldOrder) {
        jdbc.sql("""
                        INSERT INTO seat_inventory (showtime_id, seat_map_id, seat_id, zone_id, status, order_id)
                        VALUES (:s, :l, :seat, :z, :status::seat_status, :o)
                        """)
                .param("s", showtimeId).param("l", layoutId).param("seat", seatId).param("z", zoneId)
                .param("status", soldOrder != null ? "SOLD" : "AVAILABLE")
                .param("o", soldOrder)
                .update();
    }

    private void insertStock(UUID showtimeId, UUID layoutId, UUID zoneId, int capacity, int sold) {
        jdbc.sql("""
                        INSERT INTO standing_inventory (showtime_id, seat_map_id, zone_id, zone_kind, capacity, sold, held)
                        VALUES (:s, :l, :z, 'STANDING', :c, :sold, 0)
                        """)
                .param("s", showtimeId).param("l", layoutId).param("z", zoneId).param("c", capacity).param("sold", sold)
                .update();
    }

    private UUID insertBuyer() {
        return jdbc.sql("""
                        INSERT INTO user_account (email, full_name, password_hash, role, email_verified_at, phone, phone_verified_at)
                        VALUES (:email, 'Người mua test', 'x', 'BUYER', :now, :phone, :now) RETURNING id
                        """)
                .param("phone", CatalogFixture.uniquePhone()).param("now", utc(clock.instant()))
                .param("email", UUID.randomUUID() + "@test.vingo").query(UUID.class).single();
    }

    private UUID insertPaidOrder(UUID buyerId, UUID showtimeId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        INSERT INTO ticket_order (code, user_id, showtime_id, status, total_amount, expires_at,
                                                  buyer_name, buyer_email, buyer_phone, paid_at)
                        VALUES (:code, :u, :s, 'PAID', 0, :now, 'Người mua test', 'buyer@vingo.test', '+84901234567', :now) RETURNING id
                        """)
                .param("code", UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase())
                .param("u", buyerId).param("s", showtimeId).param("now", utc(now))
                .query(UUID.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
