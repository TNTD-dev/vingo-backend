package vingo.search.internal;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vingo.search.PriceTierResult;
import vingo.search.VenueResult;

/**
 * Truy vấn chi tiết sự kiện và các suất, chỉ đọc bằng {@link JdbcClient}. Đọc bảng của catalog (event, showtime,
 * hall, venue, price_tier) và booking (showtime_seat, showtime_zone_stock). Không tính trạng thái suất trong SQL.
 */
@Repository
class EventDetailRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Cột của event, chỉ lấy khi event PUBLISHED. */
    record EventRow(UUID id, String title, String description, String category, String status, String posterUrl,
            Map<String, Object> attributes, int maxTicketsPerOrder) {
    }

    /**
     * Một suất ở dạng thô: {@code storedStatus} là giá trị trong DB, chưa tính theo giờ; không có {@code sold_out}
     * vì cờ đó đổi theo từng giây. Đây là dữ liệu được cache 10 phút.
     */
    record ShowtimeRow(UUID id, UUID eventId, UUID hallId, UUID seatLayoutId, OffsetDateTime startsAt,
            OffsetDateTime endsAt, OffsetDateTime saleOpensAt, OffsetDateTime saleClosesAt, String storedStatus,
            VenueResult venue) {
    }

    private final JdbcClient jdbc;

    EventDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Chỉ trả sự kiện PUBLISHED; DRAFT, ENDED, CANCELLED hoặc không có đều rỗng. */
    Optional<EventRow> findPublished(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, title, description, category, status, poster_url,
                               CAST(attributes AS text) AS attributes, max_tickets_per_order
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
                        JSON.readValue(rs.getString("attributes"), new TypeReference<Map<String, Object>>() { }),
                        rs.getInt("max_tickets_per_order")))
                .optional();
    }

    List<PriceTierResult> findPriceTiers(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, name, price, color FROM price_tier WHERE event_id = :id ORDER BY price, name, id
                        """)
                .param("id", eventId)
                .query((rs, n) -> new PriceTierResult(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price"),
                        rs.getString("color")))
                .list();
    }

    /** Các suất không phải DRAFT, theo {@code starts_at} rồi {@code id}. Dữ liệu thô, không tính trạng thái. */
    List<ShowtimeRow> findShowtimes(UUID eventId) {
        return jdbc.sql("""
                        SELECT s.id, s.event_id, s.hall_id, s.seat_layout_id, s.starts_at, s.ends_at,
                               s.sale_opens_at, s.sale_closes_at, s.status,
                               v.id AS venue_id, v.name AS venue_name, v.type AS venue_type,
                               v.address AS venue_address, v.city AS venue_city
                        FROM showtime s
                        JOIN hall h ON h.id = s.hall_id
                        JOIN venue v ON v.id = h.venue_id
                        WHERE s.event_id = :id AND s.status <> 'DRAFT'
                        ORDER BY s.starts_at, s.id
                        """)
                .param("id", eventId)
                .query((rs, n) -> new ShowtimeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("hall_id", UUID.class),
                        rs.getObject("seat_layout_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getObject("sale_opens_at", OffsetDateTime.class),
                        rs.getObject("sale_closes_at", OffsetDateTime.class),
                        rs.getString("status"),
                        new VenueResult(
                                rs.getObject("venue_id", UUID.class), rs.getString("venue_name"),
                                rs.getString("venue_type"), rs.getString("venue_address"),
                                rs.getString("venue_city"))))
                .list();
    }

    /**
     * Id các suất (không phải DRAFT) của sự kiện đã hết chỗ: không còn ghế ngồi trống (AVAILABLE, hoặc HELD đã quá
     * hạn tại {@code now}) và mọi khu đứng hết chỗ. Không cache: đọc DB mỗi lần.
     */
    Set<UUID> findSoldOutShowtimeIds(UUID eventId, OffsetDateTime now) {
        return new HashSet<>(jdbc.sql("""
                        SELECT s.id
                        FROM showtime s
                        WHERE s.event_id = :id AND s.status <> 'DRAFT'
                          AND NOT EXISTS (SELECT 1 FROM showtime_seat ss
                                          WHERE ss.showtime_id = s.id
                                            AND (ss.status = 'AVAILABLE'
                                                 OR (ss.status = 'HELD' AND ss.held_until < :now)))
                          AND NOT EXISTS (SELECT 1 FROM showtime_zone_stock z
                                          WHERE z.showtime_id = s.id AND z.capacity - z.sold - z.held > 0)
                        """)
                .param("id", eventId)
                .param("now", now)
                .query(UUID.class)
                .list());
    }
}
