package vingo.search.internal;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.search.PriceTierResult;
import vingo.search.VenueResult;

/**
 * Truy vấn chi tiết sự kiện và các suất, chỉ đọc bằng {@link JdbcClient}.
 */
@Repository
class EventDetailRepository {

    record EventRow(
            UUID id,
            String title,
            String description,
            String category,
            String status,
            String posterUrl,
            String homeTeam,
            String awayTeam,
            String league,
            String round,
            int maxTicketsPerOrder) {
    }

    record ShowtimeRow(
            UUID id,
            UUID eventId,
            UUID seatLayoutId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime saleOpensAt,
            OffsetDateTime saleClosesAt,
            String eventStatus,
            OffsetDateTime cancelledAt,
            VenueResult venue) {
    }

    private final JdbcClient jdbc;

    EventDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<EventRow> findPublished(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, title, description, category, status::text AS status,
                               COALESCE(poster_desktop_url, poster_mobile_url) AS poster_url,
                               home_team, away_team, league, round, max_tickets_per_order
                        FROM event
                        WHERE id = :id AND status = 'PUBLISHED'
                        """)
                .param("id", eventId)
                .query((rs, n) -> new EventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("category"),
                        rs.getString("status"),
                        rs.getString("poster_url"),
                        rs.getString("home_team"),
                        rs.getString("away_team"),
                        rs.getString("league"),
                        rs.getString("round"),
                        rs.getInt("max_tickets_per_order")))
                .optional();
    }

    List<PriceTierResult> findPriceTiers(UUID eventId) {
        return jdbc.sql("""
                        SELECT s.id, s.name, COALESCE(sp.price, 0) AS price, s.color
                        FROM sku s
                        LEFT JOIN LATERAL (
                            SELECT price FROM sku_price
                            WHERE sku_id = s.id AND effective_from <= now()
                            ORDER BY effective_from DESC LIMIT 1
                        ) sp ON true
                        WHERE s.event_id = :id
                        ORDER BY price, s.name, s.id
                        """)
                .param("id", eventId)
                .query((rs, n) -> new PriceTierResult(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price"),
                        rs.getString("color")))
                .list();
    }

    List<ShowtimeRow> findShowtimes(UUID eventId) {
        return jdbc.sql("""
                        SELECT s.id, s.event_id, s.seat_map_id, s.starts_at, s.ends_at,
                               s.sale_opens_at, s.sale_closes_at, e.status::text AS event_status, s.cancelled_at,
                               v.id AS venue_id, v.name AS venue_name,
                               v.address AS venue_address, v.city AS venue_city
                        FROM showtime s
                        JOIN event e ON e.id = s.event_id
                        JOIN seat_map m ON m.id = s.seat_map_id
                        JOIN venue v ON v.id = m.venue_id
                        WHERE s.event_id = :id AND e.status <> 'DRAFT'
                        ORDER BY s.starts_at, s.id
                        """)
                .param("id", eventId)
                .query((rs, n) -> new ShowtimeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("seat_map_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                        rs.getString("event_status"),
                        rs.getObject("cancelled_at", OffsetDateTime.class),
                        new VenueResult(
                                rs.getObject("venue_id", UUID.class),
                                rs.getString("venue_name"),
                                rs.getString("venue_address"),
                                rs.getString("venue_city"))))
                .list();
    }

    Set<UUID> findSoldOutShowtimeIds(UUID eventId, OffsetDateTime now) {
        return new HashSet<>(jdbc.sql("""
                        SELECT s.id
                        FROM showtime s
                        JOIN event e ON e.id = s.event_id
                        WHERE s.event_id = :id AND e.status <> 'DRAFT'
                          AND NOT EXISTS (SELECT 1 FROM seat_inventory si
                                          WHERE si.showtime_id = s.id
                                            AND (si.status = 'AVAILABLE'
                                                 OR (si.status = 'HELD' AND si.held_until < :now)))
                          AND NOT EXISTS (SELECT 1 FROM standing_inventory z
                                          WHERE z.showtime_id = s.id AND z.capacity - z.sold - z.held > 0)
                        """)
                .param("id", eventId)
                .param("now", now)
                .query(UUID.class)
                .list());
    }
}
