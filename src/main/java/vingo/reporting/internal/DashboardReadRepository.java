package vingo.reporting.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vingo.api.model.AdminDashboardShowtime;
import vingo.api.model.AdminDashboardVenue;

@Repository
public class DashboardReadRepository {
    private final JdbcClient jdbc;
    public DashboardReadRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    // Tổng từng suất trước khi join sân để không nhân số ghế với số dòng khu đứng.
    private static final String STOCK = """
            WITH stock AS (
                SELECT showtime_id,count(*)::bigint AS capacity,
                    count(*) FILTER(WHERE status='SOLD')::bigint AS sold FROM showtime_seat GROUP BY showtime_id
                UNION ALL SELECT showtime_id,sum(capacity)::bigint,sum(sold)::bigint
                    FROM showtime_zone_stock GROUP BY showtime_id
            ), totals AS (SELECT showtime_id,sum(capacity)::bigint AS capacity,sum(sold)::bigint AS sold
                FROM stock GROUP BY showtime_id)
            """;

    public long newUsers(OffsetDateTime from,OffsetDateTime to) {
        return jdbc.sql("SELECT count(*) FROM users WHERE created_at>=:from AND created_at<:to")
                .param("from",from).param("to",to).query(Long.class).single();
    }

    public java.util.Map<String,String> tierNames() {
        var names=new java.util.LinkedHashMap<String,String>();
        jdbc.sql("SELECT p.id,e.title,p.name FROM price_tier p JOIN event e ON e.id=p.event_id")
                .query((rs,n) -> { names.put(rs.getObject("id",UUID.class).toString(),rs.getString("title")+" · "+rs.getString("name")); return true; }).list();
        return names;
    }

    public long onSale(OffsetDateTime now) {
        return jdbc.sql("""
                SELECT count(*) FROM showtime s JOIN event e ON e.id=s.event_id
                WHERE s.status='SCHEDULED' AND e.status='PUBLISHED'
                  AND s.sale_opens_at<=:now AND s.sale_closes_at>:now AND s.ends_at>:now
                """).param("now",now).query(Long.class).single();
    }

    public List<AdminDashboardShowtime> upcoming(OffsetDateTime now) {
        return jdbc.sql(STOCK+"""
                SELECT s.id,s.event_id,e.title,v.name,s.starts_at,COALESCE(t.sold,0) AS sold,
                    COALESCE(t.capacity,0) AS capacity,(s.sale_opens_at<=:now AND s.sale_closes_at>:now) AS on_sale
                FROM showtime s JOIN event e ON e.id=s.event_id JOIN seat_layout l ON l.id=s.seat_layout_id
                JOIN venue v ON v.id=l.venue_id LEFT JOIN totals t ON t.showtime_id=s.id
                WHERE s.status='SCHEDULED' AND e.status='PUBLISHED' AND s.starts_at>:now
                ORDER BY s.starts_at,s.id LIMIT 10
                """).param("now",now).query((rs,n)->new AdminDashboardShowtime().id(rs.getObject("id",UUID.class))
                    .eventId(rs.getObject("event_id",UUID.class)).title(rs.getString("title")).venueName(rs.getString("name"))
                    .startsAt(rs.getObject("starts_at",OffsetDateTime.class)).sold(rs.getLong("sold"))
                    .capacity(rs.getLong("capacity")).onSale(rs.getBoolean("on_sale"))).list();
    }

    public List<AdminDashboardVenue> venues(OffsetDateTime from,OffsetDateTime to) {
        return jdbc.sql(STOCK+"""
                SELECT v.id,v.name,sum(t.sold)::bigint AS sold,sum(t.capacity)::bigint AS capacity
                FROM showtime s JOIN seat_layout l ON l.id=s.seat_layout_id JOIN venue v ON v.id=l.venue_id
                JOIN totals t ON t.showtime_id=s.id WHERE s.status='SCHEDULED' AND s.starts_at>=:from AND s.starts_at<:to
                GROUP BY v.id,v.name ORDER BY sold DESC,v.id LIMIT 20
                """).param("from",from).param("to",to).query((rs,n)->new AdminDashboardVenue().id(rs.getObject("id",UUID.class))
                    .name(rs.getString("name")).sold(rs.getLong("sold")).capacity(rs.getLong("capacity"))).list();
    }
}
