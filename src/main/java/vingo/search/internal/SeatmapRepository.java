package vingo.search.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

import vingo.search.PriceTierResult;
import vingo.search.SeatResult;
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
     * Một khu. {@code seatsLeft} chỉ có nghĩa với khu đứng ở {@link #findZones} và với mọi khu ở
     * {@link #countZones}. {@code stockMissing} là true khi khu đứng chưa có dòng {@code showtime_zone_stock}.
     */
    record ZoneRow(UUID id, String name, ZoneKind kind, Optional<PriceTierResult> tier, int seatsLeft,
            boolean stockMissing) {
    }

    /** Một ghế kèm cờ {@code missing}: thuộc layout nhưng không có dòng {@code showtime_seat}. */
    record SeatRow(UUID zoneId, SeatResult seat, boolean missing) {
    }

    /** Biểu thức trạng thái ghế: thiếu dòng → BLOCKED; HELD quá hạn → AVAILABLE. */
    private static final String SEAT_STATE_SQL = """
            CASE WHEN ss.seat_id IS NULL THEN 'BLOCKED'
                 WHEN ss.status = 'HELD' AND ss.held_until < :now THEN 'AVAILABLE'
                 ELSE ss.status END""";

    private static final String FREE_SEAT_SQL = "(ss.status = 'AVAILABLE' OR (ss.status = 'HELD' AND ss.held_until < :now))";

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
     * Các khu của layout kèm hạng giá, theo {@code name}. {@code zoneId} khác null thì chỉ lấy khu đó (rỗng nếu
     * khu không thuộc layout). Khu đứng lấy {@code seats_left = capacity - sold - held} từ stock.
     */
    List<ZoneRow> findZones(ShowtimeRef showtime, UUID zoneId) {
        String sql = """
                SELECT z.id, z.name, z.kind, pt.id AS tier_id, pt.name AS tier_name, pt.price, pt.color,
                       st.zone_id IS NULL AS stock_missing,
                       COALESCE(st.capacity - st.sold - st.held, 0) AS seats_left
                FROM zone z
                LEFT JOIN zone_price zp ON zp.event_id = :eventId AND zp.zone_id = z.id
                LEFT JOIN price_tier pt ON pt.id = zp.price_tier_id
                LEFT JOIN showtime_zone_stock st ON st.showtime_id = :showtimeId AND st.zone_id = z.id
                WHERE z.seat_layout_id = :layoutId
                """ + (zoneId != null ? " AND z.id = :zoneId\n" : "") + " ORDER BY z.name, z.id";
        StatementSpec spec = jdbc.sql(sql)
                .param("eventId", showtime.eventId())
                .param("showtimeId", showtime.id())
                .param("layoutId", showtime.seatLayoutId());
        if (zoneId != null) {
            spec = spec.param("zoneId", zoneId);
        }
        return spec.query((rs, n) -> {
            ZoneKind kind = ZoneKind.valueOf(rs.getString("kind"));
            return new ZoneRow(
                    rs.getObject("id", UUID.class), rs.getString("name"), kind, tier(rs),
                    kind == ZoneKind.STANDING ? rs.getInt("seats_left") : 0,
                    kind == ZoneKind.STANDING && rs.getBoolean("stock_missing"));
        }).list();
    }

    /**
     * Toàn bộ ghế của các khu SEATED trong layout, một câu duy nhất, theo khu rồi {@code row_label}, {@code number}.
     * {@code zoneId} khác null thì chỉ lấy ghế của khu đó. Ghế đôi lấy từ {@code seat_pair} cả hai chiều.
     */
    List<SeatRow> findSeats(ShowtimeRef showtime, UUID zoneId, OffsetDateTime now) {
        String sql = """
                SELECT s.zone_id, s.id, s.row_label, s.number, s.x, s.y, sty.code AS seat_type,
                       COALESCE(pa.seat_b_id, pb.seat_a_id) AS pair_seat_id,
                       ss.seat_id IS NULL AS missing,
                       %s AS state
                FROM zone z
                JOIN seat s ON s.zone_id = z.id
                JOIN seat_type sty ON sty.id = s.seat_type_id
                LEFT JOIN seat_pair pa ON pa.seat_a_id = s.id
                LEFT JOIN seat_pair pb ON pb.seat_b_id = s.id
                LEFT JOIN showtime_seat ss ON ss.showtime_id = :showtimeId AND ss.seat_id = s.id
                WHERE z.seat_layout_id = :layoutId AND z.kind = 'SEATED'
                %s
                ORDER BY z.name, z.id, s.row_label, s.number
                """.formatted(SEAT_STATE_SQL, zoneId != null ? "AND z.id = :zoneId" : "");
        StatementSpec spec = jdbc.sql(sql)
                .param("showtimeId", showtime.id())
                .param("layoutId", showtime.seatLayoutId())
                .param("now", now);
        if (zoneId != null) {
            spec = spec.param("zoneId", zoneId);
        }
        return spec.query((rs, n) -> new SeatRow(
                rs.getObject("zone_id", UUID.class),
                new SeatResult(
                        rs.getObject("id", UUID.class),
                        rs.getString("row_label"),
                        rs.getInt("number"),
                        rs.getString("seat_type"),
                        Optional.ofNullable(rs.getObject("pair_seat_id", UUID.class)),
                        SeatState.valueOf(rs.getString("state")),
                        rs.getInt("x"),
                        rs.getInt("y")),
                rs.getBoolean("missing"))).list();
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
                                    ELSE COUNT(ss.seat_id) END AS seats_left
                        FROM zone z
                        LEFT JOIN zone_price zp ON zp.event_id = :eventId AND zp.zone_id = z.id
                        LEFT JOIN price_tier pt ON pt.id = zp.price_tier_id
                        LEFT JOIN showtime_zone_stock st ON st.showtime_id = :showtimeId AND st.zone_id = z.id
                        LEFT JOIN seat s ON s.zone_id = z.id AND z.kind = 'SEATED'
                        LEFT JOIN showtime_seat ss ON ss.showtime_id = :showtimeId AND ss.seat_id = s.id
                                                  AND %s
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
                            rs.getInt("seats_left"), kind == ZoneKind.STANDING && rs.getBoolean("stock_missing"));
                }).list();
    }

    /**
     * Ghế trống (Implicit Status) của khu SEATED thuộc layout của suất, theo {@code row_label}, {@code number}.
     * Khu đứng, khu không thuộc suất hoặc suất không có thì rỗng.
     */
    List<FreeSeat> findFreeSeats(UUID showtimeId, UUID zoneId, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT s.id, s.row_label, s.number
                        FROM showtime sh
                        JOIN zone z ON z.seat_layout_id = sh.seat_layout_id AND z.id = :zoneId AND z.kind = 'SEATED'
                        JOIN seat s ON s.zone_id = z.id
                        JOIN showtime_seat ss ON ss.showtime_id = sh.id AND ss.seat_id = s.id AND %s
                        WHERE sh.id = :showtimeId
                        ORDER BY s.row_label, s.number
                        """.formatted(FREE_SEAT_SQL))
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
