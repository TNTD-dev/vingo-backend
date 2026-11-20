package vingo.catalog.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    private final SeatLayoutSvgParser parser;

    SeatLayoutRepository(JdbcClient jdbc, JdbcTemplate batches, SeatLayoutSvgParser parser) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.parser = parser;
    }

    public UUID insertZone(UUID seatMapId, ParsedZone zone) {
        return jdbc.sql("""
                INSERT INTO zone (seat_map_id, code, name, kind, capacity)
                VALUES (:seatMapId, :code, :name, CAST(:kind AS zone_kind), :capacity)
                RETURNING id
                """)
                .param("seatMapId", seatMapId)
                .param("code", zone.code())
                .param("name", zone.label())
                .param("kind", zone.kind().name())
                .param("capacity", zone.kind() == ZoneKind.STANDING ? zone.capacity() : null)
                .query(UUID.class)
                .single();
    }

    public void insertSeats(UUID seatMapId, UUID zoneId, List<ParsedSeat> seats) {
        batches.batchUpdate("""
                INSERT INTO seat (seat_map_id, zone_id, row_label, number, zone_kind)
                VALUES (?, ?, ?, ?, 'SEATED')
                """, seats, 250, (statement, seat) -> {
            statement.setObject(1, seatMapId);
            statement.setObject(2, zoneId);
            statement.setString(3, seat.rowLabel());
            statement.setInt(4, seat.number());
        });
    }

    boolean layoutExists(UUID layoutId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM seat_map WHERE id = :id)")
                .param("id", layoutId)
                .query(Boolean.class)
                .single();
    }

    List<String[]> zoneRows(UUID layoutId) {
        return jdbc.sql("""
                SELECT code, name, kind::text, capacity
                FROM zone WHERE seat_map_id = :id
                ORDER BY code, id
                """)
                .param("id", layoutId)
                .query((rs, row) -> new String[] {
                        rs.getString("code"),
                        rs.getString("name"),
                        "",
                        "",
                        rs.getString("kind"),
                        rs.getObject("capacity") == null ? "" : rs.getString("capacity"),
                        ""
                })
                .list();
    }

    List<String[]> seatRows(UUID layoutId) {
        return jdbc.sql("""
                SELECT z.code, s.row_label, s.number
                FROM seat s JOIN zone z ON z.id = s.zone_id
                WHERE s.seat_map_id = :id AND z.kind = 'SEATED'
                ORDER BY z.code, s.row_label, s.number, s.id
                """)
                .param("id", layoutId)
                .query((rs, row) -> new String[] {
                        rs.getString("code"),
                        rs.getString("row_label"),
                        String.valueOf(rs.getInt("number")),
                        "",
                        ""
                })
                .list();
    }

    public record LayoutHeaderRow(
            UUID id,
            UUID venueId,
            UUID overviewId,
            String layoutType,
            String name,
            long version,
            OffsetDateTime createdAt,
            Bounds viewBox,
            int zoneCount,
            int seatCount
    ) {}

    public record ZoneSummaryRow(
            UUID id,
            String code,
            String label,
            ZoneKind kind,
            int capacity
    ) {}

    public record SeatPointRow(UUID id, String rowLabel, int number) {}

    public record LayoutPosition(long version, UUID id) {}

    public record LayoutAttachmentRow(UUID id, UUID venueId) {}

    public record ShowtimeData(
            UUID seatLayoutId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime saleOpensAt,
            OffsetDateTime saleClosesAt
    ) {}

    public record ShowtimeRow(
            UUID id,
            UUID eventId,
            OffsetDateTime cancelledAt,
            ShowtimeData data
    ) {}

    Optional<LayoutHeaderRow> findLayoutHeader(UUID layoutId) {
        return jdbc.sql("""
                SELECT sm.id, sm.venue_id, sm.venue_map_id, sm.layout_type, sm.name, sm.version, sm.svg, sm.created_at,
                       (SELECT COUNT(*) FROM zone z WHERE z.seat_map_id = sm.id) AS zone_count,
                       (SELECT COUNT(*) FROM seat s WHERE s.seat_map_id = sm.id) AS seat_count
                FROM seat_map sm
                WHERE sm.id = :id
                """)
                .param("id", layoutId)
                .query((rs, row) -> mapHeader(rs))
                .optional();
    }

    List<ZoneSummaryRow> findZones(UUID layoutId) {
        return jdbc.sql("""
                SELECT id, code, name, kind::text AS kind, COALESCE(capacity, 0) AS capacity
                FROM zone
                WHERE seat_map_id = :layoutId
                ORDER BY code, id
                """)
                .param("layoutId", layoutId)
                .query((rs, row) -> mapZone(rs))
                .list();
    }

    Optional<ZoneSummaryRow> findZone(UUID layoutId, UUID zoneId) {
        return jdbc.sql("""
                SELECT id, code, name, kind::text AS kind, COALESCE(capacity, 0) AS capacity
                FROM zone
                WHERE seat_map_id = :layoutId AND id = :zoneId
                """)
                .param("layoutId", layoutId)
                .param("zoneId", zoneId)
                .query((rs, row) -> mapZone(rs))
                .optional();
    }

    private LayoutHeaderRow mapHeader(ResultSet rs) throws SQLException {
        String svg = rs.getString("svg");
        Bounds viewBox = null;
        if (svg != null && !svg.isBlank()) {
            try {
                viewBox = parser.parse(svg.getBytes(StandardCharsets.UTF_8)).viewBox();
            } catch (RuntimeException ignored) {
                viewBox = null;
            }
        }
        return new LayoutHeaderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("venue_id", UUID.class),
                rs.getObject("venue_map_id", UUID.class),
                rs.getString("layout_type"),
                rs.getString("name"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                viewBox,
                rs.getInt("zone_count"),
                rs.getInt("seat_count")
        );
    }

    private static ZoneSummaryRow mapZone(ResultSet rs) throws SQLException {
        return new ZoneSummaryRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                ZoneKind.valueOf(rs.getString("kind")),
                rs.getInt("capacity")
        );
    }

    Optional<String> findSvgContent(UUID layoutId) {
        return jdbc.sql("SELECT svg FROM seat_map WHERE id = :id")
                .param("id", layoutId)
                .query(String.class)
                .optional();
    }

    List<SeatPointRow> findZoneSeats(UUID zoneId) {
        return jdbc.sql("""
                SELECT id, row_label, number
                FROM seat
                WHERE zone_id = :zoneId
                ORDER BY row_label, number, id
                """)
                .param("zoneId", zoneId)
                .query((rs, row) -> new SeatPointRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("row_label"),
                        rs.getInt("number")
                ))
                .list();
    }

    boolean activeVenueExists(UUID venueId) {
        return jdbc.sql("""
                SELECT count(*) > 0 FROM (
                    SELECT 1 FROM venue v
                    WHERE v.id = :venueId AND v.deleted_at IS NULL
                    FOR UPDATE OF v
                ) locked
                """)
                .param("venueId", venueId)
                .query(Boolean.class)
                .single();
    }

    List<LayoutHeaderRow> findByVenue(UUID venueId, String type, LayoutPosition after, int fetchSize) {
        return jdbc.sql("""
                SELECT sm.id, sm.venue_id, sm.venue_map_id, sm.layout_type, sm.name, sm.version, sm.svg, sm.created_at,
                       (SELECT COUNT(*) FROM zone z WHERE z.seat_map_id = sm.id) AS zone_count,
                       (SELECT COUNT(*) FROM seat s WHERE s.seat_map_id = sm.id) AS seat_count
                FROM seat_map sm
                WHERE sm.venue_id = :venueId
                  AND (CAST(:type AS text) IS NULL OR sm.layout_type = :type)
                  AND (
                      CAST(:afterVersion AS bigint) IS NULL
                      OR (sm.version, sm.id) < (CAST(:afterVersion AS bigint), CAST(:afterId AS uuid))
                  )
                ORDER BY sm.version DESC, sm.id DESC
                LIMIT :fetchSize
                """)
                .param("venueId", venueId)
                .param("type", type)
                .param("afterVersion", after == null ? null : after.version())
                .param("afterId", after == null ? null : after.id())
                .param("fetchSize", fetchSize)
                .query((rs, row) -> mapHeader(rs))
                .list();
    }

    Optional<String> lockEventStatus(UUID eventId) {
        return jdbc.sql("SELECT status::text FROM event WHERE id = :id FOR UPDATE")
                .param("id", eventId)
                .query(String.class)
                .optional();
    }

    boolean matchesSelectedEventLayout(UUID eventId, UUID layoutId) {
        return jdbc.sql("SELECT seat_map_id IS NULL OR seat_map_id = :layout FROM event WHERE id = :event")
                .param("event", eventId).param("layout", layoutId).query(Boolean.class).single();
    }

    boolean footballEventHasShowtime(UUID eventId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM event e JOIN showtime s ON s.event_id = e.id
                    WHERE e.id = :id AND e.category = 'FOOTBALL' AND s.cancelled_at IS NULL
                )
                """).param("id", eventId).query(Boolean.class).single();
    }

    Optional<UUID> findEventIdForShowtime(UUID showtimeId) {
        return jdbc.sql("SELECT event_id FROM showtime WHERE id = :id")
                .param("id", showtimeId).query(UUID.class).optional();
    }

    boolean hasAllocatedInventoryOrOrders(UUID showtimeId) {
        jdbc.sql("SELECT seat_id FROM seat_inventory WHERE showtime_id = :id ORDER BY seat_id FOR UPDATE")
                .param("id", showtimeId).query(UUID.class).list();
        jdbc.sql("SELECT zone_id FROM standing_inventory WHERE showtime_id = :id ORDER BY zone_id FOR UPDATE")
                .param("id", showtimeId).query(UUID.class).list();
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM ticket_order WHERE showtime_id = :id)
                    OR EXISTS (SELECT 1 FROM seat_inventory
                               WHERE showtime_id = :id AND status IN ('HELD', 'SOLD', 'BLOCKED'))
                    OR EXISTS (SELECT 1 FROM standing_inventory
                               WHERE showtime_id = :id AND (held > 0 OR sold > 0))
                """).param("id", showtimeId).query(Boolean.class).single();
    }

    Optional<LayoutAttachmentRow> findLayoutForAttach(UUID layoutId) {
        return jdbc.sql("""
                SELECT sm.id, sm.venue_id
                FROM seat_map sm
                JOIN venue v ON v.id = sm.venue_id
                WHERE sm.id = :id AND v.deleted_at IS NULL
                """)
                .param("id", layoutId)
                .query((rs, row) -> new LayoutAttachmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("venue_id", UUID.class)
                ))
                .optional();
    }

    ShowtimeRow insertShowtime(UUID eventId, ShowtimeData data) {
        jdbc.sql("UPDATE event SET seat_map_id = :layoutId WHERE id = :eventId AND seat_map_id IS NULL")
                .param("eventId", eventId)
                .param("layoutId", data.seatLayoutId())
                .update();
        return jdbc.sql("""
                INSERT INTO showtime (
                    event_id, seat_map_id,
                    starts_at, ends_at, sale_opens_at, sale_closes_at
                )
                VALUES (
                    :eventId, :layoutId,
                    :startsAt, :endsAt, :saleOpensAt, :saleClosesAt
                )
                RETURNING id, event_id, cancelled_at, seat_map_id,
                          starts_at, ends_at, sale_opens_at, sale_closes_at
                """)
                .param("eventId", eventId)
                .param("layoutId", data.seatLayoutId())
                .param("startsAt", data.startsAt())
                .param("endsAt", data.endsAt())
                .param("saleOpensAt", data.saleOpensAt())
                .param("saleClosesAt", data.saleClosesAt())
                .query((rs, row) -> mapShowtime(rs))
                .single();
    }

    Optional<ShowtimeRow> lockShowtime(UUID showtimeId) {
        return jdbc.sql("""
                SELECT id, event_id, cancelled_at, seat_map_id,
                       starts_at, ends_at, sale_opens_at, sale_closes_at
                FROM showtime
                WHERE id = :id
                FOR UPDATE
                """)
                .param("id", showtimeId)
                .query((rs, row) -> mapShowtime(rs))
                .optional();
    }

    ShowtimeRow updateShowtime(UUID showtimeId, ShowtimeData data, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE showtime
                SET seat_map_id = :layoutId,
                    starts_at = :startsAt,
                    ends_at = :endsAt,
                    sale_opens_at = :saleOpensAt,
                    sale_closes_at = :saleClosesAt,
                    updated_at = :now
                WHERE id = :id
                RETURNING id, event_id, cancelled_at, seat_map_id,
                          starts_at, ends_at, sale_opens_at, sale_closes_at
                """)
                .param("id", showtimeId)
                .param("layoutId", data.seatLayoutId())
                .param("startsAt", data.startsAt())
                .param("endsAt", data.endsAt())
                .param("saleOpensAt", data.saleOpensAt())
                .param("saleClosesAt", data.saleClosesAt())
                .param("now", now)
                .query((rs, row) -> mapShowtime(rs))
                .single();
    }

    void initShowtimeInventory(UUID showtimeId, UUID layoutId) {
        jdbc.sql("""
                INSERT INTO seat_inventory (showtime_id, seat_id, zone_id, seat_map_id, status)
                SELECT :showtimeId, s.id, s.zone_id, :layoutId, 'AVAILABLE'
                FROM seat s
                WHERE s.seat_map_id = :layoutId
                """)
                .param("showtimeId", showtimeId)
                .param("layoutId", layoutId)
                .update();

        jdbc.sql("""
                INSERT INTO standing_inventory (showtime_id, zone_id, seat_map_id, capacity, sold, held)
                SELECT :showtimeId, z.id, :layoutId, z.capacity, 0, 0
                FROM zone z
                WHERE z.seat_map_id = :layoutId AND z.kind = 'STANDING'
                """)
                .param("showtimeId", showtimeId)
                .param("layoutId", layoutId)
                .update();
    }

    void replaceShowtimeInventory(UUID showtimeId, UUID layoutId) {
        jdbc.sql("DELETE FROM seat_inventory WHERE showtime_id = :showtimeId")
                .param("showtimeId", showtimeId)
                .update();
        jdbc.sql("DELETE FROM standing_inventory WHERE showtime_id = :showtimeId")
                .param("showtimeId", showtimeId)
                .update();
        initShowtimeInventory(showtimeId, layoutId);
    }

    private static ShowtimeRow mapShowtime(ResultSet rs) throws SQLException {
        return new ShowtimeRow(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("cancelled_at", OffsetDateTime.class),
                new ShowtimeData(
                        rs.getObject("seat_map_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class)
                )
        );
    }
}
