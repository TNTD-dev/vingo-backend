package vingo.catalog.internal;

import java.net.URI;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.catalog.EventDetails;
import vingo.catalog.EventList;
import vingo.catalog.EventService.CreateEvent;
import vingo.catalog.EventService.UpdateEvent;
import vingo.catalog.ZonePriceChange;

@Repository
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasMultipleActiveShowtimes(UUID eventId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM showtime
                    WHERE event_id = :id AND cancelled_at IS NULL
                    OFFSET 1
                )
                """).param("id", eventId).query(Boolean.class).single();
    }

    public Optional<EventDetails.LayoutSelection> findSelection(UUID eventId) {
        return jdbc.sql("""
                SELECT m.venue_id, e.seat_map_id
                FROM event e
                JOIN seat_map m ON m.id = e.seat_map_id
                WHERE e.id = :id
                """)
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.LayoutSelection(
                        rs.getObject("venue_id", UUID.class),
                        rs.getObject("seat_map_id", UUID.class)))
                .optional();
    }

    public boolean isValidLayout(UUID layoutId, String category) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM seat_map m
                    JOIN venue v ON v.id = m.venue_id
                    WHERE m.id = :layout
                      AND v.deleted_at IS NULL
                      AND m.layout_type = :category
                )
                """)
                .param("layout", layoutId)
                .param("category", category)
                .query(Boolean.class)
                .single();
    }

    public boolean hasConflictingShowtimes(UUID event, UUID layout) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM showtime
                    WHERE event_id = :event AND seat_map_id <> :layout
                )
                """).param("event", event).param("layout", layout).query(Boolean.class).single();
    }

    public void selectLayout(UUID event, UUID layout) {
        jdbc.sql("DELETE FROM sku WHERE event_id = :event AND seat_map_id <> :layout")
                .param("event", event)
                .param("layout", layout)
                .update();
        jdbc.sql("DELETE FROM event_zone WHERE event_id = :event AND seat_map_id <> :layout")
                .param("event", event)
                .param("layout", layout)
                .update();
        jdbc.sql("UPDATE event SET seat_map_id = :layout WHERE id = :event")
                .param("event", event)
                .param("layout", layout)
                .update();
    }

    public int countSkus(UUID event) {
        return jdbc.sql("SELECT count(*) FROM sku WHERE event_id = :event")
                .param("event", event)
                .query(Integer.class)
                .single();
    }

    public List<ZonePriceChange> findZonePrices(UUID event) {
        return jdbc.sql("SELECT zone_id, ticket_type_id, display_name FROM event_zone WHERE event_id = :event ORDER BY zone_id")
                .param("event", event)
                .query((rs, row) -> new ZonePriceChange(
                        rs.getObject("zone_id", UUID.class),
                        rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("display_name")))
                .list();
    }

    public UUID insertDraft(CreateEvent input, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO event (
                    title, description, category,
                    home_team, away_team, league, round,
                    poster_mobile_url, poster_desktop_url,
                    max_tickets_per_order, status,
                    created_at, updated_at
                )
                VALUES (
                    :title, :description, :category,
                    :homeTeam, :awayTeam, :league, :round,
                    :mobile, :desktop,
                    :maxTickets, 'DRAFT',
                    :now, :now
                )
                RETURNING id
                """)
                .param("title", input.title())
                .param("description", input.description(), Types.VARCHAR)
                .param("category", input.category())
                .param("homeTeam", input.homeTeam(), Types.VARCHAR)
                .param("awayTeam", input.awayTeam(), Types.VARCHAR)
                .param("league", input.league(), Types.VARCHAR)
                .param("round", input.round(), Types.VARCHAR)
                .param("mobile", input.posterMobileUrl() == null ? null : input.posterMobileUrl().toString(), Types.VARCHAR)
                .param("desktop", input.posterDesktopUrl() == null ? null : input.posterDesktopUrl().toString(), Types.VARCHAR)
                .param("maxTickets", input.maxTicketsPerOrder())
                .param("now", now)
                .query(UUID.class)
                .single();
    }

    public Optional<EventDetails.Header> findById(UUID eventId) {
        return findById(eventId, false);
    }

    public Optional<EventDetails.Header> lockById(UUID eventId) {
        return findById(eventId, true);
    }

    private Optional<EventDetails.Header> findById(UUID eventId, boolean forUpdate) {
        return jdbc.sql("""
                SELECT id, status::text AS status, visible, title, description, category,
                       home_team, away_team, league, round,
                       poster_mobile_url, poster_desktop_url,
                       max_tickets_per_order
                FROM event
                WHERE id = :id
                """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.Header(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("visible"),
                        new CreateEvent(
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("category"),
                                rs.getString("home_team"),
                                rs.getString("away_team"),
                                rs.getString("league"),
                                rs.getString("round"),
                                readPoster(rs.getString("poster_mobile_url"), eventId, "poster_mobile_url"),
                                readPoster(rs.getString("poster_desktop_url"), eventId, "poster_desktop_url"),
                                rs.getInt("max_tickets_per_order")
                        )
                ))
                .optional();
    }

    public boolean updateFields(UUID eventId, UpdateEvent patch, boolean clearFootballFields, OffsetDateTime now) {
        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        addField(assignments, params, "title", patch.title());
        addField(assignments, params, "description", patch.description());
        addField(assignments, params, "category", patch.category());
        if (clearFootballFields) {
            assignments.add("home_team = NULL");
            assignments.add("away_team = NULL");
            assignments.add("league = NULL");
            assignments.add("round = NULL");
        } else {
            addField(assignments, params, "home_team", patch.homeTeam());
            addField(assignments, params, "away_team", patch.awayTeam());
            addField(assignments, params, "league", patch.league());
            addField(assignments, params, "round", patch.round());
        }
        addField(assignments, params, "poster_mobile_url",
                patch.posterMobileUrl() == null ? null : patch.posterMobileUrl().toString());
        addField(assignments, params, "poster_desktop_url",
                patch.posterDesktopUrl() == null ? null : patch.posterDesktopUrl().toString());
        addField(assignments, params, "max_tickets_per_order", patch.maxTicketsPerOrder());
        if (assignments.isEmpty()) {
            return false;
        }
        assignments.add("updated_at = :now");
        params.put("now", now);
        params.put("id", eventId);
        int updated = jdbc.sql("UPDATE event SET " + String.join(", ", assignments) + " WHERE id = :id")
                .params(params)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Không cập nhật được sự kiện đã khóa.");
        }
        return true;
    }

    private static void addField(List<String> assignments, Map<String, Object> params, String column, Object value) {
        if (value != null) {
            assignments.add(column + " = :" + column);
            params.put(column, value);
        }
    }

    public Set<UUID> findShowtimeIds(UUID eventId) {
        return Set.copyOf(jdbc.sql("SELECT id FROM showtime WHERE event_id = :id")
                .param("id", eventId).query(UUID.class).list());
    }

    public List<EventDetails.Tier> findPriceTiers(UUID eventId, OffsetDateTime now) {
        var skus = jdbc.sql("""
                SELECT s.id, s.name, s.color,
                       COALESCE(
                           (SELECT sp.price FROM ticket_type_price sp
                            WHERE sp.ticket_type_id = s.id AND sp.effective_from <= :now
                            ORDER BY sp.effective_from DESC LIMIT 1),
                           (SELECT sp.price FROM ticket_type_price sp
                            WHERE sp.ticket_type_id = s.id
                            ORDER BY sp.effective_from ASC LIMIT 1),
                           0
                       ) AS current_price
                FROM ticket_type s
                WHERE s.event_id = :id
                ORDER BY current_price, s.name, s.id
                """)
                .param("id", eventId)
                .param("now", now)
                .query((rs, row) -> new EventDetails.Tier(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("current_price"),
                        rs.getString("color"),
                        List.of()
                ))
                .list();

        if (skus.isEmpty()) {
            return List.of();
        }

        var priceRows = jdbc.sql("""
                SELECT sp.id, sp.ticket_type_id AS sku_id, sp.price, sp.effective_from, sp.created_by, sp.reason, sp.created_at
                FROM ticket_type_price sp
                JOIN ticket_type s ON s.id = sp.ticket_type_id
                WHERE s.event_id = :id
                ORDER BY sp.effective_from DESC, sp.created_at DESC
                """)
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.SkuPriceRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getLong("price"),
                        rs.getObject("effective_from", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ))
                .list();

        Map<UUID, List<EventDetails.SkuPriceRow>> bySku = new LinkedHashMap<>();
        for (var p : priceRows) {
            bySku.computeIfAbsent(p.skuId(), ignored -> new ArrayList<>()).add(p);
        }

        return skus.stream()
                .map(t -> new EventDetails.Tier(
                        t.id(), t.name(), t.price(), t.color(),
                        List.copyOf(bySku.getOrDefault(t.id(), List.of()))))
                .toList();
    }

    public List<EventDetails.Session> findShowtimes(UUID eventId) {
        return jdbc.sql("""
                SELECT s.id, s.event_id, s.seat_map_id,
                       e.status::text AS event_status, s.cancelled_at,
                       s.starts_at, s.ends_at,
                       s.sale_opens_at, s.sale_closes_at,
                       v.id AS venue_id,
                       v.name AS venue_name,
                       v.address AS venue_address,
                       v.city AS venue_city
                FROM showtime s
                JOIN event e ON e.id = s.event_id
                JOIN seat_map m ON m.id = s.seat_map_id
                JOIN venue v ON v.id = m.venue_id
                WHERE s.event_id = :id
                ORDER BY s.starts_at, s.id
                """)
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.Session(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("seat_map_id", UUID.class),
                        rs.getString("event_status"),
                        rs.getObject("cancelled_at", OffsetDateTime.class),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                        new EventDetails.VenueInfo(
                                rs.getObject("venue_id", UUID.class),
                                rs.getString("venue_name"),
                                rs.getString("venue_address"),
                                rs.getString("venue_city")
                        )
                ))
                .list();
    }

    private static URI readPoster(String value, UUID eventId, String field) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (supportedScheme && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Dữ liệu cũ có thể chứa URL sai.
        }
        log.warn("Bỏ qua poster không hợp lệ event_id={} field={}", eventId, field);
        return null;
    }

    public List<EventList.Item> findPage(
            String q,
            String status,
            String category,
            EventList.Position after,
            int fetchSize
    ) {
        var sql = new StringBuilder("""
                SELECT id, title, category, status::text AS status, visible,
                       poster_mobile_url, poster_desktop_url,
                       created_at, updated_at
                FROM event
                WHERE 1 = 1
                """);

        Map<String, Object> params = new HashMap<>();

        if (q != null) {
            sql.append(" AND strpos(lower(title), lower(:q)) > 0\n");
            params.put("q", q);
        }

        if (status != null) {
            sql.append(" AND status = CAST(:status AS event_status)\n");
            params.put("status", status);
        }

        if (category != null) {
            sql.append(" AND category = :category\n");
            params.put("category", category);
        }

        if (after != null) {
            sql.append(" AND (created_at, id) < (:afterTime, :afterId)\n");
            params.put("afterTime", after.createdAt());
            params.put("afterId", after.id());
        }

        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :fetchSize");
        params.put("fetchSize", fetchSize);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new EventList.Item(
                            id,
                            rs.getString("title"),
                            rs.getString("category"),
                            rs.getString("status"),
                            rs.getBoolean("visible"),
                            readPoster(rs.getString("poster_mobile_url"), id, "poster_mobile_url"),
                            readPoster(rs.getString("poster_desktop_url"), id, "poster_desktop_url"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("updated_at", OffsetDateTime.class)
                    );
                })
                .list();
    }
}
