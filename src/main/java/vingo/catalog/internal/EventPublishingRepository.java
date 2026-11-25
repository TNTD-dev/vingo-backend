package vingo.catalog.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventPublishingRepository {

    private final JdbcClient jdbc;

    public EventPublishingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> lockShowtimes(UUID event) {
        return jdbc.sql("SELECT id FROM showtime WHERE event_id = :event ORDER BY id FOR UPDATE")
                .param("event", event)
                .query(UUID.class)
                .list();
    }

    public boolean complete(UUID event, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM showtime WHERE event_id = :event AND cancelled_at IS NULL)
                AND EXISTS (
                    SELECT 1 FROM event e
                    JOIN seat_map m ON m.id = e.seat_map_id
                    JOIN venue v ON v.id = m.venue_id
                    WHERE e.id = :event
                      AND v.deleted_at IS NULL
                      AND m.layout_type = e.category
                      AND (e.category <> 'FOOTBALL'
                           OR (SELECT count(*) FROM showtime WHERE event_id = e.id AND cancelled_at IS NULL) = 1)
                )
                AND NOT EXISTS (
                    SELECT 1 FROM showtime s
                    JOIN seat_map m ON m.id = s.seat_map_id
                    JOIN venue v ON v.id = m.venue_id
                    WHERE s.event_id = :event AND s.cancelled_at IS NULL AND (
                        v.deleted_at IS NOT NULL
                        OR s.ends_at <= :now OR s.ends_at <= s.starts_at
                        OR s.sale_closes_at <= :now OR s.sale_closes_at <= s.sale_opens_at OR s.sale_closes_at > s.ends_at
                        OR NOT EXISTS (SELECT 1 FROM zone z WHERE z.seat_map_id = m.id)
                        OR EXISTS (
                            SELECT 1 FROM zone z
                            LEFT JOIN event_zone ez ON ez.zone_id = z.id AND ez.event_id = :event
                            LEFT JOIN ticket_type tt ON tt.id = ez.ticket_type_id AND tt.event_id = :event
                            WHERE z.seat_map_id = m.id
                              AND (tt.id IS NULL OR NOT EXISTS (SELECT 1 FROM ticket_type_price ttp WHERE ttp.ticket_type_id = tt.id))
                        )
                    )
                )
                """)
                .param("event", event)
                .param("now", now)
                .query(Boolean.class)
                .single();
    }

    public void publish(UUID event, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO seat_inventory (showtime_id, seat_id, zone_id, seat_map_id, status)
                SELECT s.id, seat.id, seat.zone_id, s.seat_map_id, 'AVAILABLE'
                FROM showtime s
                JOIN seat ON seat.seat_map_id = s.seat_map_id
                WHERE s.event_id = :event AND s.cancelled_at IS NULL
                ON CONFLICT (showtime_id, seat_id) DO NOTHING
                """)
                .param("event", event)
                .update();

        jdbc.sql("""
                INSERT INTO standing_inventory (showtime_id, zone_id, seat_map_id, capacity, sold, held)
                SELECT s.id, z.id, s.seat_map_id, z.capacity, 0, 0
                FROM showtime s
                JOIN zone z ON z.seat_map_id = s.seat_map_id AND z.kind = 'STANDING'
                WHERE s.event_id = :event AND s.cancelled_at IS NULL
                ON CONFLICT (showtime_id, zone_id) DO NOTHING
                """)
                .param("event", event)
                .update();

        jdbc.sql("UPDATE event SET status = 'PUBLISHED', updated_at = :now WHERE id = :event")
                .param("event", event)
                .param("now", now)
                .update();
    }

    public record CancellationState(
            UUID id,
            OffsetDateTime cancelledAt,
            OffsetDateTime saleOpensAt,
            OffsetDateTime endsAt,
            boolean hasOrders
    ) {}

    public List<CancellationState> cancellationStates(UUID event) {
        return jdbc.sql("""
                SELECT s.id, s.cancelled_at, s.sale_opens_at, s.ends_at,
                       EXISTS (SELECT 1 FROM ticket_order o WHERE o.showtime_id = s.id) AS has_orders
                FROM showtime s
                WHERE s.event_id = :event
                ORDER BY s.id FOR UPDATE
                """)
                .param("event", event)
                .query((rs, n) -> new CancellationState(
                        rs.getObject("id", UUID.class),
                        rs.getObject("cancelled_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getBoolean("has_orders")))
                .list();
    }

    public Optional<UUID> findShowtimeEvent(UUID showtime) {
        return jdbc.sql("SELECT event_id FROM showtime WHERE id = :id")
                .param("id", showtime)
                .query(UUID.class)
                .optional();
    }

    public void cancelShowtime(UUID id, OffsetDateTime now) {
        jdbc.sql("UPDATE showtime SET cancelled_at = :now, updated_at = :now WHERE id = :id AND cancelled_at IS NULL")
                .param("id", id)
                .param("now", now)
                .update();
    }

    public void cancelEvent(UUID id, OffsetDateTime now) {
        jdbc.sql("UPDATE event SET status = 'CANCELLED', updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("now", now)
                .update();
    }

    public void auditCancelEvent(UUID actor, UUID id, String reason, String beforeStatus, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, entity, entity_id, reason, at, before, after)
                VALUES (:actor, 'CANCEL_EVENT', 'event', :id, :reason, :now,
                        jsonb_build_object('status', CAST(:before AS text)),
                        jsonb_build_object('status', 'CANCELLED'))
                """)
                .param("actor", actor)
                .param("id", id)
                .param("reason", reason)
                .param("before", beforeStatus)
                .param("now", now)
                .update();
    }

    public void auditCancelShowtime(UUID actor, UUID id, String reason, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, entity, entity_id, reason, at, before, after)
                VALUES (:actor, 'CANCEL_SHOWTIME', 'showtime', :id, :reason, :now,
                        jsonb_build_object('cancelled_at', null),
                        jsonb_build_object('cancelled_at', :now::text))
                """)
                .param("actor", actor)
                .param("id", id)
                .param("reason", reason)
                .param("now", now)
                .update();
    }
}
