package vingo.catalog.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.catalog.internal.SeatLayoutSvgParser.Bounds;
import vingo.catalog.internal.SeatLayoutSvgParser.ParsedLayout;
import vingo.catalog.internal.SeatLayoutSvgParser.ParsedSeat;
import vingo.catalog.internal.SeatLayoutSvgParser.ParsedZone;
import vingo.catalog.internal.SeatLayoutSvgParser.ZoneKind;

@Repository
public class SeatLayoutRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;

    SeatLayoutRepository(JdbcClient jdbc, JdbcTemplate batches) {
        this.jdbc = jdbc;
        this.batches = batches;
    }

    boolean lockActiveVenueForHall(UUID hallId) {
        return jdbc.sql("""
                SELECT v.id
                FROM hall h JOIN venue v ON v.id = h.venue_id
                WHERE h.id = :hallId AND v.deleted_at IS NULL
                FOR UPDATE OF v
                """)
                .param("hallId", hallId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    LayoutVersion insertDraft(UUID hallId) {
        return jdbc.sql("INSERT INTO seat_layout (hall_id) VALUES (:hallId) RETURNING id, version")
                .param("hallId", hallId)
                .query((rs, row) -> new LayoutVersion(rs.getObject("id", UUID.class), rs.getLong("version")))
                .single();
    }

    void insertSvg(UUID layoutId, ParsedLayout layout) {
        var viewBox = layout.viewBox();
        jdbc.sql("""
                INSERT INTO seat_layout_svg (
                    seat_layout_id, schema_version, svg_content,
                    view_box_x, view_box_y, view_box_width, view_box_height
                )
                VALUES (:id, :schemaVersion, :content, :x, :y, :width, :height)
                """)
                .param("id", layoutId)
                .param("schemaVersion", layout.schemaVersion())
                .param("content", layout.svgContent())
                .param("x", viewBox.x())
                .param("y", viewBox.y())
                .param("width", viewBox.width())
                .param("height", viewBox.height())
                .update();
    }

    UUID insertZone(UUID layoutId, ParsedZone zone) {
        var bounds = zone.bounds();
        return jdbc.sql("""
                INSERT INTO zone (
                    seat_layout_id, code, name, stand_code, level, kind, capacity,
                    svg_id, content_svg_id, pitch_side_svg_id,
                    bounds_x, bounds_y, bounds_width, bounds_height
                )
                VALUES (
                    :layoutId, :code, :name, :stand, :level, :kind, :capacity,
                    :svgId, :contentId, :pitchId, :x, :y, :width, :height
                )
                RETURNING id
                """)
                .param("layoutId", layoutId)
                .param("code", zone.code())
                .param("name", zone.label())
                .param("stand", zone.standCode())
                .param("level", zone.level())
                .param("kind", zone.kind().name())
                .param("capacity", zone.capacity())
                .param("svgId", zone.svgId())
                .param("contentId", zone.contentSvgId())
                .param("pitchId", zone.pitchSideSvgId())
                .param("x", bounds.x())
                .param("y", bounds.y())
                .param("width", bounds.width())
                .param("height", bounds.height())
                .query(UUID.class)
                .single();
    }

    UUID standardSeatType() {
        return jdbc.sql("SELECT id FROM seat_type WHERE code = 'STANDARD'").query(UUID.class).single();
    }

    void insertSeats(UUID zoneId, UUID seatTypeId, List<ParsedSeat> seats) {
        batches.batchUpdate("""
                INSERT INTO seat (zone_id, seat_type_id, row_label, number, x, y)
                VALUES (?, ?, ?, ?, ?, ?)
                """, seats, 250, (statement, seat) -> {
            statement.setObject(1, zoneId);
            statement.setObject(2, seatTypeId);
            statement.setString(3, seat.rowLabel());
            statement.setInt(4, seat.number());
            statement.setBigDecimal(5, seat.x());
            statement.setBigDecimal(6, seat.y());
        });
    }

    boolean layoutExists(UUID layoutId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM seat_layout WHERE id = :id)")
                .param("id", layoutId)
                .query(Boolean.class)
                .single();
    }

    List<String[]> zoneRows(UUID layoutId) {
        return jdbc.sql("""
                SELECT COALESCE(code, name), name, stand_code, level, kind, capacity, svg_id
                FROM zone WHERE seat_layout_id = :id
                ORDER BY COALESCE(code, name), id
                """)
                .param("id", layoutId)
                .query((rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)})
                .list();
    }

    List<String[]> seatRows(UUID layoutId) {
        return jdbc.sql("""
                SELECT COALESCE(z.code, z.name), s.row_label, s.number, s.x, s.y
                FROM seat s JOIN zone z ON z.id = s.zone_id
                WHERE z.seat_layout_id = :id AND z.kind = 'SEATED'
                ORDER BY COALESCE(z.code, z.name), s.row_label, s.number, s.id
                """)
                .param("id", layoutId)
                .query((rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).toPlainString(), rs.getBigDecimal(5).toPlainString()})
                .list();
    }

    record LayoutVersion(UUID id, long version) {}

    public record LayoutHeaderRow(
            UUID id,
            UUID hallId,
            String status,
            long version,
            Integer schemaVersion,
            OffsetDateTime publishedAt,
            Bounds viewBox,
            int zoneCount,
            int seatCount
    ) {}

    public record ZoneSummaryRow(
            UUID id,
            String code,
            String label,
            String standCode,
            Integer level,
            ZoneKind kind,
            int capacity,
            String svgId,
            String contentSvgId,
            String pitchSideSvgId,
            Bounds bounds
    ) {}

    public record SeatPointRow(UUID id, String rowLabel, int number, BigDecimal x, BigDecimal y) {}

    public record LayoutPosition(long version, UUID id) {}

    public record LayoutAttachmentRow(UUID id, UUID hallId, String status) {}

    public record ShowtimeData(
            UUID hallId,
            UUID seatLayoutId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime saleOpensAt,
            OffsetDateTime saleClosesAt
    ) {}

    public record ShowtimeRow(
            UUID id,
            UUID eventId,
            String status,
            ShowtimeData data
    ) {}

    Optional<LayoutHeaderRow> findLayoutHeader(UUID layoutId) {
        return jdbc.sql("""
                SELECT sl.id, sl.hall_id, sl.status, sl.version, sl.published_at,
                       svg.schema_version,
                       svg.view_box_x, svg.view_box_y, svg.view_box_width, svg.view_box_height,
                       (SELECT COUNT(*) FROM zone z WHERE z.seat_layout_id = sl.id) AS zone_count,
                       (SELECT COUNT(*) FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = sl.id) AS seat_count
                FROM seat_layout sl
                LEFT JOIN seat_layout_svg svg ON svg.seat_layout_id = sl.id
                WHERE sl.id = :id
                """)
                .param("id", layoutId)
                .query((rs, row) -> new LayoutHeaderRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("hall_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getObject("schema_version", Integer.class),
                        rs.getObject("published_at", OffsetDateTime.class),
                        readBounds(rs, "view_box_x", "view_box_y", "view_box_width", "view_box_height"),
                        rs.getInt("zone_count"),
                        rs.getInt("seat_count")
                ))
                .optional();
    }
    List<ZoneSummaryRow> findZones(UUID layoutId) {
        return jdbc.sql("""
                SELECT id, COALESCE(code, name) AS code, name, stand_code, level, kind, capacity,
                       svg_id, content_svg_id, pitch_side_svg_id,
                       bounds_x, bounds_y, bounds_width, bounds_height
                FROM zone
                WHERE seat_layout_id = :layoutId
                ORDER BY COALESCE(code, name), id
                """)
                .param("layoutId", layoutId)
                .query((rs, row) -> mapZone(rs))
                .list();
    }

    Optional<ZoneSummaryRow> findZone(UUID layoutId, UUID zoneId) {
        return jdbc.sql("""
                SELECT id, COALESCE(code, name) AS code, name, stand_code, level, kind, capacity,
                       svg_id, content_svg_id, pitch_side_svg_id,
                       bounds_x, bounds_y, bounds_width, bounds_height
                FROM zone
                WHERE seat_layout_id = :layoutId AND id = :zoneId
                """)
                .param("layoutId", layoutId)
                .param("zoneId", zoneId)
                .query((rs, row) -> mapZone(rs))
                .optional();
    }

    private static ZoneSummaryRow mapZone(ResultSet rs) throws SQLException {
        return new ZoneSummaryRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("stand_code"),
                rs.getObject("level", Integer.class),
                ZoneKind.valueOf(rs.getString("kind")),
                rs.getInt("capacity"),
                rs.getString("svg_id"),
                rs.getString("content_svg_id"),
                rs.getString("pitch_side_svg_id"),
                readBounds(rs, "bounds_x", "bounds_y", "bounds_width", "bounds_height")
        );
    }

    private static Bounds readBounds(ResultSet rs, String xCol, String yCol, String wCol, String hCol)
            throws SQLException {
        BigDecimal x = rs.getBigDecimal(xCol);
        BigDecimal y = rs.getBigDecimal(yCol);
        BigDecimal width = rs.getBigDecimal(wCol);
        BigDecimal height = rs.getBigDecimal(hCol);
        if (x == null || y == null || width == null || height == null) {
            return null;
        }
        return new Bounds(x, y, width, height);
    }

    Optional<String> findSvgContent(UUID layoutId) {
        return jdbc.sql("SELECT svg_content FROM seat_layout_svg WHERE seat_layout_id = :id")
                .param("id", layoutId)
                .query(String.class)
                .optional();
    }

    List<SeatPointRow> findZoneSeats(UUID zoneId) {
        return jdbc.sql("""
                SELECT id, row_label, number, x, y
                FROM seat
                WHERE zone_id = :zoneId
                ORDER BY row_label, number, id
                """)
                .param("zoneId", zoneId)
                .query((rs, row) -> new SeatPointRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("row_label"),
                        rs.getInt("number"),
                        rs.getBigDecimal("x"),
                        rs.getBigDecimal("y")
                ))
                .list();
    }

    Optional<UUID> lockPublishableLayout(UUID layoutId) {
        return jdbc.sql("""
                SELECT sl.id
                FROM seat_layout sl
                JOIN hall h ON h.id = sl.hall_id
                JOIN venue v ON v.id = h.venue_id
                WHERE sl.id = :id AND v.deleted_at IS NULL
                FOR UPDATE OF sl
                """)
                .param("id", layoutId)
                .query(UUID.class)
                .optional();
    }

    void markPublished(UUID layoutId, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE seat_layout
                SET status = 'PUBLISHED',
                    published_at = COALESCE(published_at, :now)
                WHERE id = :id
                """)
                .param("id", layoutId)
                .param("now", now)
                .update();
    }

    boolean activeHallExists(UUID hallId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM hall h
                    JOIN venue v ON v.id = h.venue_id
                    WHERE h.id = :hallId AND v.deleted_at IS NULL
                )
                """)
                .param("hallId", hallId)
                .query(Boolean.class)
                .single();
    }
    
    List<LayoutHeaderRow> findByHall(UUID hallId, LayoutPosition after, int fetchSize) {
        return jdbc.sql("""
                SELECT sl.id, sl.hall_id, sl.status, sl.version, sl.published_at,
                       svg.schema_version,
                       svg.view_box_x, svg.view_box_y, svg.view_box_width, svg.view_box_height,
                       (SELECT COUNT(*) FROM zone z WHERE z.seat_layout_id = sl.id) AS zone_count,
                       (SELECT COUNT(*) FROM seat s JOIN zone z ON z.id = s.zone_id WHERE z.seat_layout_id = sl.id) AS seat_count
                FROM seat_layout sl
                LEFT JOIN seat_layout_svg svg ON svg.seat_layout_id = sl.id
                WHERE sl.hall_id = :hallId
                  AND (
                      CAST(:afterVersion AS bigint) IS NULL
                      OR (sl.version, sl.id) < (CAST(:afterVersion AS bigint), CAST(:afterId AS uuid))
                  )
                ORDER BY sl.version DESC, sl.id DESC
                LIMIT :fetchSize
                """)
                .param("hallId", hallId)
                .param("afterVersion", after == null ? null : after.version())
                .param("afterId", after == null ? null : after.id())
                .param("fetchSize", fetchSize)
                .query((rs, row) -> new LayoutHeaderRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("hall_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getObject("schema_version", Integer.class),
                        rs.getObject("published_at", OffsetDateTime.class),
                        readBounds(rs, "view_box_x", "view_box_y", "view_box_width", "view_box_height"),
                        rs.getInt("zone_count"),
                        rs.getInt("seat_count")
                ))
                .list();
    }

    Optional<String> findEventStatus(UUID eventId) {
        return jdbc.sql("SELECT status FROM event WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .optional();
    }

    Optional<LayoutAttachmentRow> findLayoutForAttach(UUID layoutId) {
        return jdbc.sql("""
                SELECT sl.id, sl.hall_id, sl.status
                FROM seat_layout sl
                JOIN hall h ON h.id = sl.hall_id
                JOIN venue v ON v.id = h.venue_id
                WHERE sl.id = :id AND v.deleted_at IS NULL
                """)
                .param("id", layoutId)
                .query((rs, row) -> new LayoutAttachmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("hall_id", UUID.class),
                        rs.getString("status")
                ))
                .optional();
    }

    ShowtimeRow insertShowtime(UUID eventId, String status, ShowtimeData data) {
        return jdbc.sql("""
                INSERT INTO showtime (
                    event_id, hall_id, seat_layout_id,
                    starts_at, ends_at, sale_opens_at, sale_closes_at, status
                )
                VALUES (
                    :eventId, :hallId, :layoutId,
                    :startsAt, :endsAt, :saleOpensAt, :saleClosesAt, :status
                )
                RETURNING id, event_id, status, hall_id, seat_layout_id,
                          starts_at, ends_at, sale_opens_at, sale_closes_at
                """)
                .param("eventId", eventId)
                .param("hallId", data.hallId())
                .param("layoutId", data.seatLayoutId())
                .param("startsAt", data.startsAt())
                .param("endsAt", data.endsAt())
                .param("saleOpensAt", data.saleOpensAt())
                .param("saleClosesAt", data.saleClosesAt())
                .param("status", status)
                .query((rs, row) -> mapShowtime(rs))
                .single();
    }

    Optional<ShowtimeRow> lockShowtime(UUID showtimeId) {
        return jdbc.sql("""
                SELECT id, event_id, status, hall_id, seat_layout_id,
                       starts_at, ends_at, sale_opens_at, sale_closes_at
                FROM showtime
                WHERE id = :id
                FOR UPDATE
                """)
                .param("id", showtimeId)
                .query((rs, row) -> mapShowtime(rs))
                .optional();
    }

    ShowtimeRow updateShowtime(UUID showtimeId, ShowtimeData data) {
        return jdbc.sql("""
                UPDATE showtime
                SET hall_id = :hallId,
                    seat_layout_id = :layoutId,
                    starts_at = :startsAt,
                    ends_at = :endsAt,
                    sale_opens_at = :saleOpensAt,
                    sale_closes_at = :saleClosesAt
                WHERE id = :id
                RETURNING id, event_id, status, hall_id, seat_layout_id,
                          starts_at, ends_at, sale_opens_at, sale_closes_at
                """)
                .param("id", showtimeId)
                .param("hallId", data.hallId())
                .param("layoutId", data.seatLayoutId())
                .param("startsAt", data.startsAt())
                .param("endsAt", data.endsAt())
                .param("saleOpensAt", data.saleOpensAt())
                .param("saleClosesAt", data.saleClosesAt())
                .query((rs, row) -> mapShowtime(rs))
                .single();
    }

    void initShowtimeInventory(UUID showtimeId, UUID layoutId) {
        jdbc.sql("""
                INSERT INTO showtime_seat (showtime_id, seat_id, status)
                SELECT :showtimeId, s.id, 'AVAILABLE'
                FROM seat s
                JOIN zone z ON z.id = s.zone_id
                WHERE z.seat_layout_id = :layoutId AND z.kind = 'SEATED'
                """)
                .param("showtimeId", showtimeId)
                .param("layoutId", layoutId)
                .update();

        jdbc.sql("""
                INSERT INTO showtime_zone_stock (showtime_id, zone_id, capacity, sold, held)
                SELECT :showtimeId, z.id, z.capacity, 0, 0
                FROM zone z
                WHERE z.seat_layout_id = :layoutId AND z.kind = 'STANDING'
                """)
                .param("showtimeId", showtimeId)
                .param("layoutId", layoutId)
                .update();
    }

    void replaceShowtimeInventory(UUID showtimeId, UUID layoutId) {
        jdbc.sql("DELETE FROM showtime_seat WHERE showtime_id = :showtimeId")
                .param("showtimeId", showtimeId)
                .update();
        jdbc.sql("DELETE FROM showtime_zone_stock WHERE showtime_id = :showtimeId")
                .param("showtimeId", showtimeId)
                .update();
        initShowtimeInventory(showtimeId, layoutId);
    }

    private static ShowtimeRow mapShowtime(ResultSet rs) throws SQLException {
        return new ShowtimeRow(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("status"),
                new ShowtimeData(
                        rs.getObject("hall_id", UUID.class),
                        rs.getObject("seat_layout_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class)
                )
        );
    }
}


