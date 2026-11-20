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

/** SQL phát vé, khoá vé và ghi lịch sử quét trong transaction của TicketingService. */
@Repository
public class TicketingRepository {

    private final JdbcClient jdbc;

    public TicketingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Đọc id vé của đơn theo thứ tự seat_id/zone_id/seq để lần gọi lại giữ nguyên danh sách. */
    public List<UUID> ticketIdsForOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT id FROM ticket
                        WHERE order_id = :order
                        ORDER BY seat_id NULLS LAST, zone_id NULLS LAST, seq NULLS LAST, id
                        """)
                .param("order", orderId)
                .query(UUID.class)
                .list();
    }

    /** Chèn vé (ngồi: seat_id; đứng: zone_id + seq), trả rỗng khi trùng code để caller sinh mã khác. */
    public Optional<UUID> insertTicket(UUID orderId, OrderLine line, Integer seq, String code) {
        boolean seated = line.seatId() != null;
        return jdbc.sql("""
                        INSERT INTO ticket (order_id, showtime_id, seat_id, zone_id, seq, code, qr_version, status)
                        VALUES (:order, :showtime, :seat, :zone, :seq, :code, 1, 'ISSUED')
                        ON CONFLICT (code) DO NOTHING
                        RETURNING id
                        """)
                .param("order", orderId)
                .param("showtime", line.showtimeId())
                .param("seat", seated ? line.seatId() : null, Types.OTHER)
                .param("zone", seated ? null : line.zoneId(), Types.OTHER)
                .param("seq", seated ? null : seq, Types.SMALLINT)
                .param("code", code)
                .query(UUID.class)
                .optional();
    }

    /** Tìm chủ vé mà không trả thông tin nhạy cảm trước khi kiểm quyền. */
    public Optional<UUID> ticketOwner(UUID ticketId) {
        return jdbc.sql("""
                        SELECT o.user_id
                        FROM ticket t
                        JOIN ticket_order o ON o.id = t.order_id
                        WHERE t.id = :id
                        """)
                .param("id", ticketId)
                .query(UUID.class)
                .optional();
    }

    /** Khoá vé theo id trước mọi kiểm tra trạng thái, chỉ khoá ticket để tránh đảo thứ tự khoá đơn. */
    public Optional<ManagedTicket> lockTicket(UUID ticketId) {
        return jdbc.sql("""
                        SELECT t.id, t.status::text AS status, t.order_id, o.user_id
                        FROM ticket t
                        JOIN ticket_order o ON o.id = t.order_id
                        WHERE t.id = :id
                        FOR UPDATE OF t
                        """)
                .param("id", ticketId)
                .query((rs, row) -> new ManagedTicket(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("status")))
                .optional();
    }

    /** Vô hiệu vé đã khoá, không thay đổi tồn kho của ghế. */
    public void voidTicket(UUID ticketId) {
        int updated = jdbc.sql("UPDATE ticket SET status = 'VOID' WHERE id = :id AND status = 'ISSUED'")
                .param("id", ticketId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Không thể vô hiệu vé ISSUED sau khi đã khoá " + ticketId);
        }
    }

    /** Tăng qr_version của vé đã khoá; QR cũ mất hiệu lực ngay khi commit. */
    public void reissueQr(UUID ticketId) {
        jdbc.sql("UPDATE ticket SET qr_version = qr_version + 1 WHERE id = :id")
                .param("id", ticketId)
                .update();
    }

    /** Chọn trang id vé của chủ sở hữu với keyset id giảm dần (uuidv7). */
    public List<UUID> listTickets(UUID userId, boolean past, String status, UUID afterId,
                                  OffsetDateTime now, int limit) {
        var query = jdbc.sql("""
                        SELECT t.id
                        FROM ticket t
                        JOIN ticket_order o ON o.id = t.order_id
                        JOIN showtime s ON s.id = t.showtime_id
                        WHERE o.user_id = :user
                        """
                        + (past ? " AND s.ends_at <= :now" : " AND s.ends_at > :now")
                        + (status == null ? "" : " AND t.status = CAST(:status AS ticket_status)")
                        + (afterId == null ? "" : " AND t.id < :afterId")
                        + " ORDER BY t.id DESC LIMIT :limit")
                .param("user", userId)
                .param("now", now)
                .param("limit", limit);
        if (status != null) {
            query.param("status", status);
        }
        if (afterId != null) {
            query.param("afterId", afterId);
        }
        return query.query(UUID.class).list();
    }

    /** Chọn trang id vé mọi buyer theo bộ lọc chính xác và keyset id giảm dần. */
    public List<UUID> adminListTickets(String code, UUID orderId, UUID showtimeId, String status,
                                       UUID afterId, int limit) {
        var query = jdbc.sql("""
                        SELECT t.id
                        FROM ticket t
                        WHERE true
                        """
                        + (code == null ? "" : " AND t.code = upper(:code)")
                        + (orderId == null ? "" : " AND t.order_id = :order")
                        + (showtimeId == null ? "" : " AND t.showtime_id = :showtime")
                        + (status == null ? "" : " AND t.status = CAST(:status AS ticket_status)")
                        + (afterId == null ? "" : " AND t.id < :afterId")
                        + " ORDER BY t.id DESC LIMIT :limit")
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
        if (afterId != null) {
            query.param("afterId", afterId);
        }
        return query.query(UUID.class).list();
    }

    /** Khoá riêng dòng vé theo id để hai máy quét không cùng chấp nhận một QR. */
    public Optional<ScannableTicket> lockTicketForScan(UUID ticketId) {
        return jdbc.sql("""
                        SELECT t.id, t.showtime_id, t.qr_version, t.status::text AS status, t.used_at, t.code,
                               COALESCE(ez.display_name, z.name) AS zone_name, seat.row_label, seat.number::integer AS number
                        FROM ticket t
                        JOIN showtime s ON s.id = t.showtime_id
                        LEFT JOIN seat ON seat.id = t.seat_id
                        JOIN zone z ON z.id = COALESCE(t.zone_id, seat.zone_id)
                        LEFT JOIN event_zone ez ON ez.event_id = s.event_id AND ez.zone_id = z.id
                        WHERE t.id = :id
                        FOR UPDATE OF t
                        """)
                .param("id", ticketId)
                .query((rs, row) -> new ScannableTicket(
                        rs.getObject("id", UUID.class),
                        rs.getObject("showtime_id", UUID.class),
                        rs.getInt("qr_version"),
                        rs.getString("status"),
                        rs.getObject("used_at", OffsetDateTime.class),
                        rs.getString("code"),
                        rs.getString("zone_name"),
                        rs.getString("row_label"),
                        rs.getObject("number", Integer.class)))
                .optional();
    }

    /** Đổi vé đang khoá sang USED với cùng thời điểm ghi lượt quét. */
    public void markUsed(UUID ticketId, OffsetDateTime now) {
        int updated = jdbc.sql("UPDATE ticket SET status = 'USED', used_at = :now WHERE id = :id AND status = 'ISSUED'")
                .param("now", now)
                .param("id", ticketId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Không thể dùng vé ISSUED sau khi đã khoá " + ticketId);
        }
    }

    /** Ghi suất người quét gửi lên, kể cả khi QR giả (ticketId = null) hoặc khác suất. */
    public void insertCheckin(UUID ticketId, UUID showtimeId, UUID scanner, String gateCode,
                              ResultEnum result, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO ticket_scan (ticket_id, showtime_id, gate_code, scanned_by, result, scanned_at)
                        VALUES (:ticket, :showtime, :gate, :scanner, CAST(:result AS scan_result), :now)
                        """)
                .param("ticket", ticketId, Types.OTHER)
                .param("showtime", showtimeId)
                .param("gate", gateCode, Types.VARCHAR)
                .param("scanner", scanner)
                .param("result", result.getValue())
                .param("now", now)
                .update();
    }

    /** Chọn trang lượt quét của suất, mới nhất trước với keyset scanned_at và id. */
    public List<PagePosition> listCheckins(UUID showtimeId, PagePosition after, int limit) {
        var query = jdbc.sql("SELECT id, scanned_at FROM ticket_scan WHERE showtime_id = :showtime"
                        + (after == null ? "" : " AND (scanned_at, id) < (:at, :id)")
                        + " ORDER BY scanned_at DESC, id DESC LIMIT :limit")
                .param("showtime", showtimeId)
                .param("limit", limit);
        if (after != null) {
            query.param("at", after.at()).param("id", after.id());
        }
        return query.query((rs, row) -> new PagePosition(
                rs.getObject("id", UUID.class),
                rs.getObject("scanned_at", OffsetDateTime.class)))
                .list();
    }

    public record PagePosition(UUID id, OffsetDateTime at) {}

    public record ScannableTicket(
            UUID id,
            UUID showtimeId,
            int qrVersion,
            String status,
            OffsetDateTime usedAt,
            String code,
            String zone,
            String row,
            Integer number
    ) {}

    public record ManagedTicket(UUID id, UUID orderId, UUID userId, String status) {}
}
