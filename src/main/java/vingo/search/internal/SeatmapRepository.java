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
 * event, zone, seat, seat_type, seat_pair, zone_price, price_tier) và booking (showtime_seat, showtime_zone_stock).
 *
 * <p>Ghế trống theo Implicit Status: {@code AVAILABLE}, hoặc {@code HELD} có {@code held_until < :now}.
 * {@code :now} luôn do chỗ gọi truyền vào, không gọi {@code now()} trong SQL.
 */
@Repository
class SeatmapRepository {

    /** Suất đủ điều kiện hiển thị, {@code storedStatus} là giá trị trong DB (chưa tính theo giờ). */
    record ShowtimeRef(UUID id, UUID eventId, UUID seatLayoutId, String storedStatus, OffsetDateTime saleOpensAt,
            OffsetDateTime saleClosesAt, OffsetDateTime endsAt) {
    }

    /**
     * Một khu. {@code seatsLeft} là số chỗ còn của khu (ghế ngồi trống hoặc chỗ đứng) ở
     * {@link #countZones}. {@code stockMissing} là true khi khu đứng chưa có dòng {@code showtime_zone_stock}.
     * {@code missingSeats} là số ghế của khu ngồi thiếu dòng {@code showtime_seat} (chỉ {@link #countZones} điền).
     */
    record ZoneRow(UUID id, String name, ZoneKind kind, Optional<PriceTierResult> tier, int seatsLeft,
            boolean stockMissing, int missingSeats) {
    }

    /**
     * Layout tĩnh của suất, dữ liệu được cache 10 phút: khu (kèm hạng giá, {@code tier} có thể null) và ghế của
     * khu ngồi. Không có trạng thái ghế hay số chỗ còn.
     */
    record SeatmapLayout(List<LayoutZone> zones) {
    }

    /** Một khu của layout; {@code seats} rỗng với khu đứng. */
    record LayoutZone(UUID id, String name, ZoneKind kind, PriceTierResult tier, List<LayoutSeat> seats) {
    }

    /** Ghế của layout; {@code pairSeatId} null khi không phải ghế đôi. */
    record LayoutSeat(UUID id, String row, int number, String seatType, UUID pairSeatId, int x, int y) {
    }

    /** Tồn kho khu đứng; {@code missing} là true khi chưa có dòng {@code showtime_zone_stock}. */
    record StandingStock(int seatsLeft, boolean missing) {
    }

    private static final String FREE_SEAT_SQL = freeSeatSql("ss");

    /** Điều kiện ghế trống theo Implicit Status cho dòng {@code showtime_seat} có alias {@code alias}. */
    private static String freeSeatSql(String alias) {
        return "(" + alias + ".status = 'AVAILABLE' OR (" + alias + ".status = 'HELD' AND " + alias
                + ".held_until < :now))";
    }

    private final JdbcClient jdbc;

    SeatmapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Suất không phải DRAFT mà sự kiện đang PUBLISHED; ngược lại rỗng. */
    Optional<ShowtimeRef> findVisibleShowtime(UUID showtimeId) {
        return jdbc.sql("""
                        SELECT s.id, s.event_id, s.seat_layout_id, s.status, s.sale_opens_at, s.sale_closes_at, s.ends_at
                        FROM showtime s
                        JOIN event e ON e.id = s.event_id
                        WHERE s.id = :id AND s.status <> 'DRAFT' AND e.status = 'PUBLISHED'
                        """)
                .param("id", showtimeId)
                .query((rs, n) -> new ShowtimeRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("seat_layout_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class)))
                .optional();
    }

    /**
     * Layout tĩnh của suất: các khu theo {@code name} kèm hạng giá, ghế của khu ngồi theo {@code row_label},
     * {@code number} (ghế đôi lấy từ {@code seat_pair} cả hai chiều). Hai câu truy vấn, không phụ thuộc giờ hay
     * trạng thái ghế nên cache được.
     */
    SeatmapLayout findLayout(ShowtimeRef showtime) {
        record ZoneBase(UUID id, String name, ZoneKind kind, PriceTierResult tier) {
        }
        List<ZoneBase> zones = jdbc.sql("""
                        SELECT z.id, z.name, z.kind, pt.id AS tier_id, pt.name AS tier_name, pt.price, pt.color
                        FROM zone z
                        LEFT JOIN zone_price zp ON zp.event_id = :eventId AND zp.zone_id = z.id
                        LEFT JOIN price_tier pt ON pt.id = zp.price_tier_id
                        WHERE z.seat_layout_id = :layoutId
                        ORDER BY z.name, z.id
                        """)
                .param("eventId", showtime.eventId())
                .param("layoutId", showtime.seatLayoutId())
                .query((rs, n) -> new ZoneBase(
                        rs.getObject("id", UUID.class), rs.getString("name"), ZoneKind.valueOf(rs.getString("kind")),
                        tier(rs).orElse(null)))
                .list();
        Map<UUID, List<LayoutSeat>> seatsByZone = new HashMap<>();
        jdbc.sql("""
                        SELECT s.zone_id, s.id, s.row_label, s.number, s.x, s.y, sty.code AS seat_type,
                               COALESCE(pa.seat_b_id, pb.seat_a_id) AS pair_seat_id
                        FROM zone z
                        JOIN seat s ON s.zone_id = z.id
                        JOIN seat_type sty ON sty.id = s.seat_type_id
                        LEFT JOIN seat_pair pa ON pa.seat_a_id = s.id
                        LEFT JOIN seat_pair pb ON pb.seat_b_id = s.id
                        WHERE z.seat_layout_id = :layoutId AND z.kind = 'SEATED'
                        ORDER BY z.name, z.id, s.row_label, s.number
                        """)
                .param("layoutId", showtime.seatLayoutId())
                .query((rs, n) -> {
                    seatsByZone.computeIfAbsent(rs.getObject("zone_id", UUID.class), k -> new ArrayList<>())
                            .add(new LayoutSeat(
                                    rs.getObject("id", UUID.class), rs.getString("row_label"), rs.getInt("number"),
                                    rs.getString("seat_type"), rs.getObject("pair_seat_id", UUID.class),
                                    rs.getInt("x"), rs.getInt("y")));
                    return null;
                })
                .list();
        return new SeatmapLayout(zones.stream()
                .map(z -> new LayoutZone(z.id(), z.name(), z.kind(), z.tier(),
                        seatsByZone.getOrDefault(z.id(), List.of())))
                .toList());
    }

    /**
     * Trạng thái hiện tại của các ghế có dòng {@code showtime_seat}; HELD quá hạn tại {@code now} báo AVAILABLE.
     * Ghế thiếu dòng không có trong kết quả. {@code zoneId} khác null thì chỉ lấy ghế của khu đó. Luôn đọc DB.
     */
    Map<UUID, SeatState> findSeatStates(UUID showtimeId, UUID zoneId, OffsetDateTime now) {
        StatementSpec spec = jdbc.sql("""
                        SELECT ss.seat_id,
                               CASE WHEN ss.status = 'HELD' AND ss.held_until < :now THEN 'AVAILABLE'
                                    ELSE ss.status END AS state
                        FROM showtime_seat ss
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
                        LEFT JOIN showtime_zone_stock st ON st.showtime_id = :showtimeId AND st.zone_id = z.id
                        WHERE z.seat_layout_id = :layoutId AND z.kind = 'STANDING'
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
     * dòng {@code showtime_seat} không tính); khu đứng lấy {@code capacity - sold - held}.
     */
    List<ZoneRow> countZones(ShowtimeRef showtime, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT z.id, z.name, z.kind, pt.id AS tier_id, pt.name AS tier_name, pt.price, pt.color,
                               st.zone_id IS NULL AS stock_missing,
                               CASE WHEN z.kind = 'STANDING' THEN COALESCE(st.capacity - st.sold - st.held, 0)
                                    ELSE COUNT(ss.seat_id) FILTER (WHERE %s) END AS seats_left,
                               COUNT(s.id) FILTER (WHERE ss.seat_id IS NULL) AS missing_seats
                        FROM zone z
                        LEFT JOIN zone_price zp ON zp.event_id = :eventId AND zp.zone_id = z.id
                        LEFT JOIN price_tier pt ON pt.id = zp.price_tier_id
                        LEFT JOIN showtime_zone_stock st ON st.showtime_id = :showtimeId AND st.zone_id = z.id
                        LEFT JOIN seat s ON s.zone_id = z.id AND z.kind = 'SEATED'
                        LEFT JOIN showtime_seat ss ON ss.showtime_id = :showtimeId AND ss.seat_id = s.id
                        WHERE z.seat_layout_id = :layoutId
                        GROUP BY z.id, z.name, z.kind, pt.id, pt.name, pt.price, pt.color,
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
                            rs.getObject("id", UUID.class), rs.getString("name"), kind, tier(rs),
                            rs.getInt("seats_left"), kind == ZoneKind.STANDING && rs.getBoolean("stock_missing"),
                            rs.getInt("missing_seats"));
                }).list();
    }

    /**
     * Ghế trống (Implicit Status) của khu SEATED thuộc layout của suất, theo {@code row_label}, {@code number}.
     * Ghế đôi chỉ tính là trống khi cả hai ghế của cặp đều trống (thiếu dòng {@code showtime_seat} là không
     * trống), vì booking tự giữ cả cặp. Khu đứng, khu không thuộc suất hoặc suất không có thì rỗng.
     */
    List<FreeSeat> findFreeSeats(UUID showtimeId, UUID zoneId, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT s.id, s.row_label, s.number
                        FROM showtime sh
                        JOIN zone z ON z.seat_layout_id = sh.seat_layout_id AND z.id = :zoneId AND z.kind = 'SEATED'
                        JOIN seat s ON s.zone_id = z.id
                        JOIN showtime_seat ss ON ss.showtime_id = sh.id AND ss.seat_id = s.id AND %s
                        WHERE sh.id = :showtimeId
                          AND NOT EXISTS (
                              SELECT 1 FROM seat_pair sp
                              LEFT JOIN showtime_seat ps ON ps.showtime_id = sh.id
                                   AND ps.seat_id = CASE WHEN sp.seat_a_id = s.id THEN sp.seat_b_id
                                                         ELSE sp.seat_a_id END
                              WHERE (sp.seat_a_id = s.id OR sp.seat_b_id = s.id)
                                AND (ps.seat_id IS NULL OR NOT %s))
                        ORDER BY s.row_label, s.number
                        """.formatted(FREE_SEAT_SQL, freeSeatSql("ps")))
                .param("showtimeId", showtimeId)
                .param("zoneId", zoneId)
                .param("now", now)
                .query((rs, n) -> new FreeSeat(
                        rs.getObject("id", UUID.class), rs.getString("row_label"), rs.getInt("number")))
                .list();
    }

    /** Ghế trống, dùng để chọn nhóm ghế gợi ý. */
    record FreeSeat(UUID id, String row, int number) {
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
