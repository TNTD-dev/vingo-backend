package vingo.search.internal;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.search.EventSearchCriteria;
import vingo.search.EventSearchCriteria.Sort;
import vingo.search.EventSummaryResult;
import vingo.search.InvalidSearchRequestException;
import vingo.search.MatchMode;
import vingo.search.internal.SearchQueryNormalizer.NormalizedQuery;

/**
 * Truy vấn tìm sự kiện, chỉ đọc bằng {@link JdbcClient}. Đọc bảng của catalog và booking.
 *
 * <p>WHERE ghép động theo bộ lọc có mặt. Biểu thức FTS và trigram phải giống hệt biểu thức tạo index trong
 * {@code V20261110_1010__search_init.sql} thì Postgres mới dùng được index.
 */
@Repository
class EventSearchRepository {

    // Giống hệt biểu thức của event_title_fts_idx và event_title_trgm_idx.
    private static final String FTS_DOC =
            "to_tsvector('simple', immutable_unaccent(coalesce(e.title, '') || ' ' || coalesce(e.description, '')))";
    private static final String TRGM_DOC = "immutable_unaccent(lower(e.title))";
    private static final String FTS_MATCH = FTS_DOC + " @@ to_tsquery('simple', :ts_query)";
    private static final String TRGM_MATCH = "CAST(:q_norm AS text) <% " + TRGM_DOC;
    private static final String RELEVANCE = "CAST(greatest(ts_rank(" + FTS_DOC + ", to_tsquery('simple', :ts_query)), "
            + "word_similarity(CAST(:q_norm AS text), " + TRGM_DOC + ")) AS float8)";

    private static final String MAX_BIGINT = "9223372036854775807";

    private final JdbcClient jdbc;

    EventSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lấy tối đa {@code fetchSize} dòng của trang kế tiếp. {@code mode} chọn tsquery chặt hoặc lỏng; trigram giữ
     * nguyên ở cả hai chế độ.
     */
    List<EventSummaryResult> search(EventSearchCriteria c, NormalizedQuery q, MatchMode mode, OffsetDateTime now,
            int fetchSize) {
        Map<String, Object> params = new HashMap<>();
        params.put("now", now);
        params.put("fetch_size", fetchSize);

        // Điều kiện lọc suất: áp cho suất đại diện của thẻ (venue_name, city, first_starts_at).
        StringBuilder showtimeFilter = new StringBuilder();
        if (c.city() != null) {
            showtimeFilter.append(" AND v.city = :city");
            params.put("city", c.city());
        }
        if (c.from() != null) {
            showtimeFilter.append(" AND s.starts_at >= :from");
            params.put("from", c.from());
        }
        if (c.to() != null) {
            showtimeFilter.append(" AND s.starts_at <= :to");
            params.put("to", c.to());
        }

        StringBuilder where = new StringBuilder("e.status = 'PUBLISHED'");
        if (c.category() != null) {
            where.append(" AND e.category = :category");
            params.put("category", c.category());
        }
        if (q != null) {
            where.append(" AND (").append(FTS_MATCH).append(" OR ").append(TRGM_MATCH).append(')');
            params.put("ts_query", q.tsQuery(mode));
            params.put("q_norm", q.text());
        }
        if (c.priceMin() != null || c.priceMax() != null) {
            where.append("""
                     AND EXISTS (SELECT 1 FROM zone_price zp JOIN price_tier pt ON pt.id = zp.price_tier_id
                                 WHERE zp.event_id = e.id""");
            if (c.priceMin() != null) {
                where.append(" AND pt.price >= :price_min");
                params.put("price_min", c.priceMin());
            }
            if (c.priceMax() != null) {
                where.append(" AND pt.price <= :price_max");
                params.put("price_max", c.priceMax());
            }
            where.append(')');
        }

        String popularityJoin = c.sort() == Sort.POPULARITY ? POPULARITY_JOIN : "";
        String sortKey = switch (c.sort()) {
            case RELEVANCE -> RELEVANCE;
            case STARTS_AT -> "CAST(floor(extract(epoch FROM nx.first_starts_at) * 1000000) AS bigint)";
            case PRICE -> "coalesce(pf.price_from, " + MAX_BIGINT + ")";
            case POPULARITY -> "pop.sold";
        };
        boolean desc = c.sort() == Sort.RELEVANCE || c.sort() == Sort.POPULARITY;
        String dir = desc ? "DESC" : "ASC";

        String keyset = "";
        if (c.after() != null) {
            keyset = " WHERE (r.sort_key, r.id) " + (desc ? "<" : ">") + " (:after_key, :after_id)";
            params.put("after_key", parseKey(c.sort(), c.after().key()));
            params.put("after_id", c.after().id());
        }

        String sql = """
                SELECT r.id, r.title, r.category, r.poster_url, r.venue_name, r.city, r.first_starts_at,
                       r.price_from, r.on_sale, r.sort_key
                FROM (
                    SELECT e.id, e.title, e.category, e.poster_url,
                           nx.venue_name, nx.city, nx.first_starts_at, pf.price_from,
                           EXISTS (SELECT 1 FROM showtime s2
                                   WHERE s2.event_id = e.id AND s2.status = 'SCHEDULED' AND s2.starts_at >= :now
                                     AND s2.sale_opens_at <= :now AND :now < s2.sale_closes_at) AS on_sale,
                           %s AS sort_key
                    FROM event e
                    JOIN LATERAL (
                        SELECT v.name AS venue_name, v.city, s.starts_at AS first_starts_at
                        FROM showtime s
                        JOIN hall h ON h.id = s.hall_id
                        JOIN venue v ON v.id = h.venue_id
                        WHERE s.event_id = e.id AND s.status = 'SCHEDULED' AND s.starts_at >= :now%s
                        ORDER BY s.starts_at, s.id
                        LIMIT 1
                    ) nx ON true
                    LEFT JOIN LATERAL (
                        SELECT min(pt.price) AS price_from
                        FROM zone_price zp JOIN price_tier pt ON pt.id = zp.price_tier_id
                        WHERE zp.event_id = e.id
                    ) pf ON true
                    %s
                    WHERE %s
                ) r%s
                ORDER BY r.sort_key %s, r.id %s
                LIMIT :fetch_size
                """.formatted(sortKey, showtimeFilter, popularityJoin, where, keyset, dir, dir);

        boolean relevance = c.sort() == Sort.RELEVANCE;
        return jdbc.sql(sql).params(params).query((rs, i) -> new EventSummaryResult(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("poster_url"),
                rs.getString("venue_name"),
                rs.getString("city"),
                rs.getObject("first_starts_at", OffsetDateTime.class),
                rs.getObject("price_from", Long.class),
                rs.getBoolean("on_sale"),
                relevance ? Double.toString(rs.getDouble("sort_key")) : Long.toString(rs.getLong("sort_key")))).list();
    }

    // Tổng vé SOLD của các suất sắp tới: ghế ngồi (showtime_seat) cộng khu đứng (showtime_zone_stock).
    private static final String POPULARITY_JOIN = """
            LEFT JOIN LATERAL (
                SELECT (SELECT count(*) FROM showtime_seat ss JOIN showtime s3 ON s3.id = ss.showtime_id
                        WHERE s3.event_id = e.id AND s3.status = 'SCHEDULED' AND s3.starts_at >= :now
                          AND ss.status = 'SOLD')
                     + (SELECT coalesce(sum(zs.sold), 0) FROM showtime_zone_stock zs JOIN showtime s4 ON s4.id = zs.showtime_id
                        WHERE s4.event_id = e.id AND s4.status = 'SCHEDULED' AND s4.starts_at >= :now) AS sold
            ) pop ON true""";

    private static Object parseKey(Sort sort, String key) {
        try {
            return sort == Sort.RELEVANCE ? (Object) Double.valueOf(key) : (Object) Long.valueOf(key);
        } catch (NumberFormatException e) {
            throw new InvalidSearchRequestException("cursor", "cursor không hợp lệ");
        }
    }
}
