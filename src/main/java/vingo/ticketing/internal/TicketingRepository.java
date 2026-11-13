package vingo.ticketing.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vingo.api.model.CheckinResult.ResultEnum;
import vingo.booking.OrderLine;

/** SQL phát vé, khoá vé và ghi lịch sử trong transaction của TicketingService. */
@Repository
public class TicketingRepository {

    private final JdbcClient jdbc;

    /** Dùng JdbcClient để cùng tham gia transaction nghiệp vụ. */
    public TicketingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // shortcut: đọc thẳng bảng catalog/booking, đổi sang service khi có
    /** Đọc id vé của đơn theo thứ tự dòng đơn và seq để lần gọi lại giữ nguyên danh sách. */
    public List<UUID> ticketIdsForOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT t.id FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE oi.order_id = :order ORDER BY oi.id, t.seq
                        """)
                .param("order", orderId).query(UUID.class).list();
    }

    /** Chèn vé với giờ ứng dụng, trả rỗng khi trùng code để caller sinh mã khác. */
    public Optional<UUID> insertTicket(OrderLine line, int seq, String code, String qrToken, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO ticket (order_item_id, seq, showtime_id, zone_id, seat_id,
                                            code, qr_token, status, issued_at)
                        VALUES (:item, :seq, :showtime, :zone, :seat, :code, :token, 'ISSUED', :now)
                        ON CONFLICT (code) DO NOTHING RETURNING id
                        """)
                .param("item", line.orderItemId()).param("seq", seq).param("showtime", line.showtimeId())
                .param("zone", line.zoneId()).param("seat", line.seatId(), Types.OTHER)
                .param("code", code).param("token", qrToken).param("now", now).query(UUID.class).optional();
    }

    /** Tìm chủ vé mà không trả thông tin nhạy cảm trước khi kiểm quyền. */
    public Optional<UUID> ticketOwner(UUID ticketId) {
        return jdbc.sql("""
                        SELECT o.user_id FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        JOIN orders o ON o.id = oi.order_id WHERE t.id = :id
                        """)
                .param("id", ticketId).query(UUID.class).optional();
    }

    /** Khoá vé theo id trước mọi kiểm tra trạng thái, chỉ khoá ticket để tránh đảo thứ tự khoá đơn. */
    public Optional<ManagedTicket> lockTicket(UUID ticketId) {
        return jdbc.sql("""
                        SELECT t.id, t.status, oi.order_id, o.user_id
                        FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        JOIN orders o ON o.id = oi.order_id WHERE t.id = :id FOR UPDATE OF t
                        """)
                .param("id", ticketId).query((rs, row) -> new ManagedTicket(
                        rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("status"))).optional();
    }

    /** Vô hiệu vé đã khoá, không thay đổi tồn kho của ghế. */
    public void voidTicket(UUID ticketId, OffsetDateTime now) {
        int updated = jdbc.sql("UPDATE ticket SET status = 'VOID', voided_at = :now WHERE id = :id AND status = 'ISSUED'")
                .param("now", now).param("id", ticketId).update();
        if (updated != 1) {
            throw new IllegalStateException("Không thể vô hiệu vé ISSUED sau khi đã khoá " + ticketId);
        }
    }

    /** Chỉ đổi qr_token của vé đã khoá; QR cũ mất hiệu lực ngay khi commit. */
    public void reissueQr(UUID ticketId, String qrToken) {
        jdbc.sql("UPDATE ticket SET qr_token = :token WHERE id = :id").param("token", qrToken).param("id", ticketId).update();
    }

    /** Chọn trang id vé của chủ sở hữu với keyset issued_at và id. */
    public List<PagePosition> listTickets(UUID userId, boolean past, String status, PagePosition after,
                                           OffsetDateTime now, int limit) {
        var query = jdbc.sql("""
                        SELECT t.id, t.issued_at FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        JOIN orders o ON o.id = oi.order_id JOIN showtime s ON s.id = t.showtime_id
                        WHERE o.user_id = :user
                        """
                        + (past ? " AND s.ends_at <= :now" : " AND s.ends_at > :now")
                        + (status == null ? "" : " AND t.status = :status")
                        + (after == null ? "" : " AND (t.issued_at, t.id) < (:at, :id)")
                        + " ORDER BY t.issued_at DESC, t.id DESC LIMIT :limit")
                .param("user", userId).param("now", now).param("limit", limit);
        if (status != null) {
            query.param("status", status);
        }
        if (after != null) {
            query.param("at", after.at()).param("id", after.id());
        }
        return query.query((rs, row) -> new PagePosition(rs.getObject("id", UUID.class),
                rs.getObject("issued_at", OffsetDateTime.class))).list();
    }

    /** Chọn trang id vé mọi buyer theo bộ lọc chính xác và keyset issued_at/id giảm dần. */
    public List<PagePosition> adminListTickets(String code, UUID orderId, UUID showtimeId, String status,
                                                PagePosition after, int limit) {
        var query = jdbc.sql("""
                        SELECT t.id, t.issued_at FROM ticket t JOIN order_item oi ON oi.id = t.order_item_id
                        WHERE true
                        """
                        + (code == null ? "" : " AND t.code = upper(:code)")
                        + (orderId == null ? "" : " AND oi.order_id = :order")
                        + (showtimeId == null ? "" : " AND t.showtime_id = :showtime")
                        + (status == null ? "" : " AND t.status = :status")
                        + (after == null ? "" : " AND (t.issued_at, t.id) < (:at, :id)")
                        + " ORDER BY t.issued_at DESC, t.id DESC LIMIT :limit")
                .param("limit", limit);
        if (code != null) {
            query.param("code", code);
        }
        if (orderId != null) {
            query.param("order", orderId);
        }
        if (showtimeId != null) {
            query.param("showtime", showtimeId);
        }
        if (status != null) {
            query.param("status", status);
        }
        if (after != null) {
            query.param("at", after.at()).param("id", after.id());
        }
        return query.query((rs, row) -> new PagePosition(rs.getObject("id", UUID.class),
                rs.getObject("issued_at", OffsetDateTime.class))).list();
    }

    /** Khoá riêng dòng vé để hai máy quét không cùng chấp nhận một QR. */
    public Optional<ScannableTicket> lockTicket(String qrToken) {
        return jdbc.sql("""
                        SELECT t.id, t.showtime_id, t.status, t.used_at, t.code,
                               z.name AS zone_name, s.row_label, s.number
                        FROM ticket t JOIN zone z ON z.id = t.zone_id LEFT JOIN seat s ON s.id = t.seat_id
                        WHERE t.qr_token = :token FOR UPDATE OF t
                        """)
                .param("token", qrToken).query((rs, row) -> new ScannableTicket(
                        rs.getObject("id", UUID.class), rs.getObject("showtime_id", UUID.class),
                        rs.getString("status"), rs.getObject("used_at", OffsetDateTime.class),
                        rs.getString("code"), rs.getString("zone_name"), rs.getString("row_label"),
                        rs.getObject("number", Integer.class))).optional();
    }

    /** Đổi vé đang khoá sang USED với cùng thời điểm ghi lượt quét. */
    public void markUsed(UUID ticketId, OffsetDateTime now) {
        int updated = jdbc.sql("UPDATE ticket SET status = 'USED', used_at = :now WHERE id = :id AND status = 'ISSUED'")
                .param("now", now).param("id", ticketId).update();
        if (updated != 1) {
            throw new IllegalStateException("Không thể dùng vé ISSUED sau khi đã khoá " + ticketId);
        }
    }

    /** Ghi suất người quét gửi lên, kể cả khi khác suất của vé. */
    public void insertCheckin(UUID ticketId, UUID showtimeId, UUID scanner, String gate,
                              ResultEnum result, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO checkin (ticket_id, showtime_id, scanned_by, gate, result, scanned_at)
                        VALUES (:ticket, :showtime, :scanner, :gate, :result, :now)
                        """)
                .param("ticket", ticketId).param("showtime", showtimeId).param("scanner", scanner)
                .param("gate", gate, Types.VARCHAR).param("result", result.getValue()).param("now", now).update();
    }

    /** Chọn trang lượt quét của suất, mới nhất trước với keyset scanned_at và id. */
    public List<PagePosition> listCheckins(UUID showtimeId, PagePosition after, int limit) {
        var query = jdbc.sql("SELECT id, scanned_at FROM checkin WHERE showtime_id = :showtime"
                        + (after == null ? "" : " AND (scanned_at, id) < (:at, :id)")
                        + " ORDER BY scanned_at DESC, id DESC LIMIT :limit")
                .param("showtime", showtimeId).param("limit", limit);
        if (after != null) {
            query.param("at", after.at()).param("id", after.id());
        }
        return query.query((rs, row) -> new PagePosition(rs.getObject("id", UUID.class),
                rs.getObject("scanned_at", OffsetDateTime.class))).list();
    }

    /** Khoá keyset gồm thời điểm và id để các bản ghi trùng giờ không bị lặp hoặc bỏ sót. */
    public record PagePosition(UUID id, OffsetDateTime at) {}

    /** Dữ liệu vé đã khoá cùng thông tin vị trí để trả kết quả quét. */
    public record ScannableTicket(UUID id, UUID showtimeId, String status, OffsetDateTime usedAt,
                                   String code, String zone, String row, Integer number) {}

    /** Dữ liệu vé đã khoá để kiểm chủ và trạng thái mà không mang email hoặc QR ra khỏi repository. */
    public record ManagedTicket(UUID id, UUID orderId, UUID userId, String status) {}
}
