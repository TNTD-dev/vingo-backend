package vingo.notifications.internal;

import static vingo.notifications.internal.EmailLayout.ACCENT;
import static vingo.notifications.internal.EmailLayout.INK;
import static vingo.notifications.internal.EmailLayout.MONO;
import static vingo.notifications.internal.EmailLayout.MUTED;
import static vingo.notifications.internal.EmailLayout.SANS;
import static vingo.notifications.internal.EmailLayout.escape;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Đọc vé/Catalog có sẵn, không phát hành QR hay ghi bảng module khác. */
@Component
public class TicketEmailRenderer {
    static final String SUBJECT = "Vé sự kiện Vingo";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private record Details(String code,String status,String orderCode,String title,OffsetDateTime startsAt,String venue,String zone,String seat,String tier) {}
    private final JdbcClient jdbc;
    private final String webUrl;

    public TicketEmailRenderer(JdbcClient jdbc) { this(jdbc, "http://localhost:3000"); }

    @Autowired
    public TicketEmailRenderer(JdbcClient jdbc, @Value("${vingo.mail.web-url:http://localhost:3000}") String webUrl) {
        this.jdbc=jdbc;
        this.webUrl=webUrl.replaceAll("/+$","");
    }

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
                SELECT t.code,t.status,o.code AS order_code,e.title,s.starts_at,v.name AS venue,z.name AS zone,
                    CASE WHEN se.id IS NULL THEN 'Khu đứng' ELSE se.row_label || se.number::text END AS seat,
                    pt.name AS tier
                FROM ticket t JOIN order_item i ON i.id=t.order_item_id JOIN orders o ON o.id=i.order_id
                JOIN showtime s ON s.id=t.showtime_id JOIN event e ON e.id=s.event_id
                JOIN hall h ON h.id=s.hall_id JOIN venue v ON v.id=h.venue_id
                JOIN zone z ON z.id=t.zone_id JOIN price_tier pt ON pt.id=i.price_tier_id
                LEFT JOIN seat se ON se.id=t.seat_id
                WHERE t.id IN (:ids) AND t.status IN ('ISSUED','USED') AND o.status='PAID'
                ORDER BY s.starts_at,t.code
                """).param("ids",ticketIds).query((rs,row) -> new Details(rs.getString("code"),rs.getString("status"),
                        rs.getString("order_code"),rs.getString("title"),rs.getObject("starts_at",OffsetDateTime.class),
                        rs.getString("venue"),rs.getString("zone"),rs.getString("seat"),rs.getString("tier"))).list();
        if (rows.size()!=ticketIds.size()) throw new PayloadUnavailableException();

        var orders=rows.stream().map(Details::orderCode).distinct().toList();
        String intro=orders.size()==1
                ? "Cảm ơn bạn đã đặt vé trên Vingo. Đơn <strong style=\"font-family:%s;color:%s;\">%s</strong> gồm %d vé, chi tiết bên dưới."
                        .formatted(MONO,INK,escape(orders.getFirst()),rows.size())
                : "Cảm ơn bạn đã đặt vé trên Vingo. Bạn có %d vé, chi tiết bên dưới.".formatted(rows.size());

        var body=new StringBuilder("""
                <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                <td width="92" style="vertical-align:middle;">%1$s</td>
                <td style="vertical-align:middle;padding-left:18px;">%2$s
                <h1 style="margin:8px 0 0 0;font-size:28px;line-height:1.2;font-weight:700;letter-spacing:-0.5px;color:%3$s;">Vé của bạn đã sẵn sàng</h1>
                </td></tr></table>
                <p style="margin:24px 0;font-size:16px;line-height:1.6;color:%4$s;">%5$s</p>
                """.formatted(EmailLayout.Image.MASCOT_CELEBRATE.tag(""),EmailLayout.pill("Thanh toán thành công",ACCENT),
                        INK,MUTED,intro));
        for (var row:rows) body.append(ticket(row));
        body.append("""
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin-top:8px;">
                <tr><td align="center" style="padding:4px 0 12px 0;">
                <a href="%1$s/tickets" style="display:inline-block;background:%2$s;color:#ffffff;text-decoration:none;border-radius:999px;padding:14px 28px;font-family:%3$s;font-size:16px;line-height:20px;font-weight:600;">Xem vé và mã QR</a>
                </td></tr>
                <tr><td align="center" style="font-size:13px;line-height:1.55;color:#595959;">Mã QR để soát vé nằm trong mục Vé của tôi trên Vingo. Đưa mã cho nhân viên tại cổng.</td></tr>
                </table>
                """.formatted(escape(webUrl),INK,SANS));

        String html=EmailLayout.page(SUBJECT,"Thanh toán thành công, vé của bạn đã sẵn sàng.",body.toString(),
                "Vé đã bán không hoàn tiền hay đổi ghế. Email tự động, vui lòng không trả lời.");
        return new EmailPayload(recipient,SUBJECT,html,EmailLayout.images(EmailLayout.Image.MASCOT_CELEBRATE));
    }

    /** Một thẻ vé: nửa trên nền đen thông tin suất, nửa dưới nền trắng địa điểm và hạng giá. */
    private static String ticket(Details row) {
        var local=row.startsAt().atZoneSameInstant(VIETNAM);
        boolean used="USED".equals(row.status());
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;border:1px solid #e0e0e0;border-radius:20px;margin:0 0 16px 0;">
                <tr><td style="background:%1$s;border-radius:19px 19px 0 0;padding:20px 22px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"><tr>
                <td style="font-family:%2$s;font-size:12px;line-height:16px;letter-spacing:1px;color:#a6a6a6;">VÉ ĐIỆN TỬ · #%3$s</td>
                <td align="right">%4$s</td>
                </tr></table>
                <div style="padding:12px 0 16px 0;font-size:22px;line-height:1.25;font-weight:700;color:#ffffff;">%5$s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0">
                <tr>%6$s%7$s</tr>
                <tr><td colspan="2" style="height:12px;line-height:12px;font-size:0;">&nbsp;</td></tr>
                <tr>%8$s%9$s</tr>
                </table>
                </td></tr>
                <tr><td style="background:#ffffff;border-top:2px dashed #d9d9d9;border-radius:0 0 19px 19px;padding:14px 22px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"><tr>
                <td style="font-size:14px;line-height:20px;color:%10$s;">%11$s</td>
                <td align="right" style="font-size:14px;line-height:20px;font-weight:600;color:%1$s;">%12$s</td>
                </tr></table>
                </td></tr>
                </table>
                """.formatted(INK,MONO,escape(row.code()),
                        used ? EmailLayout.pill("Đã sử dụng","#d9d9d9") : EmailLayout.pill("Hợp lệ",ACCENT),
                        escape(row.title()),
                        field("Ngày",weekday(local.getDayOfWeek())+", "+local.format(DATE)),
                        field("Giờ",local.format(TIME)+" (UTC+7)"),
                        field("Khu",row.zone()),field("Ghế",row.seat()),
                        MUTED,escape(row.venue()),escape(row.tier()));
    }

    private static String field(String label,String value) {
        return """
                <td width="50%%" style="vertical-align:top;"><div style="font-family:%s;font-size:11px;line-height:14px;letter-spacing:1px;text-transform:uppercase;color:#a6a6a6;">%s</div><div style="padding-top:4px;font-size:15px;line-height:20px;font-weight:600;color:#ffffff;">%s</div></td>"""
                .formatted(MONO,escape(label),escape(value));
    }

    private static String weekday(DayOfWeek day) {
        return day==DayOfWeek.SUNDAY ? "CN" : "T"+(day.getValue()+1);
    }
}
