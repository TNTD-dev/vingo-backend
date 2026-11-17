package vingo.catalog.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventPublishingRepository {
    private final JdbcClient jdbc;
    public EventPublishingRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public List<UUID> lockShowtimes(UUID event) {
        return jdbc.sql("SELECT id FROM showtime WHERE event_id=:event ORDER BY id FOR UPDATE")
                .param("event",event).query(UUID.class).list();
    }
    public boolean complete(UUID event,OffsetDateTime now) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM showtime WHERE event_id=:event AND status<>'CANCELLED')
            AND NOT EXISTS (
              SELECT 1 FROM event e WHERE e.id=:event AND e.seat_layout_id IS NOT NULL AND (
                (e.category='FOOTBALL' AND
                 (SELECT count(*) FROM showtime WHERE event_id=e.id AND status<>'CANCELLED')<>1)
                OR NOT EXISTS (
                  SELECT 1 FROM seat_layout l JOIN venue_overview o ON o.id=l.overview_id
                  WHERE l.id=e.seat_layout_id AND l.venue_id=e.venue_id
                    AND l.layout_type=e.category AND o.status='PUBLISHED'
                )
              )
            )
            AND NOT EXISTS (
              SELECT 1 FROM showtime s
              JOIN seat_layout l ON l.id=s.seat_layout_id
              JOIN venue v ON v.id=l.venue_id
              WHERE s.event_id=:event AND s.status<>'CANCELLED' AND (
                l.status<>'PUBLISHED' OR v.deleted_at IS NOT NULL
                OR s.ends_at<=:now OR s.ends_at<=s.starts_at
                OR s.sale_closes_at<=s.sale_opens_at OR s.sale_closes_at>s.ends_at
                OR NOT EXISTS(SELECT 1 FROM zone z WHERE z.seat_layout_id=l.id)
                OR EXISTS (
                  SELECT 1 FROM zone z LEFT JOIN zone_price zp ON zp.zone_id=z.id AND zp.event_id=:event
                  LEFT JOIN price_tier pt ON pt.id=zp.price_tier_id AND pt.event_id=:event
                  WHERE z.seat_layout_id=l.id AND (pt.id IS NULL OR pt.price<0)
                )
              )
            )
            """).param("event",event).param("now",now).query(Boolean.class).single();
    }
    public void publish(UUID event,OffsetDateTime now) {
        jdbc.sql("""
            INSERT INTO showtime_seat(showtime_id,seat_id,status)
            SELECT s.id,seat.id,'AVAILABLE' FROM showtime s
            JOIN zone z ON z.seat_layout_id=s.seat_layout_id AND z.kind='SEATED'
            JOIN seat ON seat.zone_id=z.id
            WHERE s.event_id=:event AND s.status<>'CANCELLED'
            ON CONFLICT(showtime_id,seat_id) DO NOTHING
            """).param("event",event).update();
        jdbc.sql("""
            INSERT INTO showtime_zone_stock(showtime_id,zone_id,capacity,sold,held)
            SELECT s.id,z.id,z.capacity,0,0 FROM showtime s
            JOIN zone z ON z.seat_layout_id=s.seat_layout_id AND z.kind='STANDING'
            WHERE s.event_id=:event AND s.status<>'CANCELLED'
            ON CONFLICT(showtime_id,zone_id) DO NOTHING
            """).param("event",event).update();
        jdbc.sql("UPDATE showtime SET status='SCHEDULED' WHERE event_id=:event AND status='DRAFT'")
                .param("event",event).update();
        jdbc.sql("UPDATE event SET status='PUBLISHED',published_at=:now,updated_at=:now WHERE id=:event")
                .param("event",event).param("now",now).update();
    }

    public record CancellationState(UUID id, String status, OffsetDateTime saleOpensAt, boolean hasOrders) {}

    public List<CancellationState> cancellationStates(UUID event) {
        return jdbc.sql("""
                SELECT s.id,s.status,s.sale_opens_at,
                    EXISTS(SELECT 1 FROM orders o WHERE o.showtime_id=s.id) AS has_orders
                FROM showtime s WHERE s.event_id=:event ORDER BY s.id FOR UPDATE
                """).param("event",event).query((rs,n)->new CancellationState(
                    rs.getObject("id",UUID.class),rs.getString("status"),
                    rs.getObject("sale_opens_at",OffsetDateTime.class),rs.getBoolean("has_orders"))).list();
    }

    public java.util.Optional<UUID> findShowtimeEvent(UUID showtime) {
        return jdbc.sql("SELECT event_id FROM showtime WHERE id=:id").param("id",showtime)
                .query(UUID.class).optional();
    }

    public void cancelShowtime(UUID id) {
        jdbc.sql("UPDATE showtime SET status='CANCELLED' WHERE id=:id").param("id",id).update();
    }

    public void cancelEvent(UUID id,OffsetDateTime now) {
        jdbc.sql("UPDATE event SET status='CANCELLED',updated_at=:now WHERE id=:id")
                .param("id",id).param("now",now).update();
    }

    public void auditCancellation(UUID actor, String action, String entity, UUID id, String reason,
            String before, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO audit_log(actor_id,action,entity,entity_id,reason,at,before,after)
                VALUES(:actor,:action,:entity,:id,:reason,:now,
                  jsonb_build_object('status',CAST(:before AS text)),jsonb_build_object('status','CANCELLED'))
                """).param("actor",actor).param("action",action).param("entity",entity).param("id",id)
                .param("reason",reason).param("before",before).param("now",now).update();
    }
}
