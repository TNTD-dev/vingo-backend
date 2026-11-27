package vingo.search.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

import vingo.search.PriceTierResult;
import vingo.search.SeatState;
import vingo.search.ZoneKind;

/**
 * Truy vấn sơ đồ ghế, số chỗ còn và gợi ý ghế, chỉ đọc bằng {@link JdbcClient}. Đọc bảng của catalog (showtime,
 * event, seat_map, zone, seat, event_zone, sku, sku_price) và booking (seat_inventory, standing_inventory).
 *
 * <p>Ghế trống theo Implicit Status: {@code AVAILABLE}, hoặc {@code HELD} có {@code held_until < :now}.
 * {@code :now} luôn do chỗ gọi truyền vào, không gọi {@code now()} trong SQL.
 */
@Repository
class SeatmapRepository {

    /** Suất đủ điều kiện hiển thị. */
    record ShowtimeRef(UUID id, UUID eventId, UUID seatLayoutId, String eventStatus, OffsetDateTime cancelledAt,
            OffsetDateTime saleOpensAt, OffsetDateTime saleClosesAt, OffsetDateTime endsAt) {
    }

    /**
     * Một khu. {@code seatsLeft} là số chỗ còn của khu (ghế ngồi trống hoặc chỗ đứng) ở
     * {@link #countZones}. {@code stockMissing} là true khi khu đứng chưa có dòng {@code standing_inventory}.
     * {@code missingSeats} là số ghế của khu ngồi thiếu dòng {@code seat_inventory} (chỉ {@link #countZones} điền).
     */
    record ZoneRow(UUID id, String code, String name, ZoneKind kind, UUID skuId, Optional<PriceTierResult> tier, int seatsLeft,
            boolean stockMissing, int missingSeats) {
    }

    /**
     * Layout tĩnh của suất, dữ liệu được cache 10 phút: khu (kèm hạng giá, {@code tier} có thể null) và ghế của
     * khu ngồi. Không có trạng thái ghế hay số chỗ còn.
     */
    record SeatmapLayout(List<LayoutZone> zones) {
    }

    /** Một khu của layout; {@code seats} rỗng với khu đứng. */
    record LayoutZone(UUID id, String name, ZoneKind kind, UUID skuId, PriceTierResult tier, List<LayoutSeat> seats) {
        LayoutZone {
            seats = seats.stream().sorted(SeatOrder.BY_ROW_AND_NUMBER).toList();
        }
    }

    /** Ghế của layout kèm lựa chọn bán (sku), hạng vé (ticket_type) và giá có hiệu lực. */
    record LayoutSeat(UUID id, UUID skuId, UUID ticketTypeId, String tierName, Long price, String row, int number) {
        LayoutSeat(UUID id, String row, int number) {
            this(id, null, null, null, null, row, number);
        }
    }

    /** Tồn kho khu đứng; {@code missing} là true khi chưa có dòng {@code standing_inventory}. */
    record StandingStock(int seatsLeft, boolean missing) {
    }

    private static final String FREE_SEAT_SQL = freeSeatSql("ss");

    /** Điều kiện ghế trống theo Implicit Status cho dòng {@code seat_inventory} có alias {@code alias}. */
    private static String freeSeatSql(String alias) {
        return "(" + alias + ".status = 'AVAILABLE' OR (" + alias + ".status = 'HELD' AND " + alias
                + ".held_until < :now))";
    }

    private final JdbcClient jdbc;

    SeatmapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Suất thuộc sự kiện đang PUBLISHED; ngược lại rỗng. */
    Optional<ShowtimeRef> findVisibleShowtime(UUID showtimeId) {
        return jdbc.sql("""
                        SELECT s.id, s.event_id, s.seat_map_id, e.status::text AS event_status, s.cancelled_at,
                               s.sale_opens_at, s.sale_closes_at, s.ends_at
                        FROM showtime s
                        JOIN event e ON e.id = s.event_id
                        WHERE s.id = :id AND e.status = 'PUBLISHED'
                        """)
                .param("id", showtimeId)
                .query((rs, n) -> new ShowtimeRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("seat_map_id", UUID.class),
                        rs.getString("event_status"),
                        rs.getObject("cancelled_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class)))
                .optional();
    }

    record SvgLayers(String overview, String detail) {}

    Optional<SvgLayers> findPublishedSvg(UUID layoutId) {
        return jdbc.sql("""
                        SELECT vm.svg AS overview, sm.svg AS detail
                        FROM seat_map sm
                        JOIN venue_map vm ON vm.id = sm.venue_map_id
                        WHERE sm.id = :id
                        """)
                .param("id", layoutId)
                .query((rs, n) -> new SvgLayers(rs.getString("overview"), rs.getString("detail")))
                .optional();
    }

    /**
     * Layout tĩnh của suất: các khu theo {@code name} kèm hạng giá, ghế của khu ngồi theo {@code row_label},
     * {@code number}. Hai câu truy vấn, không phụ thuộc trạng thái ghế nên cache được.
     */
    SeatmapLayout findLayout(ShowtimeRef showtime, OffsetDateTime now) {
        record ZoneBase(UUID id, String name, ZoneKind kind, UUID skuId, PriceTierResult tier) {
        }
        List<ZoneBase> zones = jdbc.sql("""
                        SELECT z.id, COALESCE(ez.display_name, z.name) AS name, z.kind,
                               sk.id AS sku_id,
                               tt.id AS tier_id, tt.name AS tier_name, sp.price, tt.color
                        FROM zone z
                        JOIN event_zone ez ON ez.event_id = :eventId AND ez.zone_id = z.id
                        LEFT JOIN ticket_type tt ON tt.id = ez.ticket_type_id
                        LEFT JOIN sku sk ON sk.event_id = :eventId AND sk.zone_id = z.id AND sk.ticket_type_id = tt.id
                        LEFT JOIN LATERAL (
                            SELECT price FROM ticket_type_price
                            WHERE ticket_type_id = tt.id AND effective_from <= :now
                            ORDER BY effective_from DESC
                            LIMIT 1
                        ) sp ON true
                        WHERE z.seat_map_id = :layoutId
                        ORDER BY z.name, z.id
                        """)
                .param("eventId", showtime.eventId())
                .param("layoutId", showtime.seatLayoutId())
                .param("now", now)
                .query((rs, n) -> new ZoneBase(
                        rs.getObject("id", UUID.class), rs.getString("name"), ZoneKind.valueOf(rs.getString("kind")),
                        rs.getObject("sku_id", UUID.class),
                        tier(rs).orElse(null)))
                .list();
        Map<UUID, List<LayoutSeat>> seatsByZone = new HashMap<>();
        jdbc.sql("""
                        SELECT s.zone_id, s.id, s.row_label, s.number,
                               sk.id AS sku_id, tt.id AS ticket_type_id, tt.name AS tier_name, sp.price
                        FROM zone z
                        JOIN seat s ON s.zone_id = z.id
                        JOIN event_zone ez ON ez.event_id = :eventId AND ez.zone_id = z.id
                        LEFT JOIN sku sk ON sk.event_id = :eventId AND sk.seat_id = s.id AND sk.ticket_type_id = ez.ticket_type_id
                        LEFT JOIN ticket_type tt ON tt.id = COALESCE(sk.ticket_type_id, ez.ticket_type_id)
                        LEFT JOIN LATERAL (
                            SELECT price FROM ticket_type_price
                            WHERE ticket_type_id = tt.id AND effective_from <= :now
                            ORDER BY effective_from DESC
                            LIMIT 1
                        ) sp ON true
                        WHERE z.seat_map_id = :layoutId AND z.kind = 'SEATED'
                        """)
                .param("layoutId", showtime.seatLayoutId())
                .param("eventId", showtime.eventId())
                .param("now", now)
                .query((rs, n) -> {
                    seatsByZone.computeIfAbsent(rs.getObject("zone_id", UUID.class), k -> new ArrayList<>())
                            .add(new LayoutSeat(
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("sku_id", UUID.class),
                                    rs.getObject("ticket_type_id", UUID.class),
                                    rs.getString("tier_name"),
                                    rs.getObject("price", Long.class),
                                    rs.getString("row_label"),
                                    rs.getInt("number")));
                    return null;
                })
                .list();
        return new SeatmapLayout(zones.stream()
                .map(z -> new LayoutZone(z.id(), z.name(), z.kind(), z.skuId(), z.tier(),
                        seatsByZone.getOrDefault(z.id(), List.of())))
                .toList());
    }

    /**
     * Trạng thái hiện tại của các ghế có dòng {@code seat_inventory}; HELD quá hạn tại {@code now} báo AVAILABLE.
     * Ghế thiếu dòng không có trong kết quả. {@code zoneId} khác null thì chỉ lấy ghế của khu đó. Luôn đọc DB.
     */
    Map<UUID, SeatState> findSeatStates(UUID showtimeId, UUID zoneId, OffsetDateTime now) {
        StatementSpec spec = jdbc.sql("""
                        SELECT ss.seat_id,
                               CASE WHEN ss.status = 'HELD' AND ss.held_until < :now THEN 'AVAILABLE'
                                    ELSE ss.status::text END AS state
                        FROM seat_inventory ss
                        %s
                        WHERE ss.showtime_id = :showtimeId
                        """.formatted(zoneId != null ? "JOIN seat s ON s.id = ss.seat_id AND s.zone_id = :zoneId" : ""))
                .param("showtimeId", showtimeId)
                .param("now", now);
        if (zoneId != null) {
            spec = spec.param("zoneId", zoneId);
        }
        Map<UUID, SeatState> states = new HashMap<>();
        spec.query((rs, n) -> {
            states.put(rs.getObject("seat_id", UUID.class), SeatState.valueOf(rs.getString("state")));
            return null;
        }).list();
        return states;
    }

    /** Số chỗ còn của từng khu đứng trong layout của suất, theo id khu. Luôn đọc DB. */
    Map<UUID, StandingStock> findStandingStock(ShowtimeRef showtime) {
        Map<UUID, StandingStock> stock = new HashMap<>();
        jdbc.sql("""
                        SELECT z.id, st.zone_id IS NULL AS stock_missing,
                               COALESCE(st.capacity - st.sold - st.held, 0) AS seats_left
                        FROM zone z
                        LEFT JOIN standing_inventory st ON st.showtime_id = :showtimeId AND st.zone_id = z.id
                        WHERE z.seat_map_id = :layoutId AND z.kind = 'STANDING'
                        """)
                .param("showtimeId", showtime.id())
                .param("layoutId", showtime.seatLayoutId())
                .query((rs, n) -> {
                    stock.put(rs.getObject("id", UUID.class),
                            new StandingStock(rs.getInt("seats_left"), rs.getBoolean("stock_missing")));
                    return null;
                })
                .list();
        return stock;
    }

    /**
     * Số chỗ còn theo khu, một câu {@code GROUP BY} khu. Khu ngồi đếm ghế trống theo Implicit Status (ghế thiếu
     * dòng {@code seat_inventory} không tính); khu đứng lấy {@code capacity - sold - held}.
     */
    List<ZoneRow> countZones(ShowtimeRef showtime, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT z.id, COALESCE(ez.display_name, z.name) AS name, z.kind,
                               sk.id AS sku_id,
                               tt.id AS tier_id, tt.name AS tier_name, sp.price, tt.color,
                               z.code, st.zone_id IS NULL AS stock_missing,
                               CASE WHEN z.kind = 'STANDING' THEN COALESCE(st.capacity - st.sold - st.held, 0)
                                    ELSE COUNT(ss.seat_id) FILTER (WHERE %s) END AS seats_left,
                               COUNT(s.id) FILTER (WHERE ss.seat_id IS NULL) AS missing_seats
                        FROM zone z
                        JOIN event_zone ez ON ez.event_id = :eventId AND ez.zone_id = z.id
                        LEFT JOIN ticket_type tt ON tt.id = ez.ticket_type_id
                        LEFT JOIN sku sk ON sk.event_id = :eventId AND sk.zone_id = z.id AND sk.ticket_type_id = tt.id
                        LEFT JOIN LATERAL (
                            SELECT price FROM ticket_type_price
                            WHERE ticket_type_id = tt.id AND effective_from <= :now
                            ORDER BY effective_from DESC
                            LIMIT 1
                        ) sp ON true
                        LEFT JOIN standing_inventory st ON st.showtime_id = :showtimeId AND st.zone_id = z.id
                        LEFT JOIN seat s ON s.zone_id = z.id AND z.kind = 'SEATED'
                        LEFT JOIN seat_inventory ss ON ss.showtime_id = :showtimeId AND ss.seat_id = s.id
                        WHERE z.seat_map_id = :layoutId
                        GROUP BY z.id, z.name, ez.display_name, z.kind, sk.id, tt.id, tt.name, sp.price, tt.color,
                                 st.zone_id, st.capacity, st.sold, st.held
                        ORDER BY z.name, z.id
                        """.formatted(FREE_SEAT_SQL))
                .param("eventId", showtime.eventId())
                .param("showtimeId", showtime.id())
                .param("layoutId", showtime.seatLayoutId())
                .param("now", now)
                .query((rs, n) -> {
                    ZoneKind kind = ZoneKind.valueOf(rs.getString("kind"));
                    return new ZoneRow(
                            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), kind,
                            rs.getObject("sku_id", UUID.class), tier(rs),
                            rs.getInt("seats_left"), kind == ZoneKind.STANDING && rs.getBoolean("stock_missing"),
                            rs.getInt("missing_seats"));
                }).list();
    }

    /**
     * Ghế trống (Implicit Status) của khu SEATED thuộc layout của suất. SeatSuggester sắp theo thứ tự tự nhiên.
     */
    List<LayoutSeat> findFreeSeats(UUID showtimeId, UUID zoneId, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT s.id, s.row_label, s.number
                        FROM showtime sh
                        JOIN zone z ON z.seat_map_id = sh.seat_map_id AND z.id = :zoneId AND z.kind = 'SEATED'
                        JOIN seat s ON s.zone_id = z.id
                        JOIN seat_inventory ss ON ss.showtime_id = sh.id AND ss.seat_id = s.id AND %s
                        WHERE sh.id = :showtimeId
                        """.formatted(FREE_SEAT_SQL))
                .param("showtimeId", showtimeId)
                .param("zoneId", zoneId)
                .param("now", now)
                .query((rs, n) -> new LayoutSeat(
                        rs.getObject("id", UUID.class), rs.getString("row_label"), rs.getInt("number")))
                .list();
    }

    private static Optional<PriceTierResult> tier(ResultSet rs) throws SQLException {
        UUID tierId = rs.getObject("tier_id", UUID.class);
        if (tierId == null) {
            return Optional.empty();
        }
        return Optional.of(new PriceTierResult(tierId, rs.getString("tier_name"), rs.getLong("price"),
                rs.getString("color")));
    }
}
