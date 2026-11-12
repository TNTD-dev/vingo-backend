package vingo.notifications.internal;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Đọc vé/Catalog có sẵn, không phát hành QR hay ghi bảng module khác. */
@Component
public class TicketEmailRenderer {
    private record Details(String code,String orderCode,String title,OffsetDateTime startsAt,String venue,String zone,String seat,String tier) {}
    private final JdbcClient jdbc;
    public TicketEmailRenderer(JdbcClient jdbc) { this.jdbc=jdbc; }

    /**
     * Đọc đủ các vé ISSUED/USED của đơn PAID và escape thông tin trước khi dựng
     * HTML. Thiếu vé thì báo chưa có payload để giữ thư chờ xử lý. Không tự phát
     * QR, sửa Catalog hoặc xác minh quyền của recipient thay cho caller.
     */
    public EmailPayload render(String recipient,List<UUID> ticketIds) {
        if (ticketIds.isEmpty() || ticketIds.size()>20 || ticketIds.stream().distinct().count()!=ticketIds.size()) {
            throw new PayloadUnavailableException();
        }
        var rows=jdbc.sql("""
                SELECT t.code,o.code AS order_code,e.title,s.starts_at,v.name AS venue,z.name AS zone,
                    CASE WHEN se.id IS NULL THEN 'Khu đứng' ELSE se.row_label || se.number::text END AS seat,
                    pt.name AS tier
                FROM ticket t JOIN order_item i ON i.id=t.order_item_id JOIN orders o ON o.id=i.order_id
                JOIN showtime s ON s.id=t.showtime_id JOIN event e ON e.id=s.event_id
                JOIN hall h ON h.id=s.hall_id JOIN venue v ON v.id=h.venue_id
                JOIN zone z ON z.id=t.zone_id JOIN price_tier pt ON pt.id=i.price_tier_id
                LEFT JOIN seat se ON se.id=t.seat_id
                WHERE t.id IN (:ids) AND t.status IN ('ISSUED','USED') AND o.status='PAID'
                ORDER BY t.id
                """).param("ids",ticketIds).query((rs,row) -> new Details(rs.getString("code"),rs.getString("order_code"),
                        rs.getString("title"),rs.getObject("starts_at",OffsetDateTime.class),rs.getString("venue"),
                        rs.getString("zone"),rs.getString("seat"),rs.getString("tier"))).list();
        if (rows.size()!=ticketIds.size()) throw new PayloadUnavailableException();
        var html=new StringBuilder("<h1>Vé Vingo của bạn</h1>");
        for (var row:rows) {
            html.append("<section><h2>").append(escape(row.title())).append("</h2><p>")
                .append(row.startsAt().atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC+7'")))
                .append(" · ").append(escape(row.venue())).append("</p><p>")
                .append(escape(row.zone())).append(" · ").append(escape(row.seat())).append(" · ")
                .append(escape(row.tier())).append("</p><p>Mã vé: ").append(escape(row.code()))
                .append(" · Đơn: ").append(escape(row.orderCode())).append("</p></section>");
        }
        html.append("<p>Bạn có thể xem vé trong mục Vé của tôi trên Vingo.</p>");
        return new EmailPayload(recipient,"Vé sự kiện Vingo",html.toString(),List.of());
    }
    /** Mã hoá ký tự HTML để tên sự kiện/địa điểm không trở thành markup thực thi. */
    private static String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }
}
