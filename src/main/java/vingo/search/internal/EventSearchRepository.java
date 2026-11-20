package vingo.search.internal;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    // Giống hệt biểu thức của các index trong V20261110_1010__search_init.sql và V20261115_1000__search_venue_city_index.sql.
    private static final String FTS_DOC =
            "to_tsvector('simple', immutable_unaccent(coalesce(e.title, '') || ' ' || coalesce(e.description, '')))";
    private static final String TITLE_DOC = "to_tsvector('simple', immutable_unaccent(coalesce(e.title, '')))";
    private static final String TRGM_DOC = "immutable_unaccent(lower(e.title))";
    private static final String TS_QUERY = "to_tsquery('simple', :ts_query)";
    private static final String FTS_MATCH = FTS_DOC + " @@ " + TS_QUERY;
    private static final String TITLE_MATCH = TITLE_DOC + " @@ " + TS_QUERY;
    private static final String TRGM_MATCH = "CAST(:q_norm AS text) <% " + TRGM_DOC;

    // Mức xếp hạng: khớp tên sự kiện > khớp sân/thành phố > khớp mô tả. Điểm trong mức nằm trong [0, 1].
    private static final int TIER_TITLE = 4;
    private static final int TIER_VENUE = 2;

    /** Bộ lọc trên web dùng các mã cũ, trong khi sân chọn trên bản đồ lưu tên tỉnh/thành. */
    private static List<String> cityNames(String city) {
        String name = city.strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "hn", "hà nội", "thành phố hà nội", "hanoi" ->
                    List.of("hn", "hà nội", "thành phố hà nội", "hanoi");
            case "hcm", "tp. hồ chí minh", "thành phố hồ chí minh", "hồ chí minh", "sài gòn" ->
                    List.of("hcm", "tp. hồ chí minh", "thành phố hồ chí minh", "hồ chí minh", "sài gòn");
            case "dn", "đà nẵng", "thành phố đà nẵng", "da nang" ->
                    List.of("dn", "đà nẵng", "thành phố đà nẵng", "da nang");
            default -> List.of(name);
        };
    }

    /** Tài liệu tìm kiếm của sân (tên sân + tên thành phố), giống hệt biểu thức của venue_search_fts_idx. */
    private static String venueDoc(String v) {
        return "to_tsvector('simple', immutable_unaccent(coalesce(" + v + ".name, '') || ' ' || immutable_city_text("
                + v + ".city)))";
    }

    private static String venueMatch(String v) {
        return venueDoc(v) + " @@ " + TS_QUERY;
    }

    private static final String MAX_BIGINT = "9223372036854775807";

    private final JdbcClient jdbc;

    EventSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lấy tối đa {@code fetchSize} dòng của trang kế tiếp. {@code mode} chọn tsquery chặt hoặc lỏng; trigram giữ
     * nguyên ở cả hai chế độ (và chỉ bật khi từ khoá đủ dài).
     */
    List<EventSummaryResult> search(EventSearchCriteria c, NormalizedQuery q, MatchMode mode, OffsetDateTime now,
            int fetchSize) {
        Map<String, Object> params = new HashMap<>();
        params.put("now", now);
        params.put("fetch_size", fetchSize);

        // Điều kiện lọc suất: áp cho suất đại diện của thẻ (venue_name, city, first_starts_at).
        StringBuilder showtimeFilter = new StringBuilder();
        if (c.city() != null) {
            showtimeFilter.append(" AND lower(v.city) IN (:cityNames)");
            params.put("cityNames", cityNames(c.city()));
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
        boolean trigram = q != null && q.useTrigram();
        String venueRank = "0";
        String venueFirst = "";
        String venueFlag = "false";
        if (q != null) {
            // Ứng viên gộp từ ba nhánh, mỗi nhánh có index riêng: FTS sự kiện, trigram tiêu đề, sân/thành phố của suất.
            // Nhánh sân dùng cùng bộ lọc suất (city, from, to) với thẻ nên suất khớp sân phải là suất thật sự hiện ra.
            StringBuilder cand = new StringBuilder("SELECT e.id FROM event e WHERE ").append(FTS_MATCH);
            if (trigram) {
                cand.append(" UNION SELECT e.id FROM event e WHERE ").append(TRGM_MATCH);
            }
            cand.append("""
                     UNION SELECT s.event_id FROM venue v
                           JOIN seat_map sl ON sl.venue_id = v.id
                           JOIN showtime s ON s.seat_map_id = sl.id
                           WHERE v.deleted_at IS NULL AND s.cancelled_at IS NULL AND s.starts_at >= :now
                             AND\s""").append(venueMatch("v")).append(showtimeFilter);
            where.append(" AND e.id IN (").append(cand).append(')');
            params.put("ts_query", q.tsQuery(mode));
            params.put("q_norm", q.text());
            venueFlag = "(v0.deleted_at IS NULL AND " + venueMatch("v0") + ")";
            venueFirst = "CASE WHEN v.matched THEN 0 ELSE 1 END, ";
            venueRank = "EXISTS (SELECT 1 FROM venue v JOIN seat_map sl ON sl.venue_id = v.id"
                    + " JOIN showtime s ON s.seat_map_id = sl.id"
                    + " WHERE s.event_id = e.id AND v.deleted_at IS NULL AND s.cancelled_at IS NULL AND s.starts_at >= :now"
                    + " AND " + venueMatch("v") + showtimeFilter + ")";
        }
        if (c.priceMin() != null || c.priceMax() != null) {
            where.append("""
                     AND EXISTS (SELECT 1 FROM event_zone ez
                                 JOIN LATERAL (
                                     SELECT price FROM sku_price
                                     WHERE sku_id = ez.sku_id AND effective_from <= :now
                                     ORDER BY effective_from DESC LIMIT 1
                                 ) pt ON true
                                 WHERE ez.event_id = e.id""");
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
            case RELEVANCE -> relevance(trigram, venueRank);
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
                    SELECT e.id, e.title, e.category,
                           COALESCE(e.poster_desktop_url, e.poster_mobile_url) AS poster_url,
                           nx.venue_name, nx.city, nx.first_starts_at, pf.price_from,
                           EXISTS (SELECT 1 FROM showtime s2
                                   WHERE s2.event_id = e.id AND s2.cancelled_at IS NULL AND s2.starts_at >= :now
                                     AND s2.sale_opens_at <= :now AND :now < s2.sale_closes_at) AS on_sale,
                           %s AS sort_key
                    FROM event e
                    JOIN LATERAL (
                        SELECT v.name AS venue_name, v.city, s.starts_at AS first_starts_at
                        FROM showtime s
                        CROSS JOIN LATERAL (
                            SELECT v0.name, v0.city, %s AS matched
                            FROM seat_map sl JOIN venue v0 ON v0.id = sl.venue_id
                            WHERE sl.id = s.seat_map_id
                            OFFSET 0
                        ) v
                        WHERE s.event_id = e.id AND s.cancelled_at IS NULL AND s.starts_at >= :now%s
                        ORDER BY %s s.starts_at, s.id
                        LIMIT 1
                    ) nx ON true
                    LEFT JOIN LATERAL (
                        SELECT min(pt.price) AS price_from
                        FROM event_zone ez
                        JOIN LATERAL (
                            SELECT price FROM sku_price
                            WHERE sku_id = ez.sku_id AND effective_from <= :now
                            ORDER BY effective_from DESC LIMIT 1
                        ) pt ON true
                        WHERE ez.event_id = e.id
                    ) pf ON true
                    %s
                    WHERE %s
                ) r%s
                ORDER BY r.sort_key %s, r.id %s
                LIMIT :fetch_size
                """.formatted(sortKey, venueFlag, showtimeFilter, venueFirst, popularityJoin, where, keyset, dir, dir);

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

    private static final String POPULARITY_JOIN = """
            LEFT JOIN LATERAL (
                SELECT (SELECT count(*) FROM seat_inventory ss JOIN showtime s3 ON s3.id = ss.showtime_id
                        WHERE s3.event_id = e.id AND s3.cancelled_at IS NULL AND s3.starts_at >= :now
                          AND ss.status = 'SOLD')
                     + (SELECT coalesce(sum(zs.sold), 0) FROM standing_inventory zs JOIN showtime s4 ON s4.id = zs.showtime_id
                        WHERE s4.event_id = e.id AND s4.cancelled_at IS NULL AND s4.starts_at >= :now) AS sold
            ) pop ON true""";

    /**
     * Độ liên quan = mức (khớp tên 4, khớp sân/thành phố 2, chỉ khớp mô tả 0) cộng điểm trong mức (0..1). Điểm trong
     * mức: tên lấy max(ts_rank, word_similarity), mô tả lấy ts_rank.
     */
    private static String relevance(boolean trigram, String venueRank) {
        String titleScore = "least(1.0, ts_rank(" + TITLE_DOC + ", " + TS_QUERY + "))";
        String titleMatch = TITLE_MATCH;
        if (trigram) {
            titleScore = "greatest(" + titleScore + ", word_similarity(CAST(:q_norm AS text), " + TRGM_DOC + "))";
            titleMatch = "(" + titleMatch + " OR " + TRGM_MATCH + ")";
        }
        return "CAST(CASE WHEN " + titleMatch + " THEN " + TIER_TITLE + " + " + titleScore
                + " WHEN " + venueRank + " THEN " + TIER_VENUE
                + " ELSE least(1.0, ts_rank(" + FTS_DOC + ", " + TS_QUERY + ")) END AS float8)";
    }

    private static Object parseKey(Sort sort, String key) {
        try {
            return sort == Sort.RELEVANCE ? (Object) Double.valueOf(key) : (Object) Long.valueOf(key);
        } catch (NumberFormatException e) {
            throw new InvalidSearchRequestException("cursor", "cursor không hợp lệ");
        }
    }
}
