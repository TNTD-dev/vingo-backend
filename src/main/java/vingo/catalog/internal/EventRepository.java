package vingo.catalog.internal;

import java.net.URI;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;
import vingo.catalog.EventDetails;
import vingo.catalog.EventList;
import vingo.catalog.EventService.CreateEvent;
import vingo.catalog.EventService.UpdateEvent;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private static final Logger log
            = LoggerFactory.getLogger(EventRepository.class);

    public EventRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID insertDraft(CreateEvent input, OffsetDateTime now) {
        String attributes = json.writeValueAsString(
                input.attributes() == null ? Map.of() : input.attributes()
        );

        return jdbc.sql("""
                INSERT INTO event (
                    title, description, category, attributes,
                    poster_mobile_url, poster_desktop_url, poster_url, age_limit,
                    max_tickets_per_order, status,
                    created_at, updated_at
                )
                VALUES (
                    :title, :description, :category, CAST(:attributes AS jsonb),
                    :mobile, :desktop, COALESCE(:desktop, :mobile), :ageLimit,
                    :maxTickets, 'DRAFT',
                    :now, :now
                )
                RETURNING id
                """)
                .param("title", input.title())
                .param("description", input.description(), Types.VARCHAR)
                .param("category", input.category())
                .param("attributes", attributes)
                .param("mobile",
                        input.posterMobileUrl() == null
                        ? null : input.posterMobileUrl().toString(),
                        Types.VARCHAR)
                .param("desktop",
                        input.posterDesktopUrl() == null
                        ? null : input.posterDesktopUrl().toString(),
                        Types.VARCHAR)
                .param("ageLimit", input.ageLimit(), Types.INTEGER)
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
                SELECT id, status, title, description, category,
                        CAST(attributes AS text) AS attributes_json,
                        poster_mobile_url, poster_desktop_url,
                        age_limit, max_tickets_per_order
                FROM event
                WHERE id = :id
                """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.Header(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        new CreateEvent(
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("category"),
                                json.readValue(
                                        rs.getString("attributes_json"),
                                        Object.class
                                ),
                                readPoster(
                                        rs.getString("poster_mobile_url"),
                                        eventId,
                                        "poster_mobile_url"
                                ),
                                readPoster(
                                        rs.getString("poster_desktop_url"),
                                        eventId,
                                        "poster_desktop_url"
                                ),
                                rs.getObject("age_limit", Integer.class),
                                rs.getInt("max_tickets_per_order")
                        )
                ))
                .optional();
    }

    public boolean updateFields(UUID eventId, UpdateEvent patch, OffsetDateTime now) {
        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        addField(assignments, params, "title", patch.title());
        addField(assignments, params, "description", patch.description());
        addField(assignments, params, "category", patch.category());
        if (patch.attributes() != null) {
            assignments.add("attributes = CAST(:attributes AS jsonb)");
            params.put("attributes", json.writeValueAsString(patch.attributes()));
        }
        addField(assignments, params, "poster_mobile_url",
                patch.posterMobileUrl() == null ? null : patch.posterMobileUrl().toString());
        addField(assignments, params, "poster_desktop_url",
                patch.posterDesktopUrl() == null ? null : patch.posterDesktopUrl().toString());
        if (patch.posterMobileUrl() != null || patch.posterDesktopUrl() != null) {
            assignments.add("poster_url = COALESCE("
                    + (patch.posterDesktopUrl() != null ? ":poster_desktop_url" : "poster_desktop_url")
                    + ", "
                    + (patch.posterMobileUrl() != null ? ":poster_mobile_url" : "poster_mobile_url")
                    + ")");
        }
        addField(assignments, params, "age_limit", patch.ageLimit());
        addField(assignments, params, "max_tickets_per_order", patch.maxTicketsPerOrder());
        if (assignments.isEmpty()) {
            return false;
        }
        assignments.add("updated_at = :now");
        params.put("now", now);
        params.put("id", eventId);
        int updated = jdbc.sql("UPDATE event SET " + String.join(", ", assignments) + " WHERE id = :id")
                .params(params).update();
        if (updated != 1) {
            throw new IllegalStateException("Không cập nhật được sự kiện đã khóa.");
        }
        return true;
    }

    private static void addField(List<String> assignments, Map<String, Object> params,
            String column, Object value) {
        if (value != null) {
            assignments.add(column + " = :" + column);
            params.put(column, value);
        }
    }

    public Set<UUID> findShowtimeIds(UUID eventId) {
        return Set.copyOf(jdbc.sql("SELECT id FROM showtime WHERE event_id = :id")
                .param("id", eventId).query(UUID.class).list());
    }

    public List<EventDetails.Tier> findPriceTiers(UUID eventId) {
        return jdbc.sql("""
                SELECT id, name, price, color
                FROM price_tier
                WHERE event_id = :id
                ORDER BY price, name, id
                """)
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.Tier(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price"),
                        rs.getString("color")
                ))
                .list();
    }

    public List<EventDetails.Session> findShowtimes(UUID eventId) {
        return jdbc.sql("""
                SELECT s.id, s.event_id, s.hall_id, s.seat_layout_id,
                        s.status, s.starts_at, s.ends_at,
                        s.sale_opens_at, s.sale_closes_at,
                        v.id AS venue_id,
                        v.name AS venue_name,
                        v.type AS venue_type,
                        v.address AS venue_address,
                        v.city AS venue_city
                FROM showtime s
                JOIN hall h ON h.id = s.hall_id
                JOIN venue v ON v.id = h.venue_id
                WHERE s.event_id = :id
                ORDER BY s.starts_at, s.id
                """)
                .param("id", eventId)
                .query((rs, row) -> new EventDetails.Session(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("hall_id", UUID.class),
                        rs.getObject("seat_layout_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                        new EventDetails.VenueInfo(
                                rs.getObject("venue_id", UUID.class),
                                rs.getString("venue_name"),
                                rs.getString("venue_type"),
                                rs.getString("venue_address"),
                                rs.getString("venue_city")
                        )
                ))
                .list();
    }

    private static URI readPoster(
            String value,
            UUID eventId,
            String field
    ) {
        if (value == null) {
            return null;
        }

        try {
            URI uri = URI.create(value);

            boolean supportedScheme
                    = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());

            if (supportedScheme && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Dữ liệu cũ có thể chứa URL sai trước khi có validation.
        }

        log.warn(
                "Bỏ qua poster không hợp lệ event_id={} field={}",
                eventId, field
        );
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
                SELECT id, title, category, status,
                        poster_mobile_url, poster_desktop_url,
                        created_at, updated_at
                FROM event
                WHERE 1 = 1
                """);

        Map<String, Object> params = new HashMap<>();

        if (q != null) {
            sql.append("""
                        AND strpos(lower(title), lower(:q)) > 0
                        """);
            params.put("q", q);
        }

        if (status != null) {
            sql.append(" AND status = :status\n");
            params.put("status", status);
        }

        if (category != null) {
            sql.append(" AND category = :category\n");
            params.put("category", category);
        }

        if (after != null) {
            sql.append("""
                        AND (created_at, id) < (:afterTime, :afterId)
                        """);
            params.put("afterTime", after.createdAt());
            params.put("afterId", after.id());
        }

        sql.append("""
                ORDER BY created_at DESC, id DESC
                LIMIT :fetchSize
                """);
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
                            readPoster(
                                    rs.getString("poster_mobile_url"),
                                    id,
                                    "poster_mobile_url"
                            ),
                            readPoster(
                                    rs.getString("poster_desktop_url"),
                                    id,
                                    "poster_desktop_url"
                            ),
                            rs.getObject(
                                    "created_at", OffsetDateTime.class
                            ),
                            rs.getObject(
                                    "updated_at", OffsetDateTime.class
                            )
                    );
                })
                .list();
    }
}
