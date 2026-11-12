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
              SELECT 1 FROM showtime s
              JOIN seat_layout l ON l.id=s.seat_layout_id
              JOIN hall h ON h.id=s.hall_id JOIN venue v ON v.id=h.venue_id
              WHERE s.event_id=:event AND s.status<>'CANCELLED' AND (
                l.status<>'PUBLISHED' OR l.hall_id<>s.hall_id OR v.deleted_at IS NOT NULL
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
}
